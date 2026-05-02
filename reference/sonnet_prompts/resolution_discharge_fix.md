# Sonnet handoff: Resolution Discharge Fix

**Authoritative spec:** `spec/PROPOSAL_RESOLUTION_DISCHARGE_FIX.md`. Read it first; it explains the bug, the root cause, the fix, and the tests. This prompt only adds execution guidance.

---

## Scope

One bug fix, one branch, one commit (preferred) — or split into two commits if the migration feels heavy enough to merit its own.

**Branch:** `fix/resolution-discharge`

The fix is independent of the queue-priority work happening on `feat/enrichment-priority-*`. Different tables touched, no overlap. Either can land first.

---

## What to build

### 1. New repository method

`EnrichmentQueue.dischargeFailedFetchTitle(long titleId, String resolution, Handle h)` per §3.1 of the proposal. Handle-scoped — must accept a caller-owned `Handle` so it can join an existing transaction. Do NOT add a non-handle overload that opens its own transaction; every call site is already inside a transaction (see step 2).

### 2. Wire into resolution tools

Per §3.2. Three call sites:

- **`PickReviewCandidateTool.call()`** — inside the existing `useTransaction` block (currently lines 121–126), after the existing three calls (`upsertEnrichment`, `resolveAllOpenForTitle`, `revalidationPendingRepo.enqueue`), add:
  ```java
  enrichmentQueue.dischargeFailedFetchTitle(row.titleId(), "manual_picker", h);
  ```
  Inject `EnrichmentQueue` via constructor. Update `Application.java` wiring to pass it.

- **`ForceEnrichTitleTool`** — find the post-success transaction (where the enrichment is written) and add the same discharge call with resolution `"manual_override"`. Inject `EnrichmentQueue` if not already present.

- **`ResolveReviewQueueRowTool`** — currently just calls `repo.resolveOne(id, resolution)`. Needs to:
  - Look up the review row's `title_id` first (likely add an `Optional<Long> findTitleIdByQueueRow(long id)` method to `EnrichmentReviewQueueRepository`, or change `resolveOne` to return the row).
  - Open a transaction wrapping both the resolve and the discharge.
  - Call `dischargeFailedFetchTitle` ONLY for resolutions in `{"accepted_gap", "marked_resolved", "dismissed"}` — pass the resolution string as the discharge tag.
  - For `marked_moved` and `confirmed_delete`, leave the work-queue row alone — those resolutions are paired with title deletion/move workflows that handle cleanup elsewhere.

### 3. Backfill migration

Add `applyV42()` to `SchemaUpgrader` (or `applyV41` if priority's `applyV40` has not yet landed when you start; coordinate with the priority branch state). Body per §3.3 of the proposal — the SQL UPDATE that clears existing stale rows. Bump `CURRENT_VERSION` accordingly.

The migration is genuinely idempotent: re-running finds zero matching rows, no-op. Don't add defensive existence checks.

### 4. Tests

Per §4 of the proposal. Targeted test classes only, with `--rerun`:

- `EnrichmentQueueTest` — five new cases covering `dischargeFailedFetchTitle` (one updated, zero updated, pending-not-touched, multi-failed-updated, idempotent).
- `PickReviewCandidateToolTest` — assert work-queue row is `done` after a pick.
- `ForceEnrichTitleToolTest` — assert work-queue row is `done` after force-enrich.
- `ResolveReviewQueueRowToolTest` — three positive cases (accepted_gap, marked_resolved, dismissed → discharged) and two negative cases (marked_moved, confirmed_delete → not discharged).
- Migration test — three cases per §4 (closed review + failed → done; no closed review → untouched; closed review + pending → untouched).

---

## What NOT to do

- **Do not** change the resolution model or add new statuses. `done` is the right terminal state.
- **Do not** modify the Discovery → Queue or Discovery → Actresses → Errors SQL. Their `WHERE status IN (...)` filters are correct; the rows will fall out naturally once they're properly marked `done`.
- **Do not** touch `ConfirmOrphanDeleteTool` — it's already covered by FK cascade on title delete.
- **Do not** wire discharge into the `marked_moved` or `confirmed_delete` paths in `ResolveReviewQueueRowTool` — the title is being deleted/moved; the queue row is handled by those flows.
- **Do not** combine this fix with the queue-priority work. Separate concerns, separate branches, separate review.

---

## Project conventions to honor

- **No Spring** — wire `EnrichmentQueue` into the affected tools manually in `Application.java`.
- **Tests are non-negotiable** — every new code path gets a test. Repository tests use real in-memory SQLite.
- **Targeted test runs only** — never the full suite. `./gradlew test --tests com.organizer3.javdb.enrichment.EnrichmentQueueTest --rerun` and similar for each affected test class. Run results immediately, do not background and poll.
- **Schema migration discipline** — body must be idempotent (the WHERE clause already ensures this; re-running finds zero rows). No defensive existence checks for the `applyVN` body.
- **Commit message style** — match recent commits (`git log --oneline -20`); `fix(enrichment):` is the right prefix here. Body should mention the bug (review-resolved titles still showing as ambiguous on Discovery surfaces) and the user-visible example (Riko Hoshino, START-240).
- **No emojis in code or commit messages** unless the user explicitly requested them (they did not).

---

## Done criteria

- Branch `fix/resolution-discharge` ready for review.
- `CURRENT_VERSION` bumped (40 or 41 or 42 depending on priority-work merge state).
- All new tests pass; no existing tests broken.
- Manual sanity check: on a dev DB with at least one stale row matching the bug pattern, run the app, observe that after the migration runs (logged in startup output) the row is `done` and Discovery → Queue no longer shows it.
- A second sanity check: pick a candidate via the Review screen on a fresh ambiguous title, then check the corresponding `javdb_enrichment_queue` row — should be `done` with `[resolved: manual_picker]` annotation.
