# PROPOSAL: Async post-commit folder ops + bounded SMB read timeout

**Status:** DRAFT (implementation-ready)
**Branch:** `fix/async-promote-smb`
**Motivation:** During bulk curation, a single stale/dead SMB connection froze the
Unprocessed promote UI for ~5 minutes and wedged the pandora `unsorted` connection
pool until an app restart. Root causes are two independent axes; both must be fixed.

## Background — the two independent defects

Observed 2026-07-10 (title 92415 / APGH-056):

1. **Synchronous SMB on the request thread.** `DraftPromotionService.promote` runs two
   post-commit SMB steps — NAS cover-write + folder rename — *inline on the Jetty
   request thread*. The DB transaction had already committed (`curated_at` set, cast
   written, draft consumed), but the HTTP response blocked on the stalled rename, so the
   browser spinner hung even though the promotion had succeeded. The no-draft path
   (`UnsortedEditorService.renameFolderIfNeeded`) has the same shape.

2. **Unbounded-in-practice SMB read timeout.** `SmbSettings` read/transact timeouts
   default to **5 minutes**. A dead pandora socket therefore took ~5 min
   (`00:51:49` hang → `00:56:49` broken-pipe detection) to fail, evict, and re-dial.
   That 5-minute window is what let the wedge persist and block the reconciler's re-dial.

**Async alone hides the stranding** (the folder silently never renames until a restart
during a wedge, because the reconciler shares the same wedged connection factory).
**Timeout alone self-heals but the UI can still freeze up to the timeout.** Both together
deliver: *promote returns instantly, and a flaky connection heals silently within seconds.*

## Part 1 — Bounded SMB read/transact timeout (root cause)

### Design
- Add two seconds-granular fields to `SmbSettings` (`com.organizer3.smb` /
  `com.organizer3.config`, wherever the record lives):
  - `readTimeoutSeconds` (Integer, nullable) — default **45**
  - `transactTimeoutSeconds` (Integer, nullable) — default **45**
  - Accessors `readTimeoutSecondsOrDefault()` / `transactTimeoutSecondsOrDefault()`
    with `DEFAULT_READ_TIMEOUT_SECONDS = 45`, `DEFAULT_TRANSACT_TIMEOUT_SECONDS = 45`.
- Update **both** `buildSmbConfig` methods (`SmbConnectionFactory` **and** `SmbjConnector`):
  - `.withReadTimeout(readTimeoutSecondsOrDefault(), TimeUnit.SECONDS)`
  - `.withTransactTimeout(transactTimeoutSecondsOrDefault(), TimeUnit.SECONDS)`
  - **Leave `.withSoTimeout(...)` and `.withWriteTimeout(...)` exactly as they are**
    (minute-based). See rationale.

### Rationale — why NOT touch soTimeout / writeTimeout
- `withReadTimeout` / `withTransactTimeout` bound the wait for a response to an
  **already-sent** request (the hung `Promise.retrieve()`). This is precisely the
  rename hang. Shortening them makes a dead connection fail in ~45s → evict → re-dial →
  self-heal. They only fire when a request is outstanding, so they never disturb idle
  connections.
- `withSoTimeout` is the raw socket `SO_TIMEOUT`; smbj's packet-reader blocks on it while
  a connection is idle. Shortening it risks tearing down **healthy idle** connections
  (e.g. between promotes with a >45s gap), causing re-dial churn. Out of scope; leave at 5 min.
- `withWriteTimeout` governs large uploads; not implicated. Leave at 5 min.
- 45s is safe for streaming/large reads because these are **per-request** timeouts and
  SMB2 chunks large reads into many requests; a healthy transfer completes each chunk far
  under 45s.

### Config docs
Update the commented `smb:` block in `organizer-config.yaml` to add
`readTimeoutSeconds: 45` / `transactTimeoutSeconds: 45` with a one-line note that these
bound the wait on a dead connection (fast self-heal) and are deliberately shorter than the
minute-based idle/write timeouts.

### Tests
- `SmbSettings` default accessors return 45 when unset; return the override when set.
- `buildSmbConfig` (both classes) yields an `SmbConfig` whose read/transact timeout equals
  the configured seconds value (assert via the seconds the builder was fed, or a small seam).
- Existing SMB timeout tests updated for the new defaults.

### Acceptance
- A request issued on a dead socket fails within ~45s (not ~5 min), the factory evicts the
  connection, and the next call re-dials cleanly.
- **Eviction-on-readTimeout VERIFIED (static trace, 2026-07-10):** the self-heal depends on a
  readTimeout actually evicting the pooled connection, not merely failing the caller. Confirmed
  end-to-end: smbj raises `com.hierynomus.protocol.transport.TransportException` when its
  `future.get(readTimeout)` times out → `SmbConnectionFactory.isBrokenPipe` (line ~474) returns
  true for any `TransportException`/`SMBRuntimeException` in the cause chain →
  `SmbConnectionFactory.withRetry` (line ~145) evicts and retries on a fresh dial → the folder
  rename routes through `withRetry` (`TitleFolderRenamer` line ~214). Therefore the FIRST caller
  to hit a wedged connection self-heals (evict + fresh re-dial) — no restart, no per-caller 45s
  cascade. The 5-min hang observed 2026-07-10 was solely the 5-min readTimeout; the eviction path
  itself already fired ("SMB broken pipe … evicting and retrying" log). Real-world proof is
  watching the next live wedge self-heal — treat as verified-by-trace, confirm-on-next-wedge.
- Per-call breadth: 45s is safe for `CoherentMultiVolumeSyncTask` — its aggregate is bounded by
  `perVolumeTimeoutMinutes` (120), and no single SMB call there legitimately exceeds 45s.

## Part 2 — Async post-commit folder ops (UX)

### New component: `PostCommitSmbExecutor`
`com.organizer3.web` (or `com.organizer3.smb`). Wraps a `ThreadPoolExecutor`:
- Fixed pool **3** daemon threads (name `post-commit-smb-N`).
- Bounded queue (capacity ~256).
- Rejection handler: **log at WARN and drop** (`"post-commit SMB task dropped (queue full);
  reconciler will heal"`). Never `CallerRuns` (that reintroduces request-thread blocking).
- `void submit(String label, Runnable task)` — wraps `task` in try/catch so a thrown SMB
  exception is logged (`"post-commit SMB task '{label}' failed"`) and never escapes.
- `void shutdown()` — graceful, called from Application stop sequence.
- Unit-testable with an injected/overridable executor; tests assert submit runs the task,
  swallows exceptions, and drops on saturation.

### `DraftPromotionService` changes
- New nullable dependency `PostCommitSmbExecutor postCommitExecutor` (add to the full ctor;
  short ctors pass `null`). **When null, run the SMB steps inline (current behavior)** — this
  preserves every existing test unchanged.
- In `promote()`, after COMMIT and after the local-only steps (effective-tags recompute stays
  inline), replace the two inline SMB blocks (NAS cover-write @ ~356-383, folder rename
  @ ~385-408) with:
  1. **In the request thread**, capture everything the task needs so it is self-contained and
     the scratch delete can't race it:
     - `byte[] coverBytes` = `coverStore.read(draftTitleId).orElse(null)` (read **before** the
       scratch delete).
     - Keep `titleId`, `draftCheck.getCode()`, the guards (`destCoverHolder[0] != null`,
       `primaryHolder[0] != null`).
  2. Build a `Runnable` that performs (in order): NAS cover-write (resolve volume+path via the
     existing single query, then `coverWriteService.saveToNasBestEffort(...)`) then
     `renamer.renamePreservingDescriptor(titleId, orderedNames, code)`. Reuse
     `StagingCastHelper.orderedNamesForTitle(jdbi, titleId)` **inside** the task (post-commit
     DB state; jdbi is thread-safe). Preserve the existing try/catch semantics (collision →
     WARN, other → WARN).
  3. If `postCommitExecutor != null`: `postCommitExecutor.submit("promote:"+code, task)` and
     set `folderRenamed = false` (now async — cannot report the outcome synchronously).
     Else: run `task.run()` inline and derive `folderRenamed` as today.
- The scratch-cover delete (currently ~410-417) stays in the request thread — safe because
  `coverBytes` was already captured.
- `age_at_release` recompute stays inline (local DB).
- `PromotionResult.folderRenamed` semantics change to "renamed synchronously" (false when
  dispatched async). Route/UI must not treat `folderRenamed:false` as an error — verify
  `DraftRoutes` @ ~392 and the v1/v2 promote handlers just report success; adjust copy if any
  branch shows an alarming message.

### `UnsortedEditorService` changes
- New nullable dependency `PostCommitSmbExecutor postCommitExecutor` (full ctor; short ctors
  pass `null`, run inline).
- In `renameFolderIfNeeded` (~443): when `postCommitExecutor != null`, build the rename
  `Runnable` (the existing multi-name/legacy branch logic) and dispatch it; return
  `new SaveResult(committed.actressIds(), committed.primaryActressId(), detail.folderPath(),
  false)` (pre-rename path, `renamed=false`). When null, keep the current inline behavior and
  return the real outcome. No NAS cover-write on this path (rename only).

### Application wiring
- Construct one `PostCommitSmbExecutor` (daemon, 3 threads); inject into both
  `DraftPromotionService` and `UnsortedEditorService`; call `shutdown()` in the stop sequence
  alongside the other executors.
- Reconciler + scheduler unchanged (already the durability backstop for both paths).

### Tests
- `DraftPromotionService`: with a stub executor, promote **dispatches** the folder op and
  returns immediately; the DB commit is unaffected; inline path (null executor) unchanged.
- `UnsortedEditorService`: with a stub executor, save dispatches the rename and returns the
  pre-rename path; inline path unchanged.

### Acceptance
- Promote/save returns as soon as the DB commits; a subsequent SMB stall never blocks the HTTP
  response. A dropped/failed async task is healed by the reconciler. Combined with Part 1, a
  dead connection is detected within ~45s and re-dials without a restart.

## Out of scope
- Per-op or per-volume SMB clients (single global client retained).
- soTimeout / idle-connection lifecycle tuning.
- Any change to the reconciler cadence (600s) — it remains the backstop.
