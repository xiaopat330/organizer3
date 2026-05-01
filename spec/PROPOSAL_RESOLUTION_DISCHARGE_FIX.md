# Proposal: Resolution Discharge Fix (review-queue ↔ work-queue reconciliation)

**Status:** Draft 2026-05-01 — bug fix, ready to implement.
**Origin:** User reported titles for Riko Hoshino still appearing as "ambiguous" on Discovery → Queue and Discovery → Actresses → Errors after manual resolution via the Review screen. START-240 is one of many examples.

---

## 1. Bug

When an enrichment job hits an ambiguous result, two rows are created:

- **`javdb_enrichment_queue`** — the work queue. Row goes to `status='failed', last_error='ambiguous'`.
- **`enrichment_review_queue`** — the human-facing queue. Open row created for the operator to resolve.

When the operator resolves the review row (Pick / Override slug / Mark resolved / Accept gap / Dismissed), the resolution path updates `enrichment_review_queue` but **never updates `javdb_enrichment_queue`**. The work-queue row sits at `failed` forever.

**Where this surfaces incorrectly:**

- Discovery → Queue (`JavdbDiscoveryService.getActiveQueueItems`, line 519): `WHERE q.status IN ('pending','in_flight','failed','paused')` — orphaned `failed` rows stay visible. The LEFT JOIN to `enrichment_review_queue` correctly returns null `review_queue_id` (review row resolved), so the row shows as a plain failure with no actionable button. Confusing.
- Discovery → Actresses → Errors: same pattern; reads `javdb_enrichment_queue` for failed rows, never reconciles.
- Discovery → Actress → Titles: appears correct because that view is enrichment-driven (joins `title_javdb_enrichment`); the work-queue join is informational only.

**Why `ConfirmOrphanDeleteTool` accidentally avoids the bug:** it deletes the title; FK cascade removes the queue row. Every other resolution path leaks.

---

## 2. Root cause audit

Searched all resolution tools for any reference to `javdb_enrichment_queue`, `markDone`, or `EnrichmentQueue`:

| Tool | Updates `javdb_enrichment_queue`? |
|---|---|
| `PickReviewCandidateTool` | No |
| `ForceEnrichTitleTool` (force-enrich / Override slug) | No |
| `ResolveReviewQueueRowTool` (Mark resolved / Accept gap / Dismissed / Mark moved / Confirmed delete) | No |
| `ConfirmOrphanDeleteTool` | No, but title deletion's FK cascade removes the row |

`EnrichmentQueue.markDone(long id)` exists and works fine — it's just never called from these paths.

---

## 3. Fix

### 3.1 New repository method

Add to `EnrichmentQueue`:

```java
/**
 * Marks any failed fetch_title rows for this title as done. Used by review-queue
 * resolution paths to reconcile the work queue when the operator manually resolves
 * an ambiguous (or other failed) job.
 *
 * <p>Targets {@code status='failed'} only. Pending and in_flight rows represent
 * active intent and are not touched. The original {@code last_error} is preserved
 * with an annotation noting the resolution kind.
 *
 * @param titleId    the title whose failed work-queue rows should be discharged
 * @param resolution short tag describing how the row was resolved (e.g. {@code "manual_picker"})
 * @param h          open JDBI handle (caller owns the transaction)
 * @return count of rows discharged
 */
public int dischargeFailedFetchTitle(long titleId, String resolution, Handle h) {
    return h.createUpdate("""
            UPDATE javdb_enrichment_queue
            SET status = 'done',
                updated_at = :now,
                last_error = COALESCE(last_error, '') || ' [resolved: ' || :resolution || ']'
            WHERE job_type = 'fetch_title'
              AND target_id = :titleId
              AND status = 'failed'
            """)
            .bind("now", now())
            .bind("titleId", titleId)
            .bind("resolution", resolution)
            .execute();
}
```

Handle-scoped so it joins existing transactions in resolution tools — no nested transactions.

### 3.2 Call sites

Each resolution path that means "this title has been dealt with" calls `dischargeFailedFetchTitle` inside the same transaction that resolves the review row.

| Tool | Resolution tag | Notes |
|---|---|---|
| `PickReviewCandidateTool` | `"manual_picker"` | Add to existing `useTransaction` block (line 121–126) |
| `ForceEnrichTitleTool` | `"manual_override"` | After successful enrichment write |
| `ResolveReviewQueueRowTool` | `accepted_gap` / `marked_resolved` / `dismissed` | Use the resolution string itself as the discharge tag. NOT for `marked_moved` / `confirmed_delete` (handled by deletion/move workflows). Need to look up `title_id` from the review row before discharging. |
| `ConfirmOrphanDeleteTool` | n/a | Already covered by FK cascade on title delete |

### 3.3 One-time backfill of existing stale rows

Existing stale rows already in the DB get cleaned up via a one-off migration step (or a manual SQL run before the fix ships). The criterion is unambiguous: the review row is closed AND the work-queue row is `failed`.

```sql
UPDATE javdb_enrichment_queue
SET status = 'done',
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    last_error = COALESCE(last_error, '') || ' [resolved: backfill_cleanup]'
WHERE job_type = 'fetch_title'
  AND status = 'failed'
  AND target_id IN (
    SELECT title_id FROM enrichment_review_queue
    WHERE resolved_at IS NOT NULL
      AND resolution IN ('manual_picker', 'manual_override', 'accepted_gap', 'marked_resolved', 'dismissed')
  );
```

Two ways to ship this:

- **As a SchemaUpgrader migration** (e.g. `applyV41` after the priority work's `applyV40` lands). One-off, idempotent (re-running finds nothing to update). Recommended — gets the cleanup applied automatically on next startup, no manual step required.
- **As a manual SQL run by the operator** before the fix deploys. Simpler, no schema version bump, but requires the operator to remember.

Recommend the migration approach.

---

## 4. Tests

**Repository:**
- `dischargeFailedFetchTitle` updates exactly one row when one matching failed row exists.
- Updates zero rows when no failed row exists (returns 0, no errors).
- Does NOT update pending or in_flight rows for the same title.
- Updates multiple rows when multiple failed `fetch_title` rows exist for the title (force-reenrich history).
- Idempotent: second call does nothing.

**Tool integration:**
- `PickReviewCandidateTool`: after a successful pick, the corresponding `javdb_enrichment_queue` row is `done` with `last_error` annotated `[resolved: manual_picker]`.
- `ForceEnrichTitleTool`: same, with `manual_override`.
- `ResolveReviewQueueRowTool`: for each of `accepted_gap`, `marked_resolved`, `dismissed`, the work-queue row becomes `done`.
- `ResolveReviewQueueRowTool`: for `marked_moved` and `confirmed_delete`, work-queue row is NOT touched by this tool (those flows handle queue cleanup elsewhere — verify the existing behavior, don't change it).

**Migration (if shipped as `applyV41`):**
- Pre-state: a title with a closed review row + a failed work-queue row → post-migration the work-queue row is `done` with `[resolved: backfill_cleanup]` annotation.
- Pre-state: a title with no closed review row + a failed work-queue row → untouched.
- Pre-state: a title with a closed review row + a pending work-queue row → untouched.
- Idempotent: re-running the migration finds nothing.

---

## 5. Non-goals

- **Changing the resolution model.** The two-table design (work queue + review queue) stays. The bug is reconciliation, not architecture.
- **Adding a new status.** `done` is the right terminal state; no `manually_resolved` status needed.
- **Changing the surfaces.** Discovery → Queue and Discovery → Actresses → Errors keep their existing SQL. Once the work-queue rows are correctly transitioned to `done`, they fall out of the `WHERE status IN (...)` filter naturally.
- **Touching enrichment-driven views.** Discovery → Actress → Titles already shows correct data; no changes there.

---

## 6. Build order

Single commit on a branch off `main`. Independent of the queue-priority work — different tables, no overlap. Can ship in parallel.

1. Add `dischargeFailedFetchTitle` + repository test.
2. Wire it into the three resolution tools + their tests.
3. Add `applyV41` migration with backfill SQL + migration test.
4. Bump `CURRENT_VERSION` to 41 (or to whatever the next number is, if priority's `applyV40` has already merged and bumped it).
