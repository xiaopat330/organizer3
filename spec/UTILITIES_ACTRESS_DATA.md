# Utilities — Actress Data Screen

> **Status: DRAFT** (2026-04-22)
>
> Screen-specific spec for the second Utilities screen. Companion to `PROPOSAL_UTILITIES.md` and modeled on `UTILITIES_VOLUMES.md`.
> Feature branch: `utilities-actress-data`.

## Purpose

Bring actress YAML maintenance out of the CLI. Today, every enrichment update requires `load actress <slug>` or `load actresses` at the shell — friction the user feels every time research produces a new YAML. This screen surfaces the canonical YAMLs already on the classpath, shows their state, and lets the user load them individually or in bulk, streaming progress like any other utility task.

## Scope

**In Phase 1 (this branch):**
- List all actress YAMLs under `src/main/resources/actresses/`
- Per-actress detail pane: canonical name, key profile fields, portfolio size, DB presence indicator
- **Load** action: runs `ActressYamlLoader.loadOne(slug)` inside a task
- **Load all** action: runs `ActressYamlLoader.loadAll()` inside a task, per-slug progress

**Explicitly deferred to later branches:**
- Field-level diff preview (requires a loader refactor to separate plan / apply)
- Classification queue (unresolved actresses workflow)
- Aliases & merges (the folded-in Aliases tile)
- YAML editing in the browser
- Reconciling the `reference/actresses/` research workspace with `src/main/resources/actresses/`

The Phase 1 value prop is narrow and concrete: **no more CLI for the common case of loading new / updated YAMLs.**

## Identity

- **Color:** muted magenta. Distinct from Volumes (blue), AV tools, and existing tool tile palette.
- **Icon:** simple two-person silhouette (outline, no fill), mirroring the Aliases tile's visual direction but recognizably different.
- **Tile label:** "Actress data".

## Layout

Two-pane target + operations (per `PROPOSAL_UTILITIES.md` convention).

```
┌────────────────────────────────────────────────────────────┐
│  Actress data                         [ Load all ]         │
├────────────────────┬───────────────────────────────────────┤
│ 🔸 Asuka Kirara   │                                        │
│ 🔸 Nana Ogura  ✓  │   Right pane: detail / run / summary   │
│ 🔸 Sora Aoi    ✓  │                                        │
│ 🔸 Yua Aida    ✓  │   (dynamic per selection)              │
│ …                 │                                        │
└────────────────────┴───────────────────────────────────────┘
```

- **Left:** alphabetical list, `canonicalName` (from YAML `profile.name.stage_name`), slug in muted text below, small `✓` if the canonical name already exists in the `actresses` table (loaded at least once), no mark otherwise.
- **Left proportions:** ~30% (same as Volumes).
- **Top right of header:** a global **Load all** button.
- **Right pane:** empty state, detail, run, summary — the same lifecycle spine Volumes uses. No visualize mode in Phase 1 (Load is idempotent and non-destructive enough to go straight to run).

## Left pane: actress YAML list

Each row:
- Canonical name (if YAML has `profile.name.stage_name`) or falls back to slug
- Slug in muted monospace below
- Small DB indicator: `✓` (loaded: actress row exists under canonical name) or nothing (not yet loaded)
- Row selection sticky via `localStorage` (same pattern as Volumes)

Empty list: this should never happen in practice, but renders a "No actress YAMLs found on classpath" message. Helpful while diagnosing a broken packaging config.

No badges / counts beyond the `✓` indicator in Phase 1 — real "is the DB in sync with the YAML" is a follow-up that depends on the plan/apply split.

## Right pane: modes

### Empty state

"Pick an actress on the left to manage it. Use **Load all** to process every YAML in one shot."

### Detail (selected actress, no run in flight)

- **Header:** canonical name (large), slug (muted monospace)
- **Profile summary:** date of birth (if present), height, active years, primary studio names as a short chip list
- **Portfolio summary:** `47 portfolio entries · grades: S: 3, A: 12, B: 18, …` — whatever counts are cheap to compute from the YAML
- **Status:** `Loaded · last update unknown` or `Not loaded yet` — based on whether the canonical name exists in the DB. No last-applied timestamp in Phase 1.
- **Operations:**
  - **[ Load ]** — primary, runs `actress.load_one`

Disabled during any running task (atomic lock applies).

### Run / summary

Existing Volumes run pane already handles this — reuse the `volumes-run` DOM and `task-center` pill as-is, just with per-task labels ("Loading actress YAMLs", "Loading Asuka Kirara").

Final summary pulls from the task's phase summary lines: `3 titles created · 12 enriched · 2 unresolved codes`, matching the existing `LoadResult` fields.

## Tasks

### LoadActressTask

| Field | Value |
|-------|-------|
| `id` | `actress.load_one` |
| `title` | "Load actress YAML" |
| `inputs` | `slug: String` |
| `visualize?` | No |
| `phases` | `load` |

Single phase wraps `ActressYamlLoader.loadOne(slug)` and emits the returned `LoadResult` fields as the phase summary. Throws surface as phase failure.

### LoadAllActressesTask

| Field | Value |
|-------|-------|
| `id` | `actress.load_all` |
| `title` | "Load all actress YAMLs" |
| `inputs` | — |
| `visualize?` | No |
| `phases` | `load_all` |

Single phase iterates every slug discovered by `ActressYamlLoader.discoverSlugs`, emits `phaseProgress` per slug (`12 / 43 · processing sora_aoi`), accumulates totals. Per-slug failures log and are counted in the summary but don't halt the run.

Both tasks honor the atomic lock (server-side) — they can't race each other or any volume task.

## HTTP surface (this screen's slice)

- `GET  /api/utilities/actress-yamls` — array of `{ slug, canonicalName, loaded: bool, portfolioSize, profileSummary: {...} }`.
- `GET  /api/utilities/actress-yamls/{slug}` — detail for one YAML (same shape, single element — used for right pane on click).
- `POST /api/utilities/tasks/actress.load_one/run` — body `{ slug }`.
- `POST /api/utilities/tasks/actress.load_all/run` — body empty.
- SSE + polling endpoints from the existing task infra — no new endpoints needed.

## Backend: refactor considerations

To keep Phase 1 small:
- Promote `ActressYamlLoader.discoverSlugs(URL)` to public (or wrap in a small service) so we can enumerate without calling `loadAll`.
- Build a tiny `ActressYamlCatalog` service that reads each YAML header once at startup (or on demand with a short-lived cache), yielding the list DTOs. Parsing all 43 YAMLs is cheap — sub-second.
- Add a `ActressRepository.existsByCanonicalName(String)` probe for the `loaded` flag, if not already present.

No changes to `ActressYamlLoader`'s load path itself. Tests for the new catalog service use real YAMLs from the existing test resources.

## Frontend module shape

- New `modules/utilities-actress-data.js` following `utilities-volumes.js`'s structure: `showView`, `hideView`, state pickup for reruns, task-center integration.
- Reuses `task-center.js` wholesale.
- Reuses the existing run pane (`#volumes-run` could be renamed to `#utilities-run` or duplicated for independence; decide during implementation).

## Out of Phase 1 scope (flagged explicitly)

- Field-level diff preview and visualize mode — requires splitting `ActressYamlLoader` into `plan()` and `apply()`. Worthwhile but a meaningful refactor; punt to a follow-up PR on this branch after MVP lands.
- Classification queue (unresolved actresses, Haiku-assisted resolution, etc.).
- Aliases & merges.
- Sync between `reference/actresses/` and `src/main/resources/actresses/`.
- YAML editor in browser.

## Open questions

- **Where the run pane lives** — the existing `#volumes-run` in `index.html` is Volumes-specific but does exactly what we need. Rename to `#utilities-run` and share, or duplicate per screen? Duplicate for now, revisit when a third screen forces the decision.
- **"Outdated" detection** — once plan/apply exists, we can mark a YAML as "changed since last load" (hash mismatch, field delta, etc.). Defer.
- **`loadAll` error accumulation** — the existing loader logs individual failures and continues. Should the task report "7 succeeded, 2 failed" in the summary? Yes — accumulate in the phase summary. Implementation detail for the task.
