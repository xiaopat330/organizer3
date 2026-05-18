# AI Picker Assist — Phase 3 Implementation Plan

Companion to `spec/PROPOSAL_AI_PICKER_ASSIST.md`. Phase 3 = auto-apply for `agreed` suggestions. End state for the feature.

**Estimated total**: ~half day with parallel dispatch.

## Decisions locked in
- Auto-apply scope: ONLY `confidence='agreed'` (both phi4 + gemma3 picked same slug). Other outcomes always require human.
- Grace period: `autoApplyDelaySeconds` (config-controlled, default 60s, smoke-test value 600s for safety).
- Audit: `ai_auto_applied=1` flag (already added by V62) set on auto-apply.
- Kill switch: live config flag — flip `enrichment.assist.mode` from `auto` → `suggest` and the auto-apply loop becomes a no-op. No restart needed if we read the config field per-loop (we will).
- Mode semantics:
  - `off` — sweeper exits at start
  - `shadow`/`suggest` — sweeper writes suggestions only (current Phase 1+2 behavior)
  - `auto` — sweeper writes suggestions AND applies eligible `agreed` rows after grace
- Topology: extend the **existing** `EnrichmentAssistSweeper` task with a second responsibility (auto-apply). One task. Single loop alternates: write a new suggestion if any pending, else try to apply an aged `agreed` suggestion. Keeps the atomic-task contract intact.

## Convention markers
- 🟢 = Sonnet-clean (mechanical, well-specified)
- 🟡 = Sonnet with Opus review of policy logic
- 🔴 = Opus (judgement calls / cross-cutting)

---

## Wave 1 — Backend (2 parallel tracks)

### Track A 🟢 — `listAutoApplyReady` repo method

**File**: `src/main/java/com/organizer3/javdb/enrichment/EnrichmentReviewQueueRepository.java`

Add:
```java
List<OpenRow> listAutoApplyReady(int limit, int minAgeSeconds);
```

- Predicate: `resolved_at IS NULL AND reason='ambiguous' AND ai_suggestion_confidence='agreed' AND ai_suggestion_slug IS NOT NULL AND ai_auto_applied=0 AND ai_suggestion_at IS NOT NULL AND (julianday('now') - julianday(ai_suggestion_at)) * 86400 >= ?`
- Ordered by `ai_suggestion_at ASC` (oldest first — they've had time to age).
- LEFT JOIN titles to match the existing `OpenRow` mapper exactly.
- Tests against in-memory SQLite:
  - Returns only rows where age ≥ minAgeSeconds AND confidence='agreed' AND auto_applied=0
  - Already-applied rows excluded
  - Wrong-outcome rows excluded (conflict, phi4_only etc.)
  - Limit honored

**~50 LOC. Sonnet in 25 minutes.**

### Track B 🟡 — `EnrichmentAutoApplier`

**File**: `src/main/java/com/organizer3/enrichment/ai/EnrichmentAutoApplier.java`

New class that handles the actual apply of one row.

```java
public class EnrichmentAutoApplier {
    public EnrichmentAutoApplier(
        EnrichmentReviewQueueRepository queueRepo,
        PickReviewCandidateTool pickTool,
        ObjectMapper objectMapper);

    /**
     * @return true if applied; false if row vanished / invalid / pick failed.
     */
    boolean apply(EnrichmentReviewQueueRepository.OpenRow row);
}
```

**Behavior**:
1. Validate: `confidence == "agreed"` and `ai_suggestion_slug != null` (defensive — should always be true by query predicate, but treat as no-op false if not).
2. Build args for `pickCandidateTool.call`: `{queue_row_id: row.id(), slug: row.aiSuggestionSlug()}`.
3. Try/catch. On success: `queueRepo.markAiAutoApplied(row.id())`. Log INFO `[ai-assist] auto-applied code={code} slug={slug}`.
4. On failure: log WARN `[ai-assist] auto-apply failed code={code}: {message}`. Do NOT mark auto_applied. Do NOT mark with error sentinel (Phase 3 keeps the row in the picker queue — same place a human would find it).
5. Return true on success, false on failure / no-op.

**Tests** in `EnrichmentAutoApplierTest`:
- Happy path: agreed row → applies → markAiAutoApplied called → returns true
- pickTool throws → returns false → markAiAutoApplied NOT called → WARN logged
- Defensive: confidence != agreed → returns false immediately, pickTool never called
- Defensive: slug null → returns false immediately

**~150 LOC. Sonnet in 45 minutes. Opus reviews the failure-handling policy (whether to mark error sentinel vs leave row for human).**

---

## Wave 2 — Sweeper integration (Track C 🟡)

**Depends on**: Wave 1.A + 1.B

**File**: `src/main/java/com/organizer3/enrichment/ai/EnrichmentAssistSweeper.java`

Extend the existing loop. Pseudocode:

```
loop:
  if isCancelled: exit
  if config.mode() == "off": log + exit

  # PHASE A: write a new suggestion if any pending
  rows = queueRepo.listOpenAwaitingAi(1)
  if !rows.empty:
    process(rows.get(0))   # existing path
    sleep(INTER_ROW_DELAY_MS)
    continue

  # PHASE B: apply an aged agreed suggestion (auto mode only)
  if config.mode() == "auto":
    aged = queueRepo.listAutoApplyReady(1, config.autoApplyDelaySeconds())
    if !aged.empty:
      autoApplier.apply(aged.get(0))
      recordAutoApplyOutcome(aged.get(0))  # day-summary counter increment
      sleep(INTER_ROW_DELAY_MS)
      continue

  # both queues empty → idle sleep
  sleep(config.sweeperIntervalSeconds() * 1000)
```

**Constructor**: gain a 4th param `EnrichmentAutoApplier autoApplier`. Existing tests need to be updated (mock injected).

**Day-summary log**: extend existing rollup with one more counter `auto_applied={N}`.

**Tests**:
- `mode=auto` with no pending suggestion writes but one aged agreed row → autoApplier.apply called, markAiAutoApplied verified via repo
- `mode=shadow` with aged agreed row → autoApplier NEVER called (auto-apply gated to `auto` mode only)
- `mode=auto` with both pending write AND aged agreed → write happens first (Phase A wins), apply on next iteration
- Cancellation respected between phases
- Day summary line includes `auto_applied` counter

**Constraints**:
- DO NOT break existing sweeper tests; update them to inject the new constructor arg
- Re-read `config.mode()` per loop iteration (so flipping the live config takes effect on next iteration without restart) — this might need confirmation that `EnrichmentAssistConfig` is read fresh; if it's snapshot-once, fix that or document the limitation

**~150 LOC. Sonnet in 1 hour. Opus reviews the live-reread question and the Phase-A-before-Phase-B priority.**

---

## Wave 3 — Wiring + smoke (Track D 🔴 + Track E human)

### Track D 🔴 — Application wiring

**File**: `src/main/java/com/organizer3/Application.java`

Adjacent to the existing AI assist construction (line ~915):

```java
com.organizer3.enrichment.ai.EnrichmentAutoApplier enrichmentAutoApplier =
        new com.organizer3.enrichment.ai.EnrichmentAutoApplier(
                enrichmentReviewQueueRepo, pickReviewCandidateTool, jsonMapper);
com.organizer3.enrichment.ai.EnrichmentAssistSweeper enrichmentAssistSweeper =
        new com.organizer3.enrichment.ai.EnrichmentAssistSweeper(
                enrichmentReviewQueueRepo, ensembleAssistCaller, assistConfig, enrichmentAutoApplier);
```

`pickReviewCandidateTool` is already constructed nearby — find the existing reference and reuse. (If it's constructed later in the file, may need to reorder — but most enrichment tools are built earlier.)

**Opus does directly. ~15 minutes.**

### Track E — Manual smoke

1. Confirm `enrichment.assist.mode: auto` + `autoApplyDelaySeconds: 600` in `organizer-config.yaml`. (600s = 10 min, gives a window to spot anything weird in logs before the first apply fires.)
2. Restart app.
3. Confirm sweeper running; wait 10+ min from any existing `agreed` suggestion timestamp.
4. Watch logs for `[ai-assist] auto-applied code=X slug=Y` lines.
5. SQL check:
   ```sql
   SELECT t.code, q.ai_suggestion_slug, q.ai_auto_applied, q.resolved_at, q.resolution
   FROM enrichment_review_queue q JOIN titles t ON t.id=q.title_id
   WHERE q.ai_auto_applied=1
   ORDER BY q.resolved_at DESC LIMIT 10;
   ```
   Should show: `ai_auto_applied=1`, `resolved_at` populated, `resolution` set to whatever PickReviewCandidateTool sets it to.
6. Cross-check: the title's `title_javdb_enrichment.javdb_slug` should now equal the AI suggestion's slug.
7. Spot-check 3-5 auto-applied rows in the UI — confirm the enrichment looks right.
8. **Kill-switch test**: flip config `mode: suggest`, save. Within one sweeper iteration (~1 min), auto-apply lines stop appearing in logs.

**Human-driven. ~30 minutes including the 10-min wait.**

---

## Schedule

```
HOUR 1
  Wave 1 parallel: [A] [B]                 (2 Sonnets, ~45m max)

HOUR 2
  Wave 2: [C] sweeper integration          (Sonnet, ~1h) + Opus review
  Wave 3.D: wiring                          (Opus, ~15m)

HOUR 3
  Wave 3.E: human smoke                    ~30m
```

**Total: ~half day wall-clock. ~80% Sonnet LOC.**

---

## Out-of-scope for Phase 3

- TranslationWorker migration to orchestrator → Phase 4
- Java post-processing rules (deterministic edge-case fixes) → Phase 4
- Backfill of historical resolved rows → Phase 4
- Bulk "undo last N auto-applies" tool → Phase 4 if needed
- UI dashboard of recent auto-applies → out (logs viewer + SQL is enough)

---

## Exit criteria for Phase 3

1. App boots with `mode: auto`, sweeper runs, eligible aged `agreed` rows get auto-applied within (delay + sweeper interval) seconds of their `ai_suggestion_at`.
2. `ai_auto_applied=1` set correctly; `resolved_at` populated by the existing pick flow.
3. The title's `title_javdb_enrichment.javdb_slug` matches the AI suggestion after auto-apply.
4. Spot-check of 5 auto-applied rows shows correct enrichment (no garbage data).
5. Kill switch verified: flipping mode away from `auto` stops auto-apply within one sweeper iteration.
6. No unhandled exceptions in logs.
7. New + existing tests passing.

Once all seven hold, **Phase 3 is done and the AI Picker Assist feature is feature-complete**. Phase 4 is plumbing/efficiency improvements, not user-facing capability.
