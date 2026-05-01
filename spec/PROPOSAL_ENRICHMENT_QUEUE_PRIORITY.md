# Proposal: Enrichment Queue Priority

**Status:** Draft 2026-05-01 — design pinned, ready to implement when greenlit.
**Origin:** Today's enrichment queue is pure FIFO (within `sort_order`). Some work needs to jump the queue; a rare class of work needs to bypass the rate limiter entirely.

---

## 1. Problem statement

`javdb_enrichment_queue` is processed strictly in `(sort_order, id)` order. Every enrichment job competes equally for the next slot. Two real needs are unmet:

1. **Some jobs deserve priority.** Certain UI-triggered enrichments (e.g. user explicitly resuming a single actress's failed jobs, or wanting fresh data for an actress they're actively curating) should jump ahead of the routine backlog without requiring manual reorder of every intervening item.
2. **Some jobs are time-critical enough to bypass throttling entirely.** Rate-limit pauses (reactive 429 backoff and proactive burst breaks) can stall the queue for tens of minutes. For a rare class of operator-initiated work, the user is willing to spend rate-limit budget against an active pause to get an answer right now.

---

## 2. Design principles

1. **Priority is a property of the work item, set at enqueue, immutable after.** No "promote to HIGH" button. NORMAL stays NORMAL forever. HIGH/URGENT items must be created at that priority by a specific UI feature.
2. **NORMAL is the universal default.** Every existing enqueue path stays NORMAL. Nothing auto-bumps to HIGH or URGENT.
3. **HIGH/URGENT only originate from the app UI.** No background process inserts at elevated priority. This keeps the elevated tiers rare and intentional.
4. **URGENT bypasses every throttle.** Rate-limit pause, burst break, per-job backoff (`next_attempt_at`) — all ignored. The only thing URGENT respects is operator pause and currently in-flight work.
5. **Within a priority tier, FIFO is preserved.** URGENT-vs-URGENT, HIGH-vs-HIGH, NORMAL-vs-NORMAL all order by `(sort_order, id)` exactly as today.

---

## 3. Data model

### 3.1 Schema migration

Add one column to `javdb_enrichment_queue`:

```sql
ALTER TABLE javdb_enrichment_queue
  ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'
  CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT'));
```

Backfill: existing rows take the default `'NORMAL'`. No data conversion needed.

Index for the claim path (partial, pending-only, since the runner only ever reads pending rows):

```sql
CREATE INDEX IF NOT EXISTS idx_jeq_claim_priority
  ON javdb_enrichment_queue(priority, sort_order, id)
  WHERE status = 'pending';
```

The existing `idx_jeq_claim ON (status, next_attempt_at)` stays — still useful for non-claim queries.

### 3.2 `EnrichmentJob` record

Add `String priority` field. Mappers updated.

### 3.3 Enum vs. string

Keep priority as `TEXT` in the schema (constraint enforces values), with a Java `Priority` enum for type safety in the API. Avoids a SQLite enum-table gymnastics for four values.

---

## 4. API changes

### 4.1 Enqueue path

All four enqueue methods — `enqueueTitle`, `enqueueTitleForce`, `enqueueActressProfile`, `enqueueActressProfileForce` — gain a `Priority priority` parameter. Priority is universal across job types: an URGENT actress-profile fetch is just as valid as an URGENT title fetch. Existing callers pass `Priority.NORMAL` — preserved via overload that defaults to NORMAL, so zero call sites need to change.

```java
public void enqueueTitle(String source, long titleId, Long actressId) {
    enqueueTitle(source, titleId, actressId, Priority.NORMAL);
}
public void enqueueTitle(String source, long titleId, Long actressId, Priority priority) { ... }
```

New UI-triggered enqueue paths (the specific HIGH/URGENT use cases) call the explicit-priority overload.

### 4.2 Claim path

`claimNextJob()` — handles LOW / NORMAL / HIGH only.

```sql
SELECT id FROM javdb_enrichment_queue
WHERE status = 'pending' AND next_attempt_at <= :now
  AND priority IN ('LOW', 'NORMAL', 'HIGH')
ORDER BY
  CASE priority
    WHEN 'HIGH'   THEN 0
    WHEN 'NORMAL' THEN 1
    WHEN 'LOW'    THEN 2
  END ASC,
  COALESCE(sort_order, 9223372036854775807) ASC,
  id ASC
LIMIT 1
```

`claimNextUrgentJob()` — new method, URGENT only, ignores `next_attempt_at`:

```sql
SELECT id FROM javdb_enrichment_queue
WHERE status = 'pending' AND priority = 'URGENT'
ORDER BY
  COALESCE(sort_order, 9223372036854775807) ASC,
  id ASC
LIMIT 1
```

URGENT explicitly bypasses `next_attempt_at <= :now` — a per-job backoff (set after a transient failure) does not delay URGENT.

Both methods are atomic claim-and-flip-to-in_flight, same pattern as today.

### 4.3 No re-prioritization API

By design — see principle 1. No `setItemPriority`, no "Run now" button on existing rows, no UI promote control.

---

## 5. Runner integration

`EnrichmentRunner.runOneStep` gets a small reordering at the top:

```java
void runOneStep() throws InterruptedException {
    // 1. Operator pause: respected by everything, including URGENT.
    if (paused.get()) {
        sleepInterruptibly(30_000);
        return;
    }

    // 2. URGENT bypass: check before rate-limit pause.
    Optional<EnrichmentJob> urgent = queue.claimNextUrgentJob();
    if (urgent.isPresent()) {
        executeJob(urgent.get());  // shared with the normal path below
        return;
    }

    // 3. Rate-limit / burst pause: gates only LOW/NORMAL/HIGH.
    if (Instant.now().isBefore(pauseUntil)) {
        // existing pause-logging + sleep logic
        return;
    }

    // 4. Normal claim — LOW/NORMAL/HIGH ordering.
    Optional<EnrichmentJob> maybeJob = queue.claimNextJob();
    // ... existing execute logic ...
}
```

`executeJob(...)` extracts the existing switch + accounting (burst counter, success counter, 429 handling) into a method shared by both URGENT and normal paths. URGENT increments the burst counter, can trigger a burst break for subsequent jobs, can hit 429 and set `pauseUntil` — all the metrics stay honest.

### 5.1 What URGENT does NOT do

- **Preempt in-flight work.** If a NORMAL job is currently being fetched when URGENT arrives, URGENT waits for the in-flight to complete. The runner is single-threaded by design; this is automatic, not enforced by special logic.
- **Reset rate-limit state.** A successful URGENT fetch does not lift `pauseUntil` for subsequent NORMAL work. The pause was set for a reason; URGENT is an exception, not a reset.
- **Bypass operator pause.** `paused.get()` blocks URGENT too. URGENT items sit in the queue until the operator unpauses; they then run before NORMAL work resumes (still URGENT-first).

### 5.2 URGENT and bulk cancellation

`cancelAll` and `cancelForActress` are exempt from URGENT. Both methods scope their cancellation to non-URGENT rows:

```sql
UPDATE javdb_enrichment_queue
SET status = 'cancelled', updated_at = :now
WHERE status = 'pending'
  AND priority != 'URGENT'
  -- (existing WHERE clauses for actress filter, etc.)
```

Rationale: URGENT carries explicit operator intent ("run this now, regardless of throttling"). A subsequent bulk cancel is unlikely to mean "and also drop the URGENT thing I just deliberately escalated." Treating URGENT as cancel-exempt protects that intent. Per-row cancel (if such a path is added later) is unaffected — explicitly cancelling an URGENT row by id is honored.

### 5.3 Multiple URGENTs

URGENT items run back-to-back (FIFO within URGENT tier) with no rate-limit pause between them. If three URGENT items are queued, the runner processes all three before resuming normal flow. If during those three the runner gets a 429, `pauseUntil` is set as usual — but the next URGENT in line still runs immediately on the next loop tick (it bypasses the pause it just helped create).

This is intentional: URGENT means "the user accepts the rate-limit risk." Letting subsequent URGENTs proceed honors that.

---

## 6. UI integration

- Any UI feature that wants to enqueue at HIGH or URGENT calls the explicit-priority overload directly.
- No generic "set priority" UI control. No "Run now" button on existing pending rows.
- Each individual feature decides its own priority; this proposal does not enumerate which ones get what.

### 6.1 Queue dashboard pill (ships in step 1)

The existing queue dashboard renders a priority pill on each pending row, read-only. Ships with the schema migration so elevated rows are visible from day one — even before any consumer enqueues at HIGH or URGENT, the column is populated (NORMAL for everything) and ready.

**List ordering in the UI is unchanged.** Rows display in insertion / `sort_order` order as today. The runner picks the next eligible row by priority, so what the user observes is that "next up" / "in-flight" is no longer always the top-row item — it can be a row further down if it carries HIGH or URGENT. The pill is the explanation: when the operator sees a mid-list row become in-flight, the URGENT/HIGH pill on it tells them why.

This intentionally separates *display order* (stable, intuitive, by insertion) from *processing order* (priority-aware). Trying to keep them in sync would mean re-sorting the dashboard whenever priority influences claim order — confusing.

---

## 7. Tests

Phase 1 (priority ordering):

- `claimNextJob` picks HIGH before NORMAL before LOW, when all are pending and eligible.
- Within a tier, `claimNextJob` honors `(sort_order, id)` exactly as today.
- An ineligible HIGH (`next_attempt_at` in the future) does NOT block a ready NORMAL — claim falls through to the next eligible row in priority+FIFO order.
- Default priority on enqueue without explicit param is NORMAL.
- Existing rows post-migration are NORMAL.

Phase 2 (URGENT bypass):

- `claimNextUrgentJob` returns a URGENT job even when `pauseUntil` is in the future (runner-level test using a stubbed clock).
- `claimNextUrgentJob` returns a URGENT job even when its `next_attempt_at` is in the future.
- `claimNextUrgentJob` returns null when no URGENT pending — caller falls through to normal path.
- `claimNextUrgentJob` does NOT return LOW/NORMAL/HIGH rows.
- Operator pause (`paused.get()`) blocks URGENT execution (runner sits at step 1).
- URGENT execution increments burst counter; an URGENT 429 sets `pauseUntil`; subsequent URGENT still runs through the pause.
- Multiple URGENT pending: claimed in `(sort_order, id)` order.
- `cancelAll` does NOT cancel URGENT rows; cancels everything else as today.
- `cancelForActress` does NOT cancel URGENT rows for that actress; cancels other priorities for that actress as today.

---

## 8. Build order

1. **Schema migration + `Priority` enum + `EnrichmentJob.priority` + queue-dashboard pill** (single commit). All new code reads/writes priority; queue dashboard renders the pill on every row (everything shows NORMAL until producers arrive). Every enqueue still defaults to NORMAL. No claim-order change yet — pure plumbing + visibility. Existing tests should pass unchanged.
2. **`claimNextJob` ordering update + cancel-path URGENT exemption** + tests for tier ordering and cancel exemption. Behavior change: claim is now priority-aware; URGENT rows survive bulk cancel. Still no HIGH/URGENT producers, so user-observable behavior is identical until step 4.
3. **`claimNextUrgentJob` + runner integration** + URGENT bypass tests. Behavior wired but unused.
4. **First UI feature that enqueues at HIGH or URGENT** — TBD per the UI design discussion. This is the first real consumer.

Steps 1–3 can ship without step 4; the elevated tiers simply don't get used yet. That's a clean place to land the infrastructure and validate it before wiring features.

---

## 9. Non-goals

- **Re-prioritization of pending items.** Set at enqueue, immutable.
- **Auto-promotion based on heuristics.** Every elevated-tier insertion is an explicit choice by a specific UI feature.
- **Priority for non-javdb queues.** Scope is `javdb_enrichment_queue`. Other queues (`utilities-task` / `TaskRunner`, probe jobs, etc.) keep their existing behavior.
- **Multi-worker concurrency.** Runner stays single-threaded. Priority is purely about *what gets claimed next*, not parallelism.
- **Priority-aware retry backoff.** A failed HIGH job uses the same backoff schedule as a failed NORMAL job. URGENT bypasses backoff via `claimNextUrgentJob`'s ignore of `next_attempt_at`, but failed-then-retried URGENT is still a retried URGENT — it doesn't get extra-special handling.
