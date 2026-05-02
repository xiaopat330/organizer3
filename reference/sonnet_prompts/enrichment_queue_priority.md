# Sonnet handoff: Enrichment Queue Priority (Phase 1–3)

**Authoritative spec:** `spec/PROPOSAL_ENRICHMENT_QUEUE_PRIORITY.md`. Read it first, end to end, before writing any code. Every design decision is in there; this prompt only adds execution guidance.

---

## Scope

Implement steps **1, 2, and 3** of §8 (Build order). Step 4 (first UI feature that enqueues at HIGH or URGENT) is **out of scope** — leave the elevated tiers unused on the producer side. The infrastructure ships and validates without a consumer.

Each step is its own commit on its own branch off `main`. Land them in order: 1 → 2 → 3.

---

## Step 1 — Schema + plumbing + dashboard pill

**Branch:** `feat/enrichment-priority-1-schema`

**Schema migration.** Add `applyV40()` to `src/main/java/com/organizer3/db/SchemaUpgrader.java` and bump `CURRENT_VERSION` from 39 to 40. Migration body per §3.1 of the proposal:

- `ALTER TABLE javdb_enrichment_queue ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'))`.
- `CREATE INDEX IF NOT EXISTS idx_jeq_claim_priority ON javdb_enrichment_queue(priority, sort_order, id) WHERE status = 'pending'`.

Existing rows get `'NORMAL'` via the column default. No backfill UPDATE needed.

**Java enum.** New `com.organizer3.javdb.enrichment.Priority` enum: `LOW, NORMAL, HIGH, URGENT`. String round-trip via `name()` / `Priority.valueOf(String)`.

**`EnrichmentJob` record.** Add `Priority priority` field. Update the mapper in `EnrichmentQueue.mapJob`.

**`EnrichmentQueue` enqueue overloads.** All four methods — `enqueueTitle`, `enqueueTitleForce`, `enqueueActressProfile`, `enqueueActressProfileForce` — gain a `Priority priority` parameter. Existing zero-arg-priority signatures stay as overloads that delegate with `Priority.NORMAL`. Zero existing call sites should need to change.

**No claim-order change yet.** `claimNextJob` continues to ignore priority. That comes in step 2.

**No URGENT exemption yet.** `cancelAll` and `cancelForActress` continue to cancel everything. That also comes in step 2.

**Queue dashboard pill.** Find the existing javdb-discovery queue dashboard surface (most likely `src/main/resources/public/modules/utilities-javdb-discovery.js` and/or related files — grep for the queue-rendering code that lists pending items). Add a small read-only priority pill on each row, rendered from the new `priority` field on the row data. Display order **stays insertion-based** — do not re-sort by priority. Style the pill with a CSS class per priority (`prio-low`, `prio-normal`, `prio-high`, `prio-urgent`); `prio-normal` can be visually subtle (or omitted from rendering) since it's the default for all rows pre-step-4. Make HIGH and URGENT visually distinct enough that they jump out — they're rare.

The API endpoint that returns queue rows for the dashboard must be updated to include `priority` in the response payload.

**Tests:**
- `EnrichmentQueueTest`: enqueue without priority → row has NORMAL; enqueue with each of LOW/NORMAL/HIGH/URGENT → row has the right value.
- `SchemaUpgraderTest` (or whatever pattern existing migrations use): post-migration column exists, default is NORMAL, CHECK rejects invalid values.

**Non-goal:** any test of priority-aware claim ordering. That's step 2.

---

## Step 2 — Priority-aware claim + cancel exemption

**Branch:** `feat/enrichment-priority-2-claim-order`

**`claimNextJob` SQL** per §4.2 of the proposal. Picks LOW/NORMAL/HIGH only (URGENT excluded — it has its own claim path in step 3). Order: priority CASE → `sort_order` → `id`.

**`cancelAll` and `cancelForActress`:** add `AND priority != 'URGENT'` to the WHERE clause. URGENT rows survive bulk cancel.

**Tests** per §7 Phase 1 of the proposal, plus the cancel-exemption tests:
- HIGH claimed before NORMAL claimed before LOW.
- Within a tier, `(sort_order, id)` ordering preserved (use a setup that has multiple rows per tier).
- An ineligible HIGH (`next_attempt_at` in the future) does NOT block a ready NORMAL — claim picks the NORMAL.
- `cancelAll`: NORMAL/HIGH/LOW pending rows → cancelled; URGENT pending row → still pending after.
- `cancelForActress`: same, scoped to one actress.

**Test discipline.** Use `--rerun` on every test execution per project convention (see `feedback_gradle_testing` in user memory). Run only the targeted test classes you modified, not the full suite (`feedback_test_runs`). Cache hits look like passing tests.

---

## Step 3 — URGENT bypass in the runner

**Branch:** `feat/enrichment-priority-3-urgent`

**`EnrichmentQueue.claimNextUrgentJob()`** per §4.2. URGENT-only, ignores `next_attempt_at`. Same atomic claim-and-flip-to-`in_flight` pattern as `claimNextJob`.

**`EnrichmentRunner.runOneStep()`** restructured per §5 of the proposal:

1. Operator pause check (existing) — respected by URGENT too.
2. `claimNextUrgentJob()` — execute if present, return.
3. Rate-limit / burst pause check (existing) — gates only LOW/NORMAL/HIGH.
4. `claimNextJob()` (existing flow).

Refactor the per-job execute body (the switch on `job.jobType()`, success accounting, burst counter, 429 catch) into a private `executeJob(job)` method called by both URGENT and normal paths. URGENT paths must increment the burst counter, can trigger a burst break for subsequent jobs, can throw `JavdbRateLimitException` and set `pauseUntil` — same as normal.

**Tests** per §7 Phase 2 of the proposal:
- `claimNextUrgentJob` returns URGENT even when `pauseUntil` is in the future.
- `claimNextUrgentJob` returns URGENT even when its `next_attempt_at` is in the future.
- `claimNextUrgentJob` returns empty when no URGENT pending.
- `claimNextUrgentJob` does NOT return LOW/NORMAL/HIGH rows.
- Operator pause blocks URGENT execution in `runOneStep`.
- URGENT execution increments the burst counter; URGENT 429 sets `pauseUntil`; subsequent URGENT still runs through the pause.
- Multiple URGENT pending: claimed in `(sort_order, id)` order.

For the runner-level tests, use the existing `EnrichmentRunnerTest` patterns — there's already infrastructure for stubbing the clock and the rate-limit pause.

---

## What NOT to do

- **Do not** add a "Run now" button or any "set priority" UI control. Per the proposal's design principle, priority is set at enqueue and never changes.
- **Do not** wire any producer to enqueue at HIGH or URGENT in this work. Those will be added per-feature later.
- **Do not** add priority to the `TaskRunner` / utilities-task subsystem. Scope is `javdb_enrichment_queue` only.
- **Do not** change retry/backoff logic. Failed HIGH uses the same backoff as failed NORMAL. URGENT bypasses backoff via `claimNextUrgentJob`'s ignore of `next_attempt_at`, but does not get other special retry handling.
- **Do not** auto-promote based on heuristics. No "if title is in the actress currently being viewed, bump to HIGH." That's a §9 non-goal.
- **Do not** combine steps 1, 2, 3 into one branch/commit — keep them separable so each is reviewable and revertable independently.

---

## Project conventions to honor

- **No Spring** — wire any new dependencies manually in `Application.java` (see `CLAUDE.md`).
- **Tests are non-negotiable** — every new method gets a test. Repository tests use real in-memory SQLite.
- **Schema migration discipline** — `applyV40` body must be idempotent (the `IF NOT EXISTS` on the index handles this; the `ADD COLUMN` will fail if re-run, but `SchemaUpgrader` only invokes `applyVN` when version is below N, so this is fine — do not add defensive existence checks for the column).
- **Targeted test runs only** — never run the full suite. `./gradlew test --tests com.organizer3.javdb.enrichment.EnrichmentQueueTest --rerun` etc. Run results immediately, do not background and poll.
- **Commit message style** — match recent commits (`git log --oneline -20`); `feat(enrichment): ...` is the right prefix.
- **No emojis in code or commit messages** unless the user explicitly requested them (they did not for this work).

## Done criteria

- All three branches have been merged to `main` (or sit ready for review, depending on the user's preferred flow — confirm with them).
- `CURRENT_VERSION = 40` in `SchemaUpgrader`.
- The queue dashboard renders priority pills on every row; everything shows NORMAL because no producer is creating elevated rows yet — this is expected and correct.
- All new tests pass; no existing tests broken.
- A manual sanity-check is appropriate at the end: insert a HIGH row directly via SQL into a dev DB, observe that `claimNextJob` picks it ahead of older NORMAL rows. Insert a URGENT row, observe that `runOneStep` picks it even with `pauseUntil` set.
