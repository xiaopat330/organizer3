# Utilities — Trash Screen

> **Status: IMPLEMENTED**
>
> Concrete implementation proposal for a centralized trash-management utility. Extends `spec/PROPOSAL_TRASH.md` — see §2 for the spec amendments.

---

## 1. Purpose

Expose the end-of-life half of the trash lifecycle. Today the `Trash` primitive moves items into `_trash/` with a JSON sidecar and stops there — items accumulate indefinitely. This screen adds:

1. **Schedule for Deletion** — mark trashed items for permanent async deletion after a minimum 10-day holding period.
2. **Restore** — move a trashed item back to its `originalPath`; DB is not touched (item becomes orphaned until the next volume sync).
3. **Browse** — paginated per-volume view of `_trash/` contents driven by sidecar metadata.

The screen is deliberately outside Volumes: trash management is intentional, centralized, and not folded into any routine operations surface.

---

## 2. Spec Amendments (`PROPOSAL_TRASH.md`)

These revisions land with Phase 1 of this proposal.

### 2.1 Section 7 — Non-Goals (revised)

Replace:

> - The app does not empty the trash
> - The app does not restore from trash (no undelete)

With:

> - The app does not perform immediate permanent deletion; deletion is always scheduled with a minimum 10-day holding period and runs asynchronously.
> - The app does not update the database on restore — restored items become orphaned until the next volume sync rediscovers them.
> - No cross-volume trash or restore (physically impossible with intra-volume move constraint).
> - No quota tracking or trash size limits.

### 2.2 Section 5 — Sidecar schema (v2)

Add one optional field:

| Field | Type | Description |
|---|---|---|
| `scheduledDeletionAt` | string \| absent | ISO 8601 UTC timestamp. Absent = not scheduled. Presence means "delete no earlier than this time." |

**Migration:** nothing to migrate — no production consumers exist. The field is absent on all sidecars written before this change and treated as "not scheduled."

### 2.3 Section 2 — Add principle

> **Scheduled deletion is a floor, not a deadline.** A sidecar's `scheduledDeletionAt` guarantees the item will not be deleted *before* that time. Actual deletion runs as a periodic sweep that catches up on every app start — an offline app does not shorten or skip the holding period.

---

## 3. UI — Trash Screen

### 3.1 Tile identity

- **Location:** Utilities top-level tile. Not a sub-screen of Volumes.
- **Color:** muted red-brown. Distinct from Volumes' blue.
- **Icon:** trash can glyph.
- **Tile label:** "Trash".

### 3.2 Layout

Two-pane target + operations, matching `PROPOSAL_UTILITIES.md` convention.

```
┌────────────────────────────────────────────────────────────────┐
│  Trash                                                         │
├────────────────────┬───────────────────────────────────────────┤
│ [ Volume A    12 ] │  [ Select All ] [ Reset ]                 │
│ [ Volume BG    3 ] │  [ Schedule for Deletion ]  [ Restore ]   │
│ [ Volume HJ    0 ] │  ┌──────────────────────────────────────┐ │
│ [ Volume QP  203 ] │  │ ☐  Original Path    Trashed  Status │ │
│ [ Volume ST    0 ] │  │ ☐  /stars/...      2d ago   —       │ │
│                    │  │ ☐  /queue/...      5d ago   Sched → │ │
│                    │  │                              2026-05-03│ │
│                    │  │          « 1 2 3 »                   │ │
│                    │  └──────────────────────────────────────┘ │
└────────────────────┴───────────────────────────────────────────┘
```

**Proportions:** left ~30%, right ~70%.

### 3.3 Left pane — volume list

Each tile shows the volume display name and a count of items currently in its `_trash/` (sum of `*.json` sidecars). Count comes from the same listing endpoint as the table; stale between sweeps is acceptable.

### 3.4 Right pane — table

Columns:

| Column | Source |
|---|---|
| Select (checkbox) | local UI state |
| Original Path | sidecar `originalPath` |
| Reason | sidecar `reason` (truncated, tooltip on hover) |
| Trashed | sidecar `trashedAt` as relative time |
| Status | `scheduledDeletionAt` → `Scheduled for {date}` / absent → `—` |

**Sort:** sidecar file mtime, descending. Most recent first. `trashedAt` is shown but not used for sorting (see §5.4).

**Pagination:** 50 rows per page, server-side. Page controls at table bottom.

### 3.5 Actions

**Selection helpers** (purely client-side, scoped to the current page):

- **Select All** — checks every row on the current page. Does not reach across pages; navigating to another page and clicking Select All extends the selection to that page too.
- **Reset** — unchecks every row on the current page.

**Operation buttons** act on the current selection and require at least one row checked.

- **Schedule for Deletion** — confirms with a dialog ("Schedule N items for permanent deletion on or after {now+10d}? This cannot be undone once the item is deleted."), then issues a single POST. On success, rows update in place to show the new Status.
- **Restore** — confirms ("Restore N items to their original paths? The database will not be updated; run a volume sync to re-index them."), then issues a single POST. On success, rows disappear from the table.

Items already scheduled for deletion can be **re-scheduled** (resets the 10-day clock) or **restored** (cancels the schedule implicitly by moving the item out of `_trash/`). Both are explicit user actions, not side effects.

---

## 4. Backend

### 4.1 New services

```
com.organizer3.trash
  Trash.java                  (existing)
  Sandbox.java                (existing, unrelated)
  TrashService.java           NEW — list / schedule / restore / sweep
  TrashSidecar.java           NEW — sidecar DTO (read + write)
```

`TrashService` is the single entry point for all trash-management operations. It is volume-scoped — every method takes a `VolumeFileSystem fs` and a volumeId. Wired in `Application.java` alongside the existing primitives; no Spring.

```java
public class TrashService {
    TrashListing list(VolumeFileSystem fs, String volumeId, String trashFolder,
                      int page, int pageSize);
    int scheduleForDeletion(VolumeFileSystem fs, List<Path> sidecarPaths,
                            Instant scheduledAt);
    int restore(VolumeFileSystem fs, List<Path> sidecarPaths);
    SweepReport sweepExpired(VolumeFileSystem fs, String volumeId,
                             String trashFolder, Instant now);
}
```

### 4.2 `TrashSidecar`

Record + read/write helpers:

```java
public record TrashSidecar(
    String originalPath,
    String trashedAt,
    String volumeId,
    String reason,
    String scheduledDeletionAt   // nullable
) {
    public static TrashSidecar read(VolumeFileSystem fs, Path sidecarPath);
    public void write(VolumeFileSystem fs, Path sidecarPath);
    public TrashSidecar withScheduledDeletionAt(Instant t);
}
```

`Trash.java` updates to write v2 sidecars (field simply absent on newly trashed items — scheduling is an explicit later step).

### 4.3 Listing

Implementation:
1. `fs.listDirectory(trashRoot)` recursively. Collect every entry that is not itself a `.json` file — these are candidate trashed items.
2. For each item, check whether a sibling `{name}.json` exists. If missing, **autogenerate** (see §4.3.1). The generated sidecar is written to disk immediately so it participates in sorting and future operations normally.
3. Sort items by sidecar mtime desc.
4. Slice `[page*pageSize, (page+1)*pageSize)`.
5. Read only the sidecars in that slice; return `TrashListing{items, totalCount, page, pageSize}`.

#### 4.3.1 Orphan recovery — autogenerated sidecars

The spec treats sidecar writes as best-effort — if a write failed after the move in `Trash.trashItem`, the item is still in `_trash/` but has no metadata. Prior to this proposal that was tolerable; now it would make the item invisible to listing and unreachable by schedule/restore/sweep.

When an item has no adjacent sidecar, reconstruct one:

| Field | Source |
|---|---|
| `originalPath` | Derived from the item's location inside `_trash/` — the trash mirrors the original directory tree, so stripping the leading `/_trash` yields the original share-relative path. |
| `trashedAt` | Filesystem mtime of the item, formatted as ISO 8601 UTC. |
| `volumeId` | Current volume context — known to `TrashService`. |
| `reason` | Literal `"Autogenerated — sidecar missing"`. |
| `scheduledDeletionAt` | Absent. |

The reconstructed sidecar is written alongside the item exactly as if `Trash.trashItem` had done it originally. Subsequent operations (schedule, restore, sweep) see a normal sidecar with no special handling. The `reason` field carries forward as the audit trail.

**Idempotent:** running the listing twice produces the same sidecar (the second run finds the one written by the first and skips reconstruction).

**Write failure during reconstruction:** log a warning and surface the item in the response with a synthetic in-memory sidecar so the user can still see it. Schedule will fail (can't persist without a writable sidecar); Restore will still work (reads the in-memory sidecar's `originalPath`).

#### 4.3.2 Orphan sidecars — item missing

The inverse of §4.3.1: a `.json` exists in `_trash/` but the file or folder it describes is no longer present. The user (or some external process) must have deleted the item directly from the NAS. The sidecar is now stale.

**Policy:** treat the sidecar as eligible for immediate removal — no 10-day hold, since the item it guarded is already gone. Concretely:

- **Listing** hides orphan sidecars. They do not appear in the table or contribute to volume counts.
- **Sweep** deletes orphan sidecars unconditionally on every run, independent of `scheduledDeletionAt`. After deletion, the prune-empty-ancestors step (§4.8) applies as usual.

Detection is cheap: listing already pairs each sidecar with its item by stripping `.json` from the filename; the pairing check that drives §4.3.1 reconstruction also surfaces the reverse case.

Count is returned alongside items so the left pane's tile badge stays consistent with the table.

**Why mtime and not `trashedAt`:** sorting by `trashedAt` requires reading every sidecar on the volume just to paginate. Mtime comes free with the directory listing and matches `trashedAt` within seconds in practice (mtime is set by the SMB move + sidecar write, both within the same `trashItem` call).

### 4.4 Schedule for Deletion

Runs as a `Task` through `TaskRunner` — serialized against sweeps and any other utility task. The HTTP endpoint enqueues the task and returns its id; the UI watches the task's completion event to refresh the table. Rationale: prevents races between a user clicking Restore/Schedule and a concurrent sweep on the same sidecar.

For each sidecar path in the request:
1. Read sidecar.
2. Set `scheduledDeletionAt = now + 10 days`.
3. Write sidecar (overwrite).

Atomic per-sidecar — a partial batch failure leaves earlier items scheduled and later items not. Response reports successes + failures; UI surfaces failures in a non-blocking toast.

**Re-scheduling is allowed.** If the sidecar already has `scheduledDeletionAt`, the new value replaces it — the clock resets. The UI confirmation dialog calls this out when any selected item is already scheduled.

### 4.5 Restore

Runs as a `Task` through `TaskRunner`, same rationale as §4.4 — serialized against sweeps and other utility tasks to eliminate races on the same sidecar.

For each sidecar path:
1. Read sidecar to get `originalPath`.
2. Compute item path (sidecar path with `.json` stripped).
3. `fs.createDirectories(originalPath.parent)` — the original parent folder may have been deleted since trashing.
4. `fs.move(trashedItemPath, originalPath)`. If `originalPath` already exists, skip and report (a new item with the same path now occupies the slot — restoring would clobber).
5. Delete the sidecar.
6. Prune empty ancestors inside `_trash/` (see §4.8).
7. No DB mutation.

Restore is intentionally loud about the re-sync requirement in the UI and in the API response (`{"restored": N, "needsSync": true}`).

### 4.6 Deletion sweep

Runs as a `Task` — subject to the utilities-atomic constraint. Registered in `Application.java` alongside other tasks.

```
TrashSweepTask (per volume)
  1. For each sidecar under /_trash, read scheduledDeletionAt.
  2. If absent or in the future → skip.
  3. Else: fs.delete(itemPath) recursively, then fs.delete(sidecarPath),
     then prune empty ancestors inside /_trash (see §4.8).
  4. Report {volumeId, deleted, skipped, errors}.
```

**Trigger policy:**
- On app start, once, in the background (`TaskRunner.enqueue` for every configured volume that is mounted).
- On volume mount — enqueue a sweep for that volume immediately. Covers volumes mounted mid-session without waiting up to an hour for the next tick.
- On a scheduled timer — every hour while the app is running. Timer fires a single "sweep all volumes" parent task that enqueues a per-volume sweep task for each mounted volume.
- Manual trigger from the UI (small "Run sweep" button on the screen, same as other utility tasks). Optional for Phase 1 — defer if noisy.

Because it runs through `TaskRunner`, sweeps automatically queue behind any other utility task and never interleave with the user's active work.

**Offline catch-up:** "sweep on every app start" guarantees that a long app downtime doesn't skip a deletion — it just delays it until the app runs again. This is the correct direction of failure (more conservative, not less).

#### 4.6.1 Deletion failures

Deletion can fail mid-sweep — locked file, permission error, transient SMB hiccup, or a recursive delete that partially succeeded on a multi-file folder. Policy:

1. **The item's delete runs before the sidecar's delete.** If the item delete fails, the sidecar delete is skipped. The sidecar stays, still past its `scheduledDeletionAt`, and the next sweep naturally retries. No separate retry logic is needed.
2. **Record the failure on the sidecar** so the UI can surface it. On failure, `TrashService` writes two fields back into the sidecar:

   | Field | Type | Description |
   |---|---|---|
   | `lastDeletionAttempt` | string | ISO 8601 UTC timestamp of the most recent failed attempt |
   | `lastDeletionError` | string | Short error message (truncated to ~200 chars) |

   On the next successful deletion these fields become moot — the whole sidecar is deleted.
3. **UI surfaces the failure** in the Status column: `Deletion failed: {lastDeletionError}` instead of the normal `Scheduled for {date}` text. Gives the user visibility so a persistently stuck item doesn't silently retry forever.
4. **No automatic give-up.** Retries continue indefinitely on every sweep. If the user wants to stop retrying, they Restore the item (which removes the sidecar and cancels the schedule) or fix the underlying filesystem issue.
5. **Partial recursive delete.** If `fs.delete(itemPath)` fails partway through (some children deleted, some not), the item folder is left in its degraded state. The sidecar remains. Next sweep retries; `fs.delete` is expected to handle "some children already gone" gracefully (idempotent leaf-first walk).

The two failure-tracking fields are optional in the sidecar schema — absent means no failures, which is the common case.

### 4.7 HTTP endpoints

Under `/api/utilities/trash/`:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/utilities/trash/volumes` | List of volumes with their trash item counts |
| GET | `/api/utilities/trash/volumes/{id}/items?page=N&pageSize=50` | Paginated items for a volume |
| POST | `/api/utilities/trash/schedule` | Body: `{sidecarPaths: [...]}`; enqueues a `TrashScheduleTask` that sets `scheduledDeletionAt = now+10d`. Returns `{taskId}`. |
| POST | `/api/utilities/trash/restore` | Body: `{sidecarPaths: [...]}`; enqueues a `TrashRestoreTask`. Returns `{taskId}`. |
| POST | `/api/utilities/trash/sweep` | Enqueue a sweep (optional, Phase 2). Returns `{taskId}`. |

All three POSTs enqueue through `TaskRunner`. The UI watches task-completion SSE events to refresh the table.

All paths are sidecar paths (not item paths) — the sidecar is the canonical reference.

### 4.8 Pruning empty ancestors in `_trash/`

Trashed items preserve their original directory hierarchy under `_trash/` — e.g. `/stars/popular/MIDE-1` becomes `/_trash/stars/popular/MIDE-1`. When the item (and its sidecar) are deleted or restored, the intermediate directories (`/_trash/stars/popular`, `/_trash/stars`) can be left behind as empty skeletons. Over time these accumulate and clutter `_trash/`.

After every successful delete (sweep) or restore:

```
parent = directParentOfRemovedItem
while parent is inside /_trash/  AND  parent != /_trash/  AND  fs.listDirectory(parent).isEmpty():
    fs.delete(parent)
    parent = parent.parent
```

- Stops at `/_trash/` itself — the trash root is never removed by this process.
- Stops at the first non-empty directory — siblings of the removed item are preserved.
- Best-effort: if a pruning `fs.delete` fails (e.g. race with another operation), log a warning and continue; the empty directory is harmless and the next operation will retry.

### 4.9 Mount lifecycle

Trash operations require the target volume to be mounted. The screen's volume tiles show only **currently mounted** volumes. Attempting to operate on an unmounted volume is surfaced in the UI as a "not mounted" state on the tile (disabled click). No auto-mount — keeps the mental model aligned with the rest of Utilities.

---

## 5. Decisions and tradeoffs

| Decision | Rationale |
|---|---|
| Sidecar is sole source of truth (no DB table) | Matches existing "volatile, detached" framing; sidecar loss fail-safes to "stays in trash forever." |
| 10 days, fixed | Constant for Phase 1. Configurable later if user asks. |
| Restore does not touch the DB | Forces the user through a sync, which is where authoritative re-discovery already lives. Avoids half-DB writes on failure mid-restore. |
| Sort by mtime, not `trashedAt` | Avoids reading every sidecar to paginate. Equivalent in practice. |
| Sweep as a Task | Cooperates with the atomic utilities rule for free; no separate scheduler to reason about. |
| Schedule and Restore also as Tasks | Serializes user actions against sweeps, eliminating per-sidecar races with no extra locking. |
| Re-scheduling resets the clock | Simplest mental model; avoids "was already X days in, now Y days in" arithmetic. |
| No undo for permanent deletion | Spec — once the sweep deletes, it's gone. The 10-day buffer is the undo window. |

---

## 6. Sandbox Testing

Follows the pattern from `PROPOSAL_SANDBOX_ORGANIZE_TESTS.md`. New tests under `src/test/java/com/organizer3/sandbox/trash/`, tag `sandbox`, run via `./gradlew sandboxTest`.

### 6.1 Test classes

```
src/test/java/com/organizer3/sandbox/trash/
  TrashSandboxTest.java         — Trash.trashItem sidecar v2 shape
  TrashListSandboxTest.java     — listing, sorting, pagination
  TrashScheduleSandboxTest.java — schedule / re-schedule
  TrashRestoreSandboxTest.java  — restore semantics
  TrashSweepSandboxTest.java    — sweep deletion semantics
```

### 6.2 Shared harness

Extends existing `SandboxTestBase`. A new `SandboxTrashBuilder` fluent helper creates trashed items directly inside a per-method `runDir/_trash/`:

```java
class SandboxTrashBuilder {
    SandboxTrashBuilder inTrashRoot(Path trashRoot);
    SandboxTrashBuilder withOriginalPath(String path);
    SandboxTrashBuilder withReason(String reason);
    SandboxTrashBuilder withTrashedAt(Instant t);
    SandboxTrashBuilder withScheduledDeletionAt(Instant t); // null → absent
    SandboxTrashBuilder asFolder();                         // creates a fake folder + sidecar
    SandboxTrashBuilder asFile();                           // default: 1-byte file + sidecar
    Path build(VolumeFileSystem fs) throws IOException;     // returns sidecar path
}
```

The builder writes a 1-byte file (or empty folder) and a hand-written sidecar. This lets tests construct states that wouldn't arise naturally — e.g. pre-scheduled items with `scheduledDeletionAt` in the past to validate the sweep.

**Note:** tests use a per-method `runDir/_trash/` (not the real `/_trash/` at volume root) to keep sandbox isolation. `TrashService` takes the trash folder name as a parameter, so tests pass `"_trash"` and root their paths under `runDir`.

### 6.3 Test cases

#### `TrashSandboxTest` — primitive sidecar v2

| Case | Setup | Assertion |
|---|---|---|
| `sidecarWritesWithoutSchedule` | `Trash.trashItem` of a title folder | sidecar has no `scheduledDeletionAt` key |
| `sidecarIncludesAllRequiredFields` | same | originalPath, trashedAt, volumeId, reason all present |

#### `TrashListSandboxTest`

| Case | Setup | Assertion |
|---|---|---|
| `listsItemsSortedByMtimeDesc` | 3 sidecars written with 10s gaps | returned order matches newest → oldest |
| `pagination` | 120 items, pageSize=50 | page=0 returns 50, page=2 returns 20, totalCount=120 |
| `emptyTrashReturnsZero` | no `_trash/` | totalCount=0, items empty |
| `ignoresNonJsonFiles` | `_trash/foo.txt` alongside `foo.json` | listing shows only sidecar |
| `autogeneratesMissingSidecar` | item at `_trash/stars/foo/MIDE-1` with no `.json` | sidecar created on disk with `originalPath=/stars/foo/MIDE-1`, `reason="Autogenerated — sidecar missing"`, `trashedAt` from mtime |
| `autogenerationIsIdempotent` | same setup, listing called twice | second call does not overwrite the first-written sidecar (mtime unchanged) |
| `orphanSidecarHiddenFromListing` | sidecar at `_trash/a/foo.json` but no `_trash/a/foo` | item not in listing, not counted in totalCount |

#### `TrashScheduleSandboxTest`

| Case | Setup | Assertion |
|---|---|---|
| `schedulesUnscheduledItem` | 1 sidecar, no schedule | `scheduledDeletionAt` set to now+10d (±1s) |
| `reschedulesItemResetsClock` | 1 sidecar scheduled 3d ago | new value = now+10d |
| `partialFailureReported` | 2 sidecars, 1 path invalid | response reports 1 success + 1 failure; valid one is scheduled |

#### `TrashRestoreSandboxTest`

| Case | Setup | Assertion |
|---|---|---|
| `restoreMovesItemToOriginalPath` | item at `/_trash/stars/foo/MIDE-1`, sidecar with `originalPath=/stars/foo/MIDE-1` | item exists at `/stars/foo/MIDE-1` post-restore |
| `restoreCreatesMissingParents` | `/stars/foo/` directory was deleted | parents recreated, item placed |
| `restoreDeletesSidecar` | any restore | sidecar path no longer exists |
| `collisionWithExistingPathSkipped` | `originalPath` already occupied | item stays in trash, response reports skip reason |
| `restoreCancelsScheduledDeletion` | scheduled item | restored item has no sidecar → not eligible for sweep |
| `restorePrunesEmptyAncestors` | item at `_trash/a/b/c/foo` with no siblings | `_trash/a`, `_trash/a/b`, `_trash/a/b/c` all removed; `_trash/` itself preserved |
| `restorePreservesNonEmptyAncestors` | item at `_trash/a/b/c/foo`, sibling at `_trash/a/b/other` | `_trash/a/b/c` removed; `_trash/a/b` preserved (still contains `other`) |

#### `TrashSweepSandboxTest`

| Case | Setup | Assertion |
|---|---|---|
| `deletesItemsPastScheduledTime` | 1 sidecar, `scheduledDeletionAt = now-1d` | item + sidecar gone post-sweep |
| `skipsItemsWithFutureSchedule` | 1 sidecar, `scheduledDeletionAt = now+1d` | still present |
| `skipsItemsWithoutSchedule` | 1 sidecar, no schedule | still present |
| `sweepOnEmptyTrashIsNoop` | no `_trash/` | report: 0 deleted, 0 errors |
| `foldersDeletedRecursively` | scheduled folder with nested files | whole tree gone post-sweep |
| `sweepPrunesEmptyAncestors` | scheduled item at `_trash/a/b/c/foo` with no siblings | `_trash/a`, `_trash/a/b`, `_trash/a/b/c` all removed; `_trash/` preserved |
| `sweepRemovesOrphanSidecar` | sidecar at `_trash/a/foo.json` with no `_trash/a/foo`, no `scheduledDeletionAt` | sidecar deleted on sweep, `_trash/a` pruned if now empty |
| `deletionFailureRecordedOnSidecar` | scheduled item where `fs.delete` throws (simulated via a fault-injecting FS wrapper) | sidecar still present, has `lastDeletionAttempt` + `lastDeletionError` set |
| `nextSweepRetriesAfterFailure` | sidecar from prior failed sweep, now `fs.delete` succeeds | item + sidecar both gone, ancestors pruned |

### 6.4 SMB-differentiating cases

- **Recursive delete of folders over SMB** — `fs.delete` on a folder with children. LocalFS unit tests typically use empty folders; SMB enforces "must be empty" on directory delete, so the service has to walk and delete leaves first.
- **Re-creating parent directories for restore** — tests that `createDirectories` is a no-op for existing segments and safe on SMB.

---

## 7. Execution Plan

### Phase 1 — Spec amendments + sidecar v2 (single PR)

- Update `spec/PROPOSAL_TRASH.md` per §2 above.
- Add `scheduledDeletionAt` field to `Trash.java` sidecar builder (absent by default).
- Add `TrashSidecar` record with read/write.
- Unit tests for `TrashSidecar` round-trip (LocalFS).

Ship on its own. No UI, no new service — just the data model.

### Phase 2 — `TrashService` + sandbox tests

- Implement `TrashService.list / schedule / restore / sweepExpired`.
- Unit tests against `LocalFileSystem` + `@TempDir` for all four methods.
- Sandbox tests per §6.
- No HTTP, no UI yet.

### Phase 3 — HTTP endpoints + web UI

- Endpoints per §4.7.
- Trash tile on Utilities landing.
- Two-pane screen: volume list + paginated table.
- Schedule and Restore action wiring.
- Confirmation dialogs.

### Phase 4 — Sweep task + scheduler

- `TrashSweepTask` wired through `TaskRunner`.
- App-start kickoff.
- Hourly timer.
- Optional: manual "Run sweep" button.

### Phase 5 — Polish (deferred)

- Search / filter on the table.
- Per-volume counts in the Utilities landing tile badge.
- Configurable holding period (if requested).

---

## 8. Non-Goals

- No undo for post-sweep deletion. The holding period *is* the undo window.
- No cross-volume trash or restore.
- No DB-backed index of trash state. Sidecars are the record.
- No automatic sync after restore. User triggers it.
- No per-item holding periods. 10 days, uniform, Phase 1.
