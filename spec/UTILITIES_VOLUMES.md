# Utilities — Volumes Screen

> **Status: DRAFT** (2026-04-21)
>
> Screen-specific spec for the first Utilities screen. Companion to `PROPOSAL_UTILITIES.md`.
> Feature branch: `utilities-volumes`.

## Purpose

Maintain and sync individual volumes. This is the user's answer to "I added new content to volume A and want the library to catch up." Today this takes four commands and requires knowing the mount lifecycle; here it is one click.

Secondary purpose: show per-volume health at a glance and fix issues surfaced there.

## Identity

- **Color:** deep blue. Distinct from any existing tile; evokes "storage / disk" without being literal.
- **Icon:** simple stacked-disk glyph (three horizontal ellipses, like a traditional database/volume icon).
- **Tile label:** "Volumes".

Both color and icon are fixed once picked — they become the user's visual anchor for this screen.

## Layout

Two-pane target + operations (per `PROPOSAL_UTILITIES.md` convention).

```
┌─────────────────────────────────────────────────────────────┐
│  Volumes                                                    │
├────────────────────┬────────────────────────────────────────┤
│ [ Volume A    🟢 ] │                                        │
│ [ Volume BG   🟡3] │    ┌─ Right pane: operations stage ─┐  │
│ [ Volume HJ   🔴1] │    │                                │  │
│ [ Volume QP   ⚫  ] │    │   (dynamic per selection)      │  │
│ [ Volume ST   🟢 ] │    │                                │  │
│                    │    └────────────────────────────────┘  │
└────────────────────┴────────────────────────────────────────┘
```

**Proportions:** left ~30%, right ~70%. Left is a fixed-width list; right expands to fill.

## Left pane: volume list

### Row

Each row shows, inline:

- Volume display name (e.g., "Volume A")
- Volume ID in muted text (e.g., `//pandora/jav_A`)
- Size + title count as a single compact line (e.g., `4.2 TB · 312 titles`)
- Last-synced timestamp, relative (e.g., `2 days ago`), or `Never synced`
- **Health badge** — a single dot with a count:
  - 🟢 Healthy (no issues)
  - 🟡 *N* — warnings (orphan covers, missing thumbs, drift)
  - 🔴 *N* — errors (probe failures, unreadable files)
  - ⚫ Offline / unreachable

### Behavior

- Click a row → right pane loads that volume. Selection highlights the row.
- Selection is **sticky**: persisted to `localStorage`, restored on reload.
- Rows auto-refresh their health badge every 30s while the screen is open (cheap state endpoint; no heavy work).
- Offline volumes stay in the list with a ⚫ badge — they are not hidden. Clicking an offline volume still loads its last-known detail; operations that need it online will surface the problem inline (see Error states).

### Empty state

If no volumes are configured: center the left pane with a short message pointing to `organizer-config.yaml`. This is an edge case — organizer doesn't boot without volumes — but render it sanely rather than a blank list.

## Right pane: operations stage

### Mode 1 — Nothing selected

Welcome panel:

> **Pick a volume on the left to manage it.**
> Sync, clean up covers, investigate errors, and more.

A small illustration or the Volumes icon, dim. No actions.

### Mode 2 — Volume selected (detail view)

Top of pane: **volume detail** card.

- Display name (large), volume ID (muted)
- Stats row: size, title count, last-synced
- Structure type (muted, e.g., `structure: qnap-multi`)
- **Health section**: enumerated problems. Zero issues → "All healthy" with a green checkmark. Any issues → a list of rows, one per problem category:

```
Health

  🟡  3 orphan covers                         [ Clean up → ]
  🟡  47 titles missing thumbnails            [ Generate → ]
  🔴  2 probe failures                        [ Investigate → ]
```

Below the detail card: **Operations** section, flat list of buttons. Phase 1 ships with:

- **[ Sync ]** — primary, filled button. Brings the volume up to date end-to-end.

Secondary actions in Phase 1 are limited to the one surfaced from health: **Clean up orphan covers** (only when orphans exist). Additional operations land in later phases.

### Mode 3 — Visualization (pre-run confirm)

For operations with user-visible side effects (Clean up orphan covers, future: merges/renames/restores). Takes over the right pane content area below the detail card:

```
Clean up orphan covers                  ← heading
The following 3 cover files will be deleted:

  ✗ /actresses/Yua Mikami/old_cover.jpg
  ✗ /actresses/Sora Aoi/stale_cover.jpg
  ✗ /archive/Nana Ogura/duplicate.jpg

                         [ Cancel ]  [ Proceed → ]
```

Cancel returns to detail view. Proceed transitions to Mode 4.

Sync has no visualization — it has no user-visible renames/deletes. Click goes straight to Mode 4.

### Mode 4 — Running (progress)

Right pane below detail card becomes a progress view:

```
Syncing Volume A                                [ ⏵ running ]

  ✓ Mount volume                      (0.8s)
  ✓ Sync titles                       (12s) — 312 scanned, 4 added, 1 updated
  ⏳ Sync covers                       — 18 of 47 covers
    [████████░░░░░░░░░░░░░░░░░░░░]
  ○ Unmount volume
```

- Each phase: pending (`○`) → running (`⏳` + spinner or bar) → done (`✓`) → failed (`✗`).
- Phase line shows elapsed time once complete, and a one-line summary of what the phase did.
- A collapsible **"Show log output"** drawer below shows the raw streamed lines for debugging. Collapsed by default.

### Mode 5 — Summary (post-run)

After the last phase completes (success or failure):

```
Sync complete                                   (14s total)

  ✓ Mount volume
  ✓ Sync titles      — 4 added, 1 updated, 312 scanned
  ✓ Sync covers      — 47 of 47
  ✓ Unmount volume

                                    [ Done ]
```

- On success: green check banner.
- On partial failure: yellow banner, show which phases succeeded and which failed, with the error message inline. `[ Done ]` returns to detail; `[ Retry ]` re-runs the failed phase(s) — **deferred to a later phase; Phase 1 only offers Done.**
- `[ Done ]` refreshes the detail view and returns to Mode 2.

### Error states

- **Volume offline** + user clicks Sync → skip visualization, show a single-phase failure: "Could not mount volume" with the underlying error. The Sync task is responsible for attempting the mount; the failure is surfaced, not prevented at UI.
- **Task already running elsewhere**: not a concern (single user, per `PROPOSAL_UTILITIES.md`).

## Tasks

### SyncVolumeTask

| Field         | Value |
|---------------|-------|
| `id`          | `volume.sync` |
| `title`       | "Sync volume" |
| `inputs`      | `volumeId: String` |
| `visualize?`  | No |
| `phases`      | `mount`, `syncTitles`, `syncCovers`, `unmount` |

**Orchestration:**
1. `mount <id>` — fail the task if this fails; unmount is skipped.
2. `sync all` scoped to this volume (today: `sync <id>` or equivalent partition-scoped invocation).
3. `sync covers <id>`.
4. `unmount <id>` — attempted even if `syncCovers` fails, so we don't leave a dangling mount.

Implementation uses a `CommandInvoker` that captures each command's output into the task's phase events. No changes to `SyncCommand`, `MountCommand`, `UnmountCommand`.

### CleanOrphanCoversTask

| Field         | Value |
|---------------|-------|
| `id`          | `volume.clean_orphan_covers` |
| `title`       | "Clean up orphan covers" |
| `inputs`      | `volumeId: String` |
| `visualize?`  | Yes — lists each file to be deleted |
| `phases`      | `scan`, `delete` |

**Visualization payload:** list of `{ path, reason }` entries produced by a dry scan (likely `prune-covers` in a report-only mode, or a small helper query). The user sees exact paths before clicking Proceed.

**Execution:** runs the prune itself; emits one event per deletion; summary shows count.

## HTTP surface (this screen's slice)

- `GET  /utilities/state/volumes` — array of `{ id, displayName, path, sizeBytes, titleCount, lastSyncedAt, structureType, status: "online"|"offline", health: [{level, category, count, description}] }`.
- `GET  /utilities/state/volumes/{id}` — detail for one volume (same shape, single element).
- `POST /utilities/tasks/volume.sync/run` — body `{ volumeId }` → `{ runId }`.
- `POST /utilities/tasks/volume.clean_orphan_covers/preview` — body `{ volumeId }` → `{ filesToDelete: [...] }`.
- `POST /utilities/tasks/volume.clean_orphan_covers/run` — body `{ volumeId }` → `{ runId }`.
- `GET  /utilities/runs/{runId}/stream` — SSE stream of task events.
- `GET  /utilities/runs/{runId}` — final state + summary.

Event schema (SSE):

```
event: phase.start     data: { phase: "syncCovers", label: "Sync covers" }
event: phase.progress  data: { phase: "syncCovers", current: 18, total: 47 }
event: phase.log       data: { phase: "syncCovers", line: "..." }
event: phase.end       data: { phase: "syncCovers", status: "ok"|"failed", durationMs, summary: "47 of 47" }
event: task.end        data: { status: "ok"|"failed"|"partial", summary: {...} }
```

## Phase 1 scope (this branch)

Deliverables:

1. Task layer skeleton: `Task`, `TaskSpec`, `TaskIO`, `TaskRunner`, `CommandInvoker`, `TaskRegistry`.
2. `SyncVolumeTask` implemented, unit-tested with a fake `CommandInvoker`.
3. `CleanOrphanCoversTask` implemented, unit-tested.
4. HTTP endpoints above (`UtilitiesRoutes`).
5. Volumes screen:
   - Tile added to Tools landing, distinct color + icon.
   - Two-pane layout, left list with live health badges.
   - Right pane detail + operations (Sync always, Clean orphan covers when applicable).
   - Visualization → run → summary lifecycle.
   - Sticky selection.
6. Playwright smoke test for Volumes landing + mock Sync run.

Out of scope for Phase 1:
- Retry of failed phases.
- Task cancellation.
- Aggregate status across all volumes (cross-volume).
- Other secondary actions (thumbnails, probe investigation, timestamp fix) — they need their own tasks and will be added in follow-up PRs on this branch or new ones.

## Open questions

- **Health endpoint cost.** Computing "orphan covers" and "missing thumbnails" per volume on every 30s poll may be expensive. Likely need cached counts updated on sync completion and a "scan health" action that recomputes on demand. Revisit when implementing `/utilities/state/volumes`.
- **Display name source.** `Volume.id` is a short code ("a", "bg"). Is there a friendlier name configured elsewhere (config yaml), or do we derive from path? Confirm before building the left-pane row.
- **Structure-type variance.** Some volumes have partitions, some don't. Does Sync behave differently per structure type? Probably yes — `SyncVolumeTask` may need to consult structure type to decide which sub-commands to run. Check `SyncCommand` for current logic before wiring the task.
