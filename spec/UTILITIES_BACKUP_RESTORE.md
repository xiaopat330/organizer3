# Utilities — Backup & Restore Screen

> **Status: DRAFT** (2026-04-22)
>
> Third Utilities screen. Companion to `PROPOSAL_UTILITIES.md` and the Volumes / Actress Data specs.
> Feature branch: `utilities-backup-restore`.

## Purpose

Move user-data backup and restore out of the CLI. These are the two most consequential
operational actions in the app — losing favorites / grades / watch history from a botched
restore would be painful — and deserve a visible, previewable UI.

## Scope

**Phase 1 (this branch):**
- List all backup snapshots on disk, each with timestamp + file size
- Back up now → runs the existing export/write pipeline as a task
- Per-snapshot detail pane with counts (actresses, titles, watch history)
- Restore with **visualize-then-confirm** — show counts and per-category preview before write

**Explicitly out of Phase 1:**
- Auto-backup schedule toggle in the UI (keep CLI + config for now; a chip like bg-thumbnails can come later)
- Partial restore (pick categories / subset of entries)
- Restore from an arbitrary uploaded file — only DB-known snapshot paths
- Configuring snapshot retention from the UI

## Identity

- **Color:** muted teal (`#2dd4bf` / `#0f766e`) — distinct from Volumes blue and Actress Data magenta.
  The Aliases tile used this teal before the fold-in; now it's free.
- **Icon:** simple archive / clock-arrow (time-history). Evokes "saved points in time".
- **Tile label:** "Backup".

## Layout

Two-pane target + operations.

```
┌────────────────────────────────────────────────────────────┐
│  Backup                       [ Back up now ]              │
├────────────────────┬───────────────────────────────────────┤
│ 📦 2026-04-22 02:10 │                                       │
│ 📦 2026-04-21 04:06 │    Right pane: per-snapshot detail   │
│ 📦 2026-04-19 22:01 │    + Restore button + visualize      │
│ 📦 2026-04-18 21:15 │                                       │
│ …                   │                                       │
└────────────────────┴───────────────────────────────────────┘
```

- Left pane: snapshots newest-first. Each row: timestamp (relative + absolute), file size, `(latest)` badge on the first.
- Header right: **Back up now** button.
- Right pane: empty state → detail → visualize → run → summary, same spine as other screens.

## Left pane: snapshots list

Each row:
- Relative timestamp (e.g. "2 hours ago") with absolute date below in muted monospace
- File size (kB)
- `(latest)` marker on the newest row
- Sticky selection via `localStorage` (standard pattern)

If there are no snapshots, the list shows a neutral empty state with a hint pointing to the **Back up now** button.

## Right pane: modes

### Detail (snapshot selected)

- Header: absolute timestamp, file size, path (muted)
- Stats row: actresses / titles / watch-history / AV actresses / AV videos — counts read from the file on demand
- **Operations**:
  - **[ Restore this snapshot ]** — primary button, opens the visualize pane

### Visualize (restore preview)

Runs a server-side dry-run through a new `UserDataBackupService.previewRestore(path)` method that
reads the file and compares each entry against current DB state. Produces a `RestorePreview` with
per-category counts + a list of "would change" items for the largest categories (likely actresses
and titles). Visualize pane renders:

- Top badges: "Would update 12 actresses · 47 titles · 128 watch entries · skip 3 missing codes"
- Collapsible per-category list with old → new deltas for fields that differ
- `Proceed — restore` and `Cancel` buttons (primary-on-the-left, per convention)

### Run / summary

Single-phase task, reuses the existing run pane pattern — phase progress + summary line with
counts returned by `RestoreResult`.

### Back up now

No visualize step (not destructive — it creates a new file). Button goes straight to the run
pane; on completion refreshes the left-pane snapshot list so the new snapshot appears at the top.

## Tasks

### BackupNowTask

| Field      | Value                    |
|------------|--------------------------|
| `id`       | `backup.run_now`         |
| `inputs`   | none                     |
| `visualize`| no                       |
| `phases`   | `export`                 |

Wraps `UserDataBackupService.exportAndWriteSnapshot(...)`. Single phase; summary line is
"N actresses, M titles, K watch entries → /path/to/file.json".

### RestoreSnapshotTask

| Field      | Value                    |
|------------|--------------------------|
| `id`       | `backup.restore`         |
| `inputs`   | `snapshotPath: String`   |
| `visualize`| yes (preview)            |
| `phases`   | `restore`                |

Wraps `UserDataBackupService.restore(...)`. Summary line pulls from `RestoreResult`.

Both tasks honour the atomic task lock (server-side).

## HTTP surface

- `GET  /api/utilities/backup/snapshots` — array of `{ path, name, sizeBytes, timestamp, latest }`.
- `GET  /api/utilities/backup/snapshots/{name}` — detail including counts read from the file.
- `POST /api/utilities/tasks/backup.restore/preview` — body `{ snapshotPath }` → restore preview DTO.
- Task run via existing `POST /api/utilities/tasks/{id}/run` infra.

Snapshot paths are looked up by filename only (no directory traversal) — the server resolves the
filename to the configured backups directory.

## Backend notes

- Extend `UserDataBackupService` with `previewRestore(Path)` → `RestorePreview`: parse the file,
  iterate entries, compare with DB state, emit per-category counts + sampled change details.
  No DB writes.
- Add `snapshotDetail(Path)` helper for the per-snapshot stats (counts only; doesn't need full diff).
- Reuse existing `findSnapshots` for the list endpoint.
- Snapshot paths leaving the server must be relative to the backups root — never absolute paths.
  The run endpoint validates the incoming `snapshotPath` against the configured root before calling
  `restore(...)`.

## Open questions (Phase 1)

- **Restore preview depth.** Do we show *every* differing row, or just counts + a few samples?
  Probably just samples + counts initially; large-volume detail is a follow-up.
- **Delete snapshot** — do we want a trash can button on each row? Useful, but destructive.
  Defer unless a real need surfaces.
- **Export to a user-chosen path** — CLI path-arg supports this; UI doesn't. Defer.
