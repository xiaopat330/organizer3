# AI Picker Assist — Phase 4 Implementation Plan

Companion to `spec/PROPOSAL_AI_PICKER_ASSIST.md`. Phase 4 = plumbing/efficiency improvements after the user-facing feature is complete. Not user-visible.

**Estimated total**: ~1 day wall-clock with parallel dispatch.

## Decisions locked in
- All four Phase 4 tracks below are independent and ship in parallel.
- Backfill (Track C) is a one-shot operation, NOT a permanent task — runs against the existing resolved-row corpus to produce accuracy ground-truth, then never runs again.
- Translation migration (Track A) preserves byte-compatible behavior with the existing TranslationWorker — only the underlying call path swaps.
- Post-processing rules (Track B) live in Java, NOT in the prompt (per POC observation that prompt rules are blunt instruments).
- Max-attempt guard (Track D) is small but important defense — surfaced by Sonnet during Track B Phase 3.

## Convention markers
- 🟢 = Sonnet-clean (mechanical, well-specified)
- 🟡 = Sonnet with Opus review of policy logic
- 🔴 = Opus (judgement calls / cross-cutting)

---

## Wave 1 — Four parallel tracks

### Track A 🟡 — TranslationWorker migration to OllamaModelOrchestrator

**Why**: today the translation pipeline calls `HttpOllamaAdapter` directly; AI assist sweeper calls through `OllamaModelOrchestrator`. When both run concurrently, models thrash. Migrating translation through the orchestrator lets it batch — which is the whole point of having the orchestrator.

**Files** (likely): `com.organizer3.translation.TranslationWorker`, `com.organizer3.translation.Tier2BatchSweeper`. Both currently take `HttpOllamaAdapter` in their constructors and call `.generate(...)` directly.

**Change shape**:
- Replace direct `adapter.generate(request)` calls with `orchestrator.submit(model, request).get(timeoutSeconds, TimeUnit.SECONDS)`. The OllamaRequest record already knows about `formatJson` and `keepAlive` from Phase 1; nothing new in the wire format.
- Constructor changes: take `OllamaModelOrchestrator` in place of `HttpOllamaAdapter`. Update `Application.java` to pass the existing orchestrator singleton.
- Preserve the existing per-call retry / health-gate / strategy-fallback logic — only the inner generate call changes.
- The "model" string for translation tier-1 / tier-2 is already a config value; use it as the orchestrator's model key.

**Tests**:
- Existing translation worker tests must continue passing — update them to mock `OllamaModelOrchestrator` instead of `HttpOllamaAdapter`. Behavior should be identical.
- Add ONE new test demonstrating that two simultaneous translation requests for the same model do NOT cause double model-load events (just submit two `CompletableFuture`s and check `orchestrator.metrics().modelSwitches()` stayed at 0 across them).

**Constraints**:
- DO NOT break the existing translation API surface (stage-name lookup, callback dispatcher, Tier2BatchSweeper). External callers should see no change.
- Existing `HttpOllamaAdapter` instance must continue to exist — the orchestrator uses it internally. Just don't have TranslationWorker call it directly anymore.

**~200 LOC. Sonnet in ~1.5h. Opus reviews the constructor wiring + Application.java threading.**

### Track B 🟡 — Java post-processing rules

**Why**: POC observation #3 noted prompt rules are blunt instruments — they shift abstention thresholds globally instead of fixing specific edge cases. Deterministic Java rules catch known patterns precisely.

**Concrete rule set for Phase 4** (the proposal mentioned these as candidates):

1. **Bonus-version preference**: when 2+ candidates have the linked actress in cast AND one candidate's `title_original` is a strict substring of another's (typically a bonus edition with extra suffix like "BONUS" or extended cut), prefer the SHORTER (canonical) title. Apply BEFORE ensemble call OR as an override on AGREED only — start with the latter (less risky).
2. **Exact-code-in-title preference**: when 2+ candidates have the linked actress in cast AND exactly one candidate's `title_original` contains the product code verbatim, prefer that candidate.
3. **Empty-cast deprioritization**: when ≥1 candidate has cast populated and a different candidate has empty cast, treat the empty-cast candidate as ineligible (effectively remove from contention) before ensemble call.

**File**: new `com.organizer3.enrichment.ai.PostProcessingRules` class.

**API**:
```java
public class PostProcessingRules {
    /** Apply pre-ensemble filters; return possibly-reduced candidate list. */
    public List<Candidate> prefilterCandidates(EnrichmentReviewQueueRepository.OpenRow row,
                                                List<Candidate> candidates);

    /** Apply post-ensemble overrides on an AssistResult; return possibly-updated result. */
    public AssistResult applyOverrides(EnrichmentReviewQueueRepository.OpenRow row,
                                        AssistResult original,
                                        List<Candidate> candidates);
}
```

The pre-filter applies rules 3 + 2 (deterministic removal / preference). The post-override applies rule 1 (bonus version) — only when result is already `agreed` and the override would change the slug, log INFO and emit the new result with confidence `agreed_with_override` (new outcome value — extend the documented set in `setAiSuggestion` javadoc).

**Integration point**: `EnsembleAssistCaller` becomes a 2-pass call: prefilter, ensemble, override.

**Tests**:
- One test per rule (pre-filter and post-override), with explicit fixtures
- A test confirming a row with NO applicable rule passes through unchanged
- A test confirming `agreed_with_override` is a distinct outcome surfaced in the AssistResult

**Constraints**:
- These are conservative rules. If any rule changes behavior on a row, the change MUST be logged at INFO so we can audit. The corpus already shows 95%+ agreed; we don't want to break that.
- Add a config flag `postProcessingEnabled: boolean` (default true) so we can disable the whole layer if it regresses anything in production.

**~250 LOC. Sonnet in ~1.5h. Opus reviews each rule's precondition logic carefully.**

### Track C 🟢 — Backfill on historical resolved rows

**Why**: Phase 1 smoke only measured agreement on currently-open rows. To get a real accuracy number across the historical corpus (rows the human already resolved), we backfill AI suggestions on already-resolved rows and compare against `title_javdb_enrichment.javdb_slug` (the ground truth).

**This is a one-shot operation**, not a permanent task. Build it as a separate Task in TaskRegistry so it can be triggered from Utilities once, produces a report, then stays unused.

**File**: new `com.organizer3.utilities.task.javdb.AiAssistBackfillTask` (matches existing task package convention).

**Behavior**:
1. Query: every `enrichment_review_queue` row WHERE `resolved_at IS NOT NULL AND reason='ambiguous'` AND a `title_javdb_enrichment` row exists for the same title_id.
2. For each row: rebuild the candidate context from the persisted `detail` JSON (it's already there for resolved rows), build the AssistPromptBuilder.Input, call `EnsembleAssistCaller.evaluate(...)`.
3. Write the resulting suggestion to the SAME `ai_suggestion_*` columns on the resolved row (overwriting any previous, since these rows weren't touched by the sweeper).
4. Mark with a sentinel: extend `setAiSuggestion` to accept an `isBackfill` flag, or just use a separate column? **Decision**: reuse `ai_suggestion_*`; backfill rows are distinguishable because `resolved_at IS NOT NULL`. Don't add new columns.
5. Emit a final report log line + a JSON file at `data/ai-assist-backfill-{date}.json` with: per-outcome counts, match-vs-ground-truth counts, list of mismatches with slugs (for spot-check audit).

**Operational constraints**:
- Task respects cancellation
- Atomic-task rule (one Utilities task at a time) is fine — backfill is single-pass
- Rate-limited at the orchestrator level naturally (no special handling)

**Tests**:
- A small test with 5 fixture resolved rows: 3 where AI agrees with the human, 1 conflict, 1 mismatch (different slug than human). Assert the report counts match.

**~200 LOC. Sonnet in ~1.5h.**

### Track D 🟢 — Max-attempt guard on auto-apply failures

**Why**: per Sonnet's Phase 3 Track B note — a persistently failing pickTool slug could loop-retry every iteration. No incident seen in smoke (0 errors in 259 applies) but worth a guard.

**Files**: `EnrichmentReviewQueueRepository` (schema-touched), `EnrichmentAutoApplier`.

**Approach**:
- Add column `ai_auto_apply_attempts INTEGER DEFAULT 0` (V63 migration). Idempotent.
- On apply failure, `EnrichmentAutoApplier` increments the column.
- `listAutoApplyReady` predicate gains `AND ai_auto_apply_attempts < ?` (e.g. 3).
- Configurable max via `EnrichmentAssistConfig` (`maxAutoApplyAttempts: int`, default 3).
- After max attempts, the row stays in the queue (visible to human via picker), just won't be retried by auto-apply.

**Tests**:
- After 3 failed apply calls, the row is excluded from `listAutoApplyReady`
- The first 3 attempts increment the counter and the row remains eligible at attempt counts 0, 1, 2

**~80 LOC. Sonnet in ~45min.**

---

## Wave 2 — Verification

### Track E 🔴 — Smoke + audit (after Wave 1 lands)

1. Restart the app.
2. Confirm translation pipeline still works end-to-end (kick a stage-name translation or two; verify outputs match historical behavior).
3. Re-enable AI assist (`mode: auto`), kick the sweeper.
4. Trigger the backfill task. Read the produced report. Confirm accuracy number is in the expected range (POC was 100% on picked rows; mismatches reveal interesting cases worth follow-up).
5. Try to provoke a max-attempt scenario: temporarily corrupt one row's `ai_suggestion_slug` to a non-existent slug, force the sweeper to attempt apply, watch the attempts counter grow.
6. Verify post-processing rules are firing: log lines should show `agreed_with_override` outcome for any row hitting rule 1.

**Human-driven. ~45 minutes including the backfill run on the resolved-row corpus (~2,500 historical resolved rows × ~25 s/call = potentially long — may want to subset).**

---

## Schedule

```
HALF-DAY 1
  Wave 1 parallel: [A] [B] [C] [D]       (4 Sonnets, ~1.5h max)
                                          + Opus review on A/B at end

HALF-DAY 2 (or same day)
  Wave 2: [E] human smoke + backfill     ~45m–1.5h depending on corpus size
```

**Total: ~1 day wall-clock. ~85% Sonnet LOC.**

---

## Out-of-scope for Phase 4 (final scope guard)

- Any new user-facing UI — the feature is feature-complete after Phase 3
- A separate "AI Picks audit" dashboard — backfill report JSON is enough
- Multi-language post-processing rules — Phase 4 ships only the 3 rules listed
- A bulk-undo tool for auto-applied rows — picker can re-queue individually; bulk would only matter if there's a systemic regression, which there isn't
- Changing the orchestrator API — translation just plugs into the existing `submit(model, request)`

---

## Exit criteria for Phase 4

1. TranslationWorker calls flow through OllamaModelOrchestrator; existing translation tests pass.
2. Two concurrent translation requests for the same model show ≤1 model switch in orchestrator metrics.
3. PostProcessingRules layer integrated; ≥1 INFO log line on any row a rule fires for.
4. Backfill task runs end-to-end on resolved corpus, produces a JSON report with per-outcome and match counts.
5. Max-attempt guard: ai_auto_apply_attempts column added; row excluded from listAutoApplyReady after maxAutoApplyAttempts.
6. No regressions in existing test suite.
7. No regressions in live behavior (kick the sweeper once + a translation; both work normally).

Once all seven hold, **Phase 4 is done and the AI Picker Assist feature is fully complete (user-facing + plumbing).** Branch is ready to merge to main.
