# Proposal: Utilities

> **Status: DRAFT** (2026-04-21)
>
> Design doc for the Utilities section of the web UI. The rest of the app is built for content consumption; Utilities is the maintenance and data-update surface. It is expected to be used occasionally, not daily.

## Motivation

Today, maintenance happens through the JLine3 shell or the web terminal. Both expose the same ~40 commands. A routine task like "sync volume A" requires the user to know the sequence:

```
mount a
sync all
sync covers
unmount a
```

(`rebuild` collapses some of these but not all, and the user still has to know it exists.)

This is a CLI-shaped interface projected onto the browser. The web terminal already does that job well enough for power users. A structured UI that mirrors the same commands as forms adds no value — it's a worse terminal.

What *would* add value is a UI oriented around **what the user wants to accomplish**, where the backend hides command sequencing, mount plumbing, argument fiddling, and error recovery. That is what Utilities is.

**Corollary:** if a Utilities screen is a form whose fields map 1:1 to a CLI command, it should not exist. Use the terminal.

## Scope

Utilities is the single surface for all maintenance and data-update work:

- Volume sync and cleanup
- Library-wide health and integrity
- Actress data loading, classification, alias management
- Backup / restore
- Operational tooling: queue, logs

It is not a consumption surface. Browsing, searching, and playback remain elsewhere.

## Principles

1. **Goals, not commands.** Screens are organized around user goals. Commands are implementation detail.
2. **Plumbing is invisible.** Mount/unmount, command dispatch order, dry-run toggles, retry logic — none of it surfaces unless it genuinely matters to the user.
3. **Surface state, then action.** Show the user what the system sees (last sync, orphan counts, pending YAML changes) before offering buttons. Actions read as consequences of visible problems.
4. **One way to do a thing.** If a workflow composes multiple commands, Utilities offers the composition, not the parts. The parts remain in the terminal for escape-hatch use.
5. **MCP parity.** Every Utilities workflow is also an MCP tool. The UI and MCP are peer clients of the same task layer; neither is a translation of the other.

## Screens

Each is a tile on the existing Tools landing page. Each screen has a **distinct color** and a **simple representative icon** so the landing page reads at a glance and users build fast visual recall. Colors and icons are fixed per screen — picked during each screen's build-out and never reshuffled.

**Development approach.** Utilities is the largest feature surface in the app — broader in scope than anything shipped so far. Each screen is spec'd and built as its own feature branch. Phase 1 (Volumes) establishes the task-layer foundation; subsequent screens are independent branches that build on it.

### 1. Volumes

**Layout:** one card per configured volume.

**Card shows (live):**
- Display name + identifier
- Total size, title count, last-synced timestamp
- Health indicators: orphan covers, missing thumbnails, recent probe errors, timestamp drift
- Mount state is *not* shown. If the user's action needs the volume mounted, the task mounts it.

**Primary action:** **Sync**. One click runs mount → sync all → sync covers → unmount (equivalent of today's `rebuild` + covers, wrapped in mount lifecycle). Streaming output panel shows progress; summary shows what changed.

**Secondary actions** appear conditionally based on health signals:
- "12 orphan covers → Clean up" (runs `prune-covers` scoped to this volume)
- "Timestamp drift detected → Reconcile" (runs `audit-timestamps` / `fix-title-timestamps`)
- "Rebuild index" (destructive; confirms)

The user never types a volume identifier, never mounts, never unmounts.

### 2. Library Health

**Scope:** collection-wide, not per-volume. Cross-volume audits live here.

**Layout:** a list of problem categories with live counts:
- Duplicate titles across volumes
- Orphan covers (covers referring to missing titles)
- Missing / stale thumbnails
- Probe failures (unreadable videos)
- Alias conflicts (multiple canonical names collide)
- Unresolved actress references

Each row: count, brief description, a **Fix** or **Investigate** action. Fix runs the orchestrated task; Investigate opens a detail view (list of affected items, optional per-item actions).

This replaces the current Duplicates tile and absorbs the cross-volume use cases of today's audit/scan/prune commands.

### 3. Actress Data

**Purpose:** loading new YAML content, reclassifying, and managing alias/merge state. Workflow-oriented.

**Panels:**
- **Pending changes** — diff of YAML files under `reference/actresses/` vs what's in the DB. Shows adds / updates / removes. One-click "Apply all" or per-actress apply.
- **Classification queue** — actresses with unresolved state; batched run with progress.
- **Aliases & merges** — the current Aliases tile, expanded. Propose merges, see pending rename effects, commit.

This folds in today's Aliases tile. Commands like `load actress`, `load actresses`, `classify actress`, `export aliases` are implementation.

### 4. Backup & Restore

**Shows:** last backup timestamp, size, location. List of available snapshots.

**Actions:** **Back up now** (one click). **Restore** (pick a snapshot, confirm twice, shows what would change before committing).

### 5. Queue

Unchanged. Already workflow-oriented.

### 6. Logs

Unchanged. Already a usability tool.

### Retired / folded

- **Duplicates tile** — becomes a row in Library Health.
- **Aliases tile** — folds into Actress Data.

## Screen layout convention (Volumes, and template for others)

All Utilities screens follow a **two-pane target + operations** layout:

- **Left pane — target picker.** For Volumes: the list of volumes, each row showing name, size/count, last-synced, and a compact health badge. Selection is sticky across page reloads. Mount state is never surfaced; offline/online is.
- **Right pane — operations stage.** Dynamic per selection. When nothing is selected: a welcome/empty state. When a target is selected: volume detail at top (enumerated health problems), operations below.

**Operations list** is **flat** in Phase 1 — one primary action (Sync), then secondary actions surfaced from health indicators ("3 orphan covers → Clean up"). Revisit categorization when the list grows past ~5–6 items with real examples in hand.

**Right-pane lifecycle** for any operation: *detail → visualize → run → summary → back to detail*. Visualizations and progress render inline in the right pane, not in modals. This spine is the standard for every future operation on every Utilities screen.

## Execution model

### Task layer

A new package `com.organizer3.utilities.task`. Each user-facing workflow is a `Task`:

```java
public interface Task {
    String id();                          // stable identifier (e.g. "volume.sync")
    String title();                       // human label
    TaskSpec spec();                      // declared inputs (volume id, options)
    void run(TaskInputs in, TaskIO io);   // orchestration body
}
```

`TaskIO` is the task-layer analog of `CommandIO`: it accepts structured progress events (phase start, phase complete, item counts, log lines, final summary) rather than terminal ANSI. A `CommandInvoker` lets a task call existing commands without knowing about `CommandIO` — it adapts `TaskIO` into a `PlainCommandIO`-equivalent and captures output.

This means:
- No command code changes. Tasks compose the existing commands.
- Tasks are independently unit-testable with a fake `CommandInvoker`.
- The Tasks layer owns orchestration concerns: mount lifecycle, error recovery, aggregate progress, structured summaries.

### HTTP surface

- `GET  /utilities/tasks` — list task ids + specs (for UI discovery and MCP)
- `GET  /utilities/state/volumes` — volume cards payload (sizes, counts, health)
- `GET  /utilities/state/library` — library health payload
- `GET  /utilities/state/actresses` — pending YAML diff, classification queue, alias state
- `POST /utilities/tasks/{id}/run` — start a task; returns run id
- `GET  /utilities/runs/{run_id}/stream` — SSE stream of progress events
- `GET  /utilities/runs/{run_id}` — terminal state + summary

SSE (not WebSocket) because progress is one-way and resumable after a page reload.

### MCP parity

Each task registers as an MCP tool with the same spec. MCP tool invocation calls the same `Task.run(...)` the HTTP endpoint does, with a `TaskIO` that buffers events into a structured response.

## Walking skeleton

Phase 1 — **Volumes as proof of concept**
1. Task layer: `Task`, `TaskSpec`, `TaskIO`, `TaskRunner`, `CommandInvoker`.
2. `SyncVolumeTask` — composes mount → sync all → sync covers → unmount. Unit tested with fake invoker.
3. `/utilities/state/volumes` and task run / stream endpoints.
4. Volumes screen: card list, Sync button, streaming output panel, one secondary action (`CleanOrphanCoversTask`).

Phase 2 — **Library Health (read-only first)**
1. Aggregate state endpoint; present counts only. No Fix actions yet.
2. Add Fix actions per row as the underlying tasks are written.

Phase 3 — **Actress Data**
1. Pending-changes diff view + Apply task.
2. Move Aliases tile contents into Actress Data; extend with merge workflow.
3. Classification queue task.

Phase 4 — **Backup & Restore**
1. Backup task + state endpoint.
2. Restore task with preview + double confirmation.

Phase 5 — **MCP surface**
1. Expose registered tasks as MCP tools.
2. Deprecate the per-command MCP tools that are now covered by tasks.

## Confirmation model: visualize, don't dry-run

There is no dry-run mode. Instead, any task with user-visible side effects (renames, moves, deletes, DB writes that change observable state) renders a **visualization** of its intended outcome before execution.

Shape:
- Folder rename: `old/folder/path → new/folder/path`
- Cover prune: list of files to delete, with previews
- Actress merge: before/after canonical name and aliases
- Restore: diff of what the snapshot would change

The user reads the visualization and either **Proceeds** or **Cancels**. This replaces dry-run entirely; it's clearer (no second run), avoids duplicated execution paths, and matches the principle of surfacing state before action.

Tasks that have no user-visible side effects beyond the obvious one (e.g., Sync) skip the visualization step.

## Open questions

- **Destructive confirmation pattern.** Rebuild, restore — the visualization handles most of this, but truly irreversible operations (restore-over-current) may want additional friction (typed name?). Defer until Phase 1 is shippable.
- **Task cancellation.** Long-running tasks (full sync) should be cancellable. Requires cooperation from underlying commands, most of which don't check interruption today. Defer; document which tasks are cancellable.
- **Partial-failure reporting.** Sync-volume runs 4 commands; if covers fail but titles succeed, the summary must make that legible. `TaskIO` events should support per-phase status. Scope deferred — revisit during Phase 1 implementation.

### Resolved

- **Dry-run.** No dry-run mode; replaced by visualization + confirm (see above).
- **Concurrency.** Single-user app, sole user. Not a concern. No locking or rejection logic needed.

## Non-goals

- Replacing the web terminal. Power users and escape-hatch debugging still use it.
- Removing CLI commands. They remain the building blocks; Utilities composes them.
- Real-time multi-user coordination. Single user, no auth.
