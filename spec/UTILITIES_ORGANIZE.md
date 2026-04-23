# Utilities — Organize Queue

> **Status: DRAFT** (2026-04-23)
>
> Secondary action on the Volumes screen. Feature branch: `utilities-organize`.

## Purpose

The organize pipeline (Phases 1–4) has full CLI and MCP surfaces but no Utilities
UI. This spec adds it as a secondary action on the Volumes screen — the natural
complement to Sync.

After a volume is synced, new titles land in the `queue` partition as raw folders.
Organize cleans them up (normalize), arranges their contents (restructure), files
them under the correct actress tier folder (sort), and promotes actresses who've
crossed tier thresholds (classify). Each phase is available as an individual action,
plus an "Organize all" action that runs all four in sequence.

## Scope

**Phase 1 (this branch):**
- Queue count on volume card (how many titles are waiting)
- Five individual actions: Normalize, Restructure, Sort, Classify, Organize all
- Each action has a plan step (dry-run) followed by an execute step
- Plan step shows per-title plan before any FS writes
- Execute step: SSE progress + per-title outcome list + summary

**Possible future consolidation:**
- "Prepare" action combining Normalize + Restructure into a single task

**Out of scope / Phase 2:**
- Per-title detail: inspect a specific title's folder before/after
- Partial re-run: resume an interrupted organize
- Paginated execution for very large queues (CLI `limit`/`offset` arg is there;
  UI always runs the whole queue)
- Attention inbox viewer (see the titles routed to `attention/`)

## Eligibility

Only volumes that actually have titles in a `queue` partition are relevant. The
Volumes screen detects this via a count from the DB (`title_locations` rows with
`partition_id = 'queue'` for the volume). Structure types with a queue: conventional,
sort_pool, and queue. Collections, exhibition, and avstars never have queue content.

The "Organize queue" section is hidden for volumes with zero queue titles.

## Actions

Five actions are exposed, each independent:

| Action | Phases | Preview task ID | Execute task ID |
|---|---|---|---|
| Normalize | NORMALIZE | `organize.normalize.preview` | `organize.normalize` |
| Restructure | RESTRUCTURE | `organize.restructure.preview` | `organize.restructure` |
| Sort | SORT | `organize.sort.preview` | `organize.sort` |
| Classify | CLASSIFY | `organize.classify.preview` | `organize.classify` |
| Organize all | NORMALIZE + RESTRUCTURE + SORT + CLASSIFY | `organize.preview` | `organize.queue` |

Each action goes through the same two-step flow: Preview plan → Execute.

## Layout (right pane addition)

The section appears below the existing Sync and Clean Orphaned Covers operations
when queue count > 0:

```
┌── Organize queue ───────────────────────────────────────────┐
│ 23 titles in queue                                          │
│                                                             │
│  [ Normalize ]  [ Restructure ]  [ Sort ]                   │
│  [ Classify ]   [ Organize all ]                            │
└─────────────────────────────────────────────────────────────┘
```

Each button opens its own plan → execute flow inline (replacing the button row):

```
┌── Organize queue ───────────────────────────────────────────┐
│ Sort — Plan (dry run) — 23 titles                           │
│                                                             │
│  ABP-123  sort → stars/minor/Yua Aida/                      │
│  ONED-456 sort → attention/ (actressless)                   │
│  …                                                          │
│                                                             │
│         [ Execute ]         [ Cancel ]                      │
└─────────────────────────────────────────────────────────────┘
```

After execute completes:

```
┌── Organize queue ───────────────────────────────────────────┐
│ Sort — Done — 0 titles remaining in queue                   │
│                                                             │
│  sort→stars:   20 filed                                     │
│  sort→attn:     3 routed to attention                       │
│  errors:        0                                           │
│                                                             │
│         [ Run again ]       [ Back ]                        │
└─────────────────────────────────────────────────────────────┘
```

"Back" returns to the action button row. "Run again" re-runs the preview step.

## Plan detail rendering

Each title in the plan shows its code + path, then one line per planned action:
- Normalize: `cover-rename: {from} → {to}` / `video-rename: {from} → {to}` / skipped (greyed)
- Restructure: `move: {file} → {subfolder}/{file}` / skipped (greyed)
- Sort: `sort → stars/{tier}/{actress}/` or `attention/ ({reason})`
- Classify: `promote: {actress} from {old} → {new}`

Errors from the preview surface inline next to the title in red.

Titles with all-skip / already-canonical actions are collapsed by default (shown
as greyed-out, click to expand). Titles with substantive actions are expanded.

## Two-step task design

Preview and execute are two separate task runs, both using the same task lock:

- Preview: `dryRun = true`, emits plan events, returns full outcome list in `TaskEnded.summary`
- Execute: `dryRun = false`, emits progress events, returns summary counts in `TaskEnded.summary`

Both accept:
- `volumeId` (required, VOLUME_ID type)

Both tasks:
1. Mount the volume (same as SyncVolumeTask pattern)
2. Call `OrganizeVolumeService.organize(...)` with the appropriate `dryRun` flag and phase set
3. Emit per-title progress events (PhaseProgress)
4. Unmount the volume
5. Emit TaskEnded with the `OrganizeVolumeService.Result` serialized as the summary

## Progress events

- `PhaseStarted` with label `"Scanning queue"` — one event at the top
- `PhaseProgress(current, total)` — emitted after each title is processed
- `PhaseStarted` with label `"Classifying actresses"` — when transitioning to Phase 4 (Organize all only)
- `TaskEnded(status, summary)` — terminal event; summary contains the full
  `OrganizeVolumeService.Result` serialized as JSON

The full per-title outcome list is embedded in `TaskEnded.summary` so the UI can
render the plan/result table without a separate endpoint.

## Backend

New package `com.organizer3.utilities.task.organize`:

```
OrganizeNormalizeTask.java      (task id: "organize.normalize")
OrganizeNormalizePreviewTask.java (task id: "organize.normalize.preview")
OrganizeRestructureTask.java    (task id: "organize.restructure")
OrganizeRestructurePreviewTask.java (task id: "organize.restructure.preview")
OrganizeSortTask.java           (task id: "organize.sort")
OrganizeSortPreviewTask.java    (task id: "organize.sort.preview")
OrganizeClassifyTask.java       (task id: "organize.classify")
OrganizeClassifyPreviewTask.java (task id: "organize.classify.preview")
OrganizeAllTask.java            (task id: "organize.queue")
OrganizeAllPreviewTask.java     (task id: "organize.preview")
```

All tasks share a common base class `OrganizeBaseTask` that handles:
- Mount/unmount using the SyncVolumeTask pattern
- Calling `OrganizeVolumeService.organize()` with the task's phase set and dryRun flag
- Serializing the result to `TaskEnded.summary`

Each concrete class only specifies its phase set and dryRun flag.

Helper for session-to-filesystem:
```java
VolumeFileSystem fs = invoker.session().getActiveConnection().fileSystem();
```

`AttentionRouter` is constructed inside the task (same as in `OrganizeVolumeCommand`):
```java
new AttentionRouter(fs, volumeId, Clock.systemUTC())
```

`OrganizerConfig` and `OrganizeVolumeService` are injected into the task constructor.
`Jdbi` is also injected (needed by `OrganizeVolumeService.organize()`).

Registration in `Application.java`: all 10 task instances added to TaskRegistry.

## Frontend

Changes to `utilities-volumes.js`:

1. **Volume state** — the existing state fetch (`/api/utilities/volumes/{id}/state`)
   is extended to include `queueCount: N`.

2. **Organize section** — rendered below the existing operations when
   `state.queueCount > 0`. Shows 5 action buttons. Clicking any button enters
   the plan → execute flow for that action inline, replacing the button row.

3. **Per-action flow state** — each action tracks: idle → planning → plan-ready →
   executing → done. "Back" resets to idle (button row). "Run again" re-runs
   preview.

4. **CSS class prefix** — `org-` (distinct from `vl-` volumes, `lh-` library health).

## Volume state endpoint extension

`GET /api/utilities/volumes/{id}/state` adds field:
```json
{ "queueCount": 23 }
```

Populated by counting `title_locations` rows where `volume_id = ? AND partition_id = 'queue'`.
Zero for volumes without queue content.

## Testing

- One test class per task pair (e.g. `OrganizeSortTaskTest`): stub `OrganizeVolumeService`,
  assert correct phase set passed, dryRun flag correct, PhaseProgress events emitted,
  TaskEnded summary contains result JSON.
- Route test: `GET /api/utilities/volumes/{id}/state` includes `queueCount`.
- Frontend: visual smoke via Playwright (existing pattern).

## Open questions

- **Q1:** Should the plan list auto-scroll as titles arrive (like a live tail), or
  only render once the preview task completes? Inclined toward: render on completion
  (simpler, and preview is fast — typically <1s per title).
- **Q2:** Should "Organize all" show a combined plan (all 4 phases per title), or
  phase-by-phase sections? Combined per-title view is more useful.
