# Utilities — Library Health Screen

> **Status: DRAFT** (2026-04-22)
>
> Fourth Utilities screen. Companion to `PROPOSAL_UTILITIES.md` and the
> Volumes / Actress Data / Backup & Restore specs. Feature branch:
> `utilities-library-health`.

## Purpose

Surface data-quality issues across the library in one place. The first three
Utilities screens are *action* surfaces ("sync this volume", "load this YAML",
"restore this snapshot"). Library Health is the *diagnostic* surface: it scans
the DB + filesystem + YAML catalog, reports what looks wrong, and offers a
path to fix it — either inline, or by linking back to the screen that owns the
action.

Today these problems are discovered ad-hoc (noticing a missing cover, tripping
over a stale location during sync, realizing a YAML never got loaded). A
single dashboard turns that into a deliberate checkup.

## Scope

**Phase 1 (this branch) — six checks:**

1. **Stale title locations** — `title_locations` rows whose file wasn't seen on
   the latest sync of that volume. Already computable via
   `StaleLocationsService`; Library Health rolls it up across volumes and
   links to the per-volume fix on the Volumes screen.
2. **Orphaned covers** — cover image files on disk with no matching title in
   the DB. Scans cover paths under the configured `coversDir`.
3. **Titles without covers** — title rows whose resolved `CoverPath` points at
   a file that doesn't exist (or at nothing).
4. **Unloaded actress YAMLs** — YAML files under `actresses/` that have never
   been applied to the DB (no matching `actresses.slug` row or slug exists but
   profile fields are empty). Links to Actress Data.
5. **Unresolved aliases** — `actress_aliases` rows pointing at an actress slug
   that doesn't exist (e.g. after a rename). Links to Actress Data → Aliases.
6. **Duplicate title codes** — titles with more than one `title_locations` row
   on the *same* volume (intra-volume duplicates only; cross-volume is
   expected given multi-NAS).

**Explicitly out of Phase 1:**
- Automatic scheduled scans — the user opens the screen, it scans.
- Write actions that run destructive fixes in bulk without confirmation.
- Orphaned video files (not covers) — videos are large and rare to orphan;
  add in Phase 2 if it becomes a real problem.
- Thumbnail backlog — already covered by the bg-thumbs chip; Library Health
  links to it, doesn't re-render it.
- Dead external references (DMM IDs that no longer resolve, etc.) — out of
  scope; network-bound checks belong elsewhere.

## Identity

- **Color:** amber (`#f59e0b` / `#78350f`) — distinct from the prior three palettes
  (Volumes blue, Actress Data magenta, Backup teal). Amber reads as "needs
  attention" without being alarming red.
- **Icon:** stethoscope / heart-pulse / activity-line — diagnostic connotation.
- **Tile label:** "Library Health".

## Layout

Two-pane: check list on the left, detail on the right. Matches the file-
explorer aesthetic of the prior screens.

```
┌──────────────────────────────────────────────────────────────┐
│  Library Health                         [ Scan library ▸ ]   │
├────────────────────────────┬─────────────────────────────────┤
│ ● Stale locations     12   │  Stale title locations          │
│   Orphaned covers     0    │  ──────────────────────────     │
│ ● Titles w/o covers   3    │  12 rows across 4 volumes.      │
│   Unloaded YAMLs      0    │                                 │
│ ● Unresolved aliases  1    │  Volume K — 5 rows  [Open →]    │
│   Duplicate codes     0    │  Volume L — 4 rows  [Open →]    │
│                            │  Volume M — 2 rows  [Open →]    │
│                            │  Volume N — 1 row   [Open →]    │
│                            │                                 │
│                            │  [Fix on Volumes screen →]      │
└────────────────────────────┴─────────────────────────────────┘
```

- Left pane lists every check with a count. A bullet (`●`) in the check's
  category color marks checks with non-zero findings; healthy checks are
  greyed out but still visible (the list itself is a health summary).
- Right pane shows the active check's detail: a short description, a sample
  of offenders (capped at ~50 rows), and one of:
  - An inline fix button (for cheap, reversible fixes scoped to this screen)
  - A link back to the owning screen (for fixes that already have a home)
- "Scan library" at the top re-runs every check. Cheap enough to run on open;
  button exists for refresh after the user fixes something elsewhere.

## States

- **Empty (on load)** — no scan has run; show `[Scan library]` centered with
  "Run a scan to see health findings." Prevents a flash of "everything is
  fine" before the scan returns.
- **Scanning** — scan is a task (atomic like every other Utilities task).
  During scan, the pill shows the active check; finished checks populate
  their counts as their phases end.
- **Idle (scan complete)** — counts rendered; user clicks a check to see
  detail.
- **Detail** — right pane shows the selected check's findings.

No visualize-then-confirm pattern here: the scan itself is read-only, and
inline fixes (if any) are small enough that a standard confirm dialog is
fine. The cross-screen "Open fix on Volumes" link is the same confirmation
flow those screens already have.

## Fix routing

Each check declares where its fix lives:

| Check                    | Where the fix runs                                |
|--------------------------|---------------------------------------------------|
| Stale locations          | Volumes screen → per-volume Clean action          |
| Orphaned covers          | Inline (Phase 2: confirm + delete files)          |
| Titles without covers    | Surface only in Phase 1; fix = re-sync that title |
| Unloaded YAMLs           | Actress Data → Load one                           |
| Unresolved aliases       | Actress Data → Aliases (edit/delete)              |
| Duplicate codes          | Surface only in Phase 1 (needs judgement call)    |

"Surface only" checks show the findings but leave resolution to the user —
duplicates especially need human judgement on which copy to keep.

## Backend

A single `library.scan` task runs all checks as phases:

```
library.scan
 ├─ stale_locations
 ├─ orphaned_covers
 ├─ titles_without_covers
 ├─ unloaded_yamls
 ├─ unresolved_aliases
 └─ duplicate_codes
```

Each phase emits a `phase.ended` with a summary count, and a final
aggregated `LibraryHealthReport` is held in a short-lived in-memory cache
on the server so the right-pane detail endpoints can read consistent data
without re-scanning. (Cache key = run id; TTL = a few minutes or until the
next scan.)

New backend classes (package `com.organizer3.utilities.health`):

- `LibraryHealthCheck` — sealed interface with `id()`, `label()`, `run()`.
- `LibraryHealthService` — composes checks; runs the scan; holds the cache.
- `LibraryHealthReport` — report record with per-check findings.
- `StaleLocationsCheck`, `OrphanedCoversCheck`, etc. — per-check classes.
- `ScanLibraryTask` — the task wrapper.

HTTP:
- `POST /api/utilities/tasks/library.scan/run` — starts scan.
- `GET  /api/utilities/health/report/latest` — latest report summary.
- `GET  /api/utilities/health/report/latest/{checkId}` — detail for one check.

## Frontend module

`src/main/resources/public/modules/utilities-library-health.js`, class
prefix `lh-` (consistent with `al-`, `bk-`, `ad-`). No new state-factory
required — the module state is small (selected check, latest report).

## Testing

- Per-check service tests against in-memory SQLite + temp dirs, covering
  the empty and populated cases.
- `ScanLibraryTask` test composes a stub check list and asserts one phase
  per check + aggregate terminal status.
- Route test covers the two endpoints' shape + 404-on-unknown-check.
- Frontend: visual smoke via Playwright is sufficient; no new state logic
  needing coverage beyond that.

## Phase 2+ candidates (deliberately parked)

- Orphaned video files
- Thumbnail backlog surfacing (once we decide if it belongs here vs its chip)
- Scheduled scans with a "last scanned" badge
- Fix-in-place actions for orphaned covers (delete files) and duplicates
  (pick canonical location)
- Cross-volume duplicate reporting with a policy for "canonical volume"

## Open questions

- **Q1:** Should "titles without covers" include titles whose cover resolves
  to a file on an unmounted/disconnected volume? Current inclination: no —
  that's expected until the volume is remounted, and we'd spam the list.
  Only flag if the volume *has* been synced recently but the file is missing.
- **Q2:** For duplicate codes, should the check scope to the user-marked
  "canonical" volume if one exists in config? No such marker exists today,
  so we'd have to add one. Probably defer to Phase 2.
