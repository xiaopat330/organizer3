# PROPOSAL: Cover-write confirmation flag + self-healing cover reconciler

**Status:** DRAFT (implementation-ready)
**Branch:** `fix/cover-confirmation`
**Motivation:** During bulk curation on `classic_fresh`, promoted title folders were left with
**missing or zero-byte** cover images (217 repaired manually via `backfill_folder_covers`).
Root cause: the promote-time NAS cover write is *best-effort* (`CoverWriteService.saveToNasBestEffort`)
— it swallows failures, and an SMB stall can throw mid-write leaving a zero-byte stub, or the
async post-commit task can be dropped on queue overflow, with **nothing re-attempting the write**.

## Design principle (from the draft/enrich/promote workflow)

The NAS folder cover is written **only at promote** — the single mutation point. Enrichment /
draft is an *ephemeral helper* (scratch cover only); a draft (and its scratch cover) may be
abandoned at any time, so covers must **never** be written to folders at enrich/populate time.
This proposal adds *reliability* to the existing promote-time write — it does not move the write
earlier. (Confirmed with user 2026-07-10: pre-promote folder covers "not necessary".)

## Why a DB confirmation flag, not a time-window SMB sweep

"Is the folder cover healthy?" is **not derivable from the DB** — checking requires an SMB stat
per title. A recency-windowed sweep would (a) SMB-stat a large recent set every pass and
(b) structurally **miss** any failure whose `curated_at` falls outside the window (app down during
the window, burst past the LIMIT). Instead we **record the pending state at the one place failures
originate** (the post-commit executor) and drive the reconciler off that:

- Set a per-location `cover_pending_since` **pessimistically at promote, in the request thread,
  before the async dispatch**; clear it only when the NAS write **completes without throwing**.
- Reconciler query = live serviceable locations `WHERE cover_pending_since IS NOT NULL`. Push the
  intact local-cache cover, clear on success.

Set-pessimistically-then-clear closes all three real failure modes uniformly — a **dropped** task
(the clear never runs), a **crash** between commit and write, and an outright **failed** write all
leave the row pending, so all three self-heal. Cost is near-zero: the pending set is only in-flight
+ genuinely-stuck rows, never the whole corpus. Symmetry with what already shipped:
`backfill_folder_covers` = one-time historical broad sweep (healed the 217);
this reconciler = go-forward auto-heal.

**Note on the earlier size-verify idea (dropped):** a post-`writeFile` size check only fires if
`writeFile` returns normally but wrote wrong bytes — which a sane SMB client does not do. The
observed `missing`/`zeroByte` failures are all exception/drop paths (write threw mid-transfer → the
call throws → best-effort swallows → stub left; or task dropped). The confirmation flag heals all of
them regardless. We keep `withRetry` (existing) and change `saveToNasBestEffort` to **report
success**; no size-verify is needed.

## Part 1 — Schema: `title_locations.cover_pending_since`

`SchemaUpgrader.applyV72()` (guard `if (version < 72) { applyV72(); setVersion(72); }`):
```java
private void applyV72() {
    log.info("Applying migration v72: cover_pending_since on title_locations (cover-write confirmation)");
    jdbi.useHandle(h -> addColumnIfMissing(h, "title_locations", "cover_pending_since", "TEXT"));
}
```
- Nullable `TEXT` (ISO-8601 µs Z). **NULL = confirmed / not-applicable** (all existing rows,
  including the already-healed 217 → correct: the first reconciler pass must not re-stat history).
- Only the promote path ever sets it non-NULL. Sync-created / library / duplicate locations stay
  NULL (not promotions — no cover to confirm).

## Part 2 — `CoverWriteService.saveToNasBestEffort` returns success

Change the signature `void → boolean`: `true` iff the `withRetry` write completed without throwing;
`false` on final failure (still swallows/logs — never throws). No other behavior change. Update the
one existing caller reference in `DraftPromotionService` (see Part 3) and any tests.

## Part 3 — Promote path (`DraftPromotionService.promote`, ~lines 400–482)

The cover write already runs post-commit inside `folderOps`, dispatched to `postCommitExecutor`.
Wire the flag around it:

1. **In the request thread, before building `folderOps`** — when a cover exists to write
   (`coverBytesForNas != null && coverWriteService != null && serviceable non-empty`): resolve the
   destination location **once** (the same query used inside `folderOps` today, plus `id`), set the
   flag pessimistically, and capture the resolved values as finals so the task reuses them (removing
   the duplicate in-task query):
   ```java
   SELECT id, volume_id, path FROM title_locations
   WHERE title_id = :tid AND volume_id IN (<vols>) AND stale_since IS NULL
   ORDER BY added_date ASC, volume_id ASC LIMIT 1
   ```
   If resolved: `UPDATE title_locations SET cover_pending_since = :now WHERE id = :locId`
   (`:now` = `Instant.now()` ISO-8601 µs Z, matching existing timestamp style). Capture
   `final long coverLocId`, `final String coverVol`, `final String coverPath`.
2. **Inside `folderOps` (the async task)** — replace the in-task resolution + void call with:
   ```java
   boolean ok = coverWriteService.saveToNasBestEffort(coverPath, t.getBaseCode(), coverBytesForNas, coverVol);
   if (ok) {
       jdbi.useHandle(h -> h.createUpdate(
           "UPDATE title_locations SET cover_pending_since = NULL WHERE id = :id")
           .bind("id", coverLocId).execute());
   }
   // on !ok: leave pending — the reconciler heals it.
   ```
   `t` is still `titleRepo.findById(titleId)`; keep the existing null guards. If step 1 did not
   resolve a location (no serviceable copy), skip the cover write as today (nothing set pending).
3. Folder-rename block (b) is **unchanged**. Scratch delete, age recompute, `PromotionResult`
   all unchanged.

Inline path (`postCommitExecutor == null`, tests): the flag set (request thread) + clear (inside
`folderOps.run()`) both execute synchronously — same code, no branch needed.

## Part 4 — `PromotionCoverReconciler` (new, mirrors `PromotionFolderRenameReconciler`)

`com.organizer3.javdb.draft.PromotionCoverReconciler`. Deps: `Jdbi jdbi`, `CoverWriteService coverWriteService`,
`CoverPath coverPath`, `Set<String> serviceableVolumeIds`. Uses `smbFactory` indirectly via
`coverWriteService.saveToNasBestEffort` — **no `SessionContext`** (background-safe, like the rename
reconciler).

```
public record ReconcileResult(int candidates, int pushed, int noLocalCover, int stillPending, int failed) {}

public ReconcileResult reconcile(int limit) {
  // SELECT tl.id, tl.volume_id, tl.path, t.base_code, t.label
  //   FROM title_locations tl JOIN titles t ON t.id = tl.title_id
  //  WHERE tl.cover_pending_since IS NOT NULL AND tl.stale_since IS NULL
  //    AND tl.volume_id IN (<vols>)
  //  ORDER BY tl.cover_pending_since LIMIT :limit
  for each row:
    Title minimal = Title.builder().label(label).baseCode(baseCode).build();
    Optional<Path> local = coverPath.find(minimal);
    if (local.isEmpty() || Files.size(local.get()) == 0) { noLocalCover++; continue; } // source-less (the 51) — leave pending, no churn
    byte[] bytes = Files.readAllBytes(local.get());
    boolean ok = coverWriteService.saveToNasBestEffort(path, baseCode, bytes, volumeId);
    if (ok) { UPDATE ... SET cover_pending_since = NULL WHERE id = :id; pushed++; }
    else stillPending++;  // transient SMB — next pass retries
}
```
- **Non-clobbering falls out for free**: only `cover_pending_since IS NOT NULL` rows are touched;
  confirmed rows (user overrides, existing valid covers) are never read or written. The pushed bytes
  are the local cache = the promoted cover (or the user's override, since `save()` writes the cache
  too), so a re-push never clobbers user intent.
- One title's failure never aborts the batch (per-row try/catch → `failed++`).
- Quiet on no-op passes (log only when `pushed > 0 || failed > 0`), matching the rename reconciler.
- `noLocalCover` rows (source-less — the 51 un-enriched/failed-fetch titles) stay pending
  harmlessly; the pending set stays tiny. (Left as-is per user 2026-07-10.)

## Part 5 — Scheduling

Reuse the existing 10-min cadence. In `PromotionRenameReconcileScheduler.runOnce()` add a second
call so one daemon tick drives both reconcilers (no new thread):
```java
private void runOnce() {
    try { reconciler.reconcile(batchLimit); }
    catch (Exception e) { log.error("Promotion rename reconcile pass failed", e); }
    if (coverReconciler != null) {
        try { coverReconciler.reconcile(batchLimit); }
        catch (Exception e) { log.error("Promotion cover reconcile pass failed", e); }
    }
}
```
Add a nullable `PromotionCoverReconciler coverReconciler` to the scheduler (new ctor param;
keep a back-compat ctor passing `null` so existing tests are unaffected). Alternatively a dedicated
sibling scheduler — folding into the existing tick is preferred (one daemon, shared cadence).

## Part 6 — Manual cover override (`UnsortedEditorRoutes` `/cover` → `CoverWriteService.save`)

Belt-and-suspenders: after a successful synchronous override on a promoted title, clear any pending
flag so the reconciler never re-pushes the (now superseded) promote-time cache cover:
`UPDATE title_locations SET cover_pending_since = NULL WHERE title_id = :tid AND volume_id = :vol AND path = :path`.
`save()` already writes both NAS + local cache, so even without this the reconciler would only ever
re-push the same override bytes — this just avoids needless churn. Low priority; include if cheap.

## Part 7 — Application wiring

- Construct `PromotionCoverReconciler` (jdbi, coverWriteService, coverPath, serviceableVolumeIds —
  the same `serviceableVolumeIds` set already passed to `DraftPromotionService`/the rename
  reconciler) near the rename-reconciler construction (~line 1172).
- Pass it into `PromotionRenameReconcileScheduler`.
- No new stop() needed (same scheduler).

## Tests

- **`SchemaUpgrader`**: after upgrade, `title_locations` has `cover_pending_since`; existing rows NULL.
- **`CoverWriteService.saveToNasBestEffort`**: returns `true` on success, `false` when the SMB write
  throws (stubbed factory); never throws.
- **`DraftPromotionService`** (in-memory SQLite + stub executor): a promote with a cover sets
  `cover_pending_since` before dispatch and clears it after the (inline stub) task succeeds; a
  promote whose NAS write fails leaves it non-NULL. Promote with no cover leaves it NULL. Inline
  path (null executor) behaves identically.
- **`PromotionCoverReconciler`** (in-memory SQLite + stub `CoverWriteService`/local cover on disk):
  a pending row with an intact local cover → pushed + cleared; pending row with no/zero local cover
  → `noLocalCover`, left pending; pending row whose push fails → `stillPending`, left pending;
  confirmed (NULL) rows are never selected; scope respects `serviceableVolumeIds` + `stale_since`.
- **Scheduler**: `runOnce` invokes both reconcilers; a throw from one does not suppress the other.

## Acceptance

- Every promote that writes a cover marks the location pending, then confirms on success; a stalled/
  dropped/failed write leaves it pending and the 10-min reconciler heals it from the local cache with
  no manual action and no coverage window.
- User overrides and existing valid covers are never touched (only pending rows are).
- Source-less titles (no local cover) stay pending harmlessly; they gain covers when enriched+promoted.

## Out of scope

- Writing covers at enrich/populate time (violates the ephemeral-draft model).
- A cover-source / custom-cover marker column (not needed — non-clobbering + local-cache-as-source
  preserve overrides).
- Changing reconciler cadence (600s retained).
