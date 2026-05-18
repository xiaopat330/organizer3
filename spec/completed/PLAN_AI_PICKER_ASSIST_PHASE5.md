# AI Picker Assist — Phase 5 Implementation Plan

Companion to `spec/PROPOSAL_AI_PICKER_ASSIST.md`. Phase 5 = orchestration throughput improvements. Not user-visible; safety-preserving (zero new wrong picks).

**Estimated total**: Track A ~1 hour. Track B ~2 hours (gated; ship-but-disabled).

## Decisions locked in
- **Track A (batched backfill)** ships unconditionally. No flag, no memory risk — batching is within one model at a time.
- **Track B (parallel ensemble per row)** ships behind `enrichment.assist.parallelEnsemble: boolean` default `false`. Sweeper + backfill share the code path; both opt in via the same flag. Runtime memory guard provides safety fallback when both-models-resident pressure exceeds a configurable budget.
- **No model-skip optimization (#4 from the discussion)**. The current 0-wrong-pick safety property is more valuable than the ~50% inference savings the skip would buy.
- **No translation pipeline changes**. Translation uses `gemma4:e4b` + `qwen2.5:14b`; picker uses `phi4` + `gemma3:12b`. Four distinct models — no cross-subsystem sharing planned. Translation models stay validated against translation quality.

## Convention markers
- 🟢 = Sonnet-clean (mechanical, well-specified)
- 🟡 = Sonnet with Opus review of policy logic
- 🔴 = Opus (judgement calls / cross-cutting)

## Why these two and not the others

| option | shipping? | reason |
|---|---|---|
| #2 batch-by-model (backfill) | **Track A — ship** | throughput win, zero safety cost |
| #1 parallel ensemble per row | **Track B — ship gated** | per-row latency win; memory tradeoff requires runtime guard |
| #3 fix translation models | **NOT shipping** | would risk regressing translation quality for marginal orchestrator wins |
| #4 phi4-solo fast-path | **NOT shipping** | trades 0-wrong-picks for ~1% wrong picks; safety regression dominates the throughput win |
| #5 pipelined ensemble | **NOT shipping** | small gain; complexity not worth it until profiling proves a bottleneck |

---

## Track A 🟢 — Batched backfill

**Goal**: cut `AiAssistBackfillTask` wall-clock time from ~8h to ~4–5h on the 1146-row corpus by processing N rows of phi4 sequentially, then N rows of gemma3 sequentially, instead of phi→gem→phi→gem per row.

### Files

- `src/main/java/com/organizer3/utilities/task/javdb/AiAssistBackfillTask.java`
- `src/main/java/com/organizer3/enrichment/ai/EnsembleAssistCaller.java` — extract `vote(phi4Pick, gemmaPick, …)` into a public static helper so the backfill can reuse the voting logic without going through the per-row `evaluate(...)` path
- `src/main/java/com/organizer3/config/EnrichmentAssistConfig.java` — add `backfillBatchSize: int` default 20

### Design

Replace the per-row loop in the backfill task with a batched loop:

```
for each chunk of N rows:
  inputs[i] = build AssistPromptBuilder.Input for row[i]            # cheap, in-memory
  // PHASE 1 — submit N phi4 prompts
  phi4Futures[i] = orchestrator.submit(primaryModel, buildRequest(inputs[i]))
  // wait for all phi4
  phi4Picks[i] = parseIndex(phi4Futures[i].get(...))
  // PHASE 2 — submit N gemma3 prompts
  gemmaFutures[i] = orchestrator.submit(secondaryModel, buildRequest(inputs[i]))
  // wait for all gemma3
  gemmaPicks[i] = parseIndex(gemmaFutures[i].get(...))
  // PHASE 3 — vote + persist
  for each i:
    result[i] = EnsembleAssistCaller.vote(phi4Picks[i], gemmaPicks[i], candidates[i], confs, reasons)
    setAiSuggestion(row[i].id, result[i].slug, result[i].outcome, result[i].reason, now)
    accumulate counter
  check cancellation between chunks
```

### Voting helper extraction

`EnsembleAssistCaller` currently has the voting logic inline in `evaluate()`. Extract it:

```java
public static AssistResult vote(
    Integer phi4Pick, String phi4Confidence, String phi4Reason,
    Integer gemmaPick, String gemmaConfidence, String gemmaReason,
    List<AssistPromptBuilder.Candidate> candidates);
```

`evaluate(...)` calls `vote(...)` after collecting both responses. Backfill calls it directly. Existing tests stay green.

### Tests

- `AiAssistBackfillTaskTest` gets a new test asserting that when 7 rows are batched with `batchSize=3`, the orchestrator receives exactly 3+3+1 phi4 submissions then 3+3+1 gemma3 submissions (or close — assert on grouping behavior).
- A test for the extracted `EnsembleAssistCaller.vote()` static helper covering each of the 5 outcomes.
- An integration test against in-memory SQLite + mocked orchestrator with 5 rows + batch size 2, verifying the same final counters as the row-at-a-time path produces.

### Constraints

- DO NOT change sweeper behavior — it stays per-row (latency-sensitive)
- DO NOT remove the per-row `evaluate(...)` API; both must work
- Default batch size 20; allow user override via `enrichment.assist.backfillBatchSize`
- Final JSON report shape unchanged
- Run targeted tests: `./gradlew test --tests '*AiAssistBackfill*' --tests '*EnsembleAssistCaller*' --rerun`

**~150 LOC. Sonnet in ~1 hour.**

---

## Track B 🟡 — Parallel ensemble per row (gated)

**Goal**: when explicitly enabled and memory headroom permits, run phi4 and gemma3 concurrently for the same row. Halves per-row wall-clock when both models are warm-resident.

### Default behavior

`enrichment.assist.parallelEnsemble: false` — feature is OFF by default. Code ships unused. Existing serial path remains the production behavior. Flipping the flag to `true` (and restarting) enables parallel mode.

### Files

- `src/main/java/com/organizer3/config/EnrichmentAssistConfig.java`:
  - Add `parallelEnsemble: boolean` default `false`
  - Add `parallelEnsembleMemoryBudgetMB: int` default 22000 (22 GB; conservative for 26 GB Macs — leaves ~4 GB for OS + apps)
- `src/main/java/com/organizer3/enrichment/ai/EnsembleAssistCaller.java`:
  - When `parallelEnsemble=true` AND memory check passes: submit both futures, `CompletableFuture.allOf(...).get(timeout)`, then vote
  - When `parallelEnsemble=true` AND memory check FAILS: log WARN one-line `[ai-assist] parallel disabled (memory pressure {N}MB >budget); falling back to serial`, run the existing serial path for this row
  - When `parallelEnsemble=false`: run serial path (no change)
- `src/main/java/com/organizer3/ollama/OllamaModelOrchestrator.java`:
  - New `int currentLoadedModelMb()` — queries `/api/ps`, sums each loaded model's `size_vram` (Ollama reports bytes; convert to MB)
  - Caches result for 30s to avoid hammering Ollama with status calls

### Behavior under the memory guard

The memory guard is per-row, not per-app:
- Before submitting parallel: check `orchestrator.currentLoadedModelMb()`
- If `<= parallelEnsembleMemoryBudgetMB`: proceed with parallel submission
- If `>`: fall back to serial for this row, log WARN
- The guard self-resolves: once parallel finishes one row, that row drops back; next row will succeed if pressure relaxed

### Memory math (for context, not enforced)

- phi4 (14B): ~9 GB VRAM when loaded
- gemma3:12b: ~8 GB VRAM
- Both simultaneously: ~17 GB
- Translation models (if loaded too): `gemma4:e4b` ~5 GB, `qwen2.5:14b` ~9 GB
- Worst case (all four loaded simultaneously on 26 GB Mac): ~31 GB → OOM
- Default budget 22 GB means parallel-ensemble fires only when there's ≤22 GB of model load — naturally prevents the 4-model worst case

### Tests

- `EnsembleAssistCallerTest`:
  - Parallel path: both futures submitted before either is awaited, `allOf` completes, vote runs the same as serial
  - Parallel path memory-pressure fallback: when orchestrator reports current load above budget, the test verifies serial path was used instead (mock the orchestrator's `currentLoadedModelMb` to return high value)
- `OllamaModelOrchestratorTest`:
  - `currentLoadedModelMb` parses `/api/ps` JSON shape correctly; sums multiple loaded models; returns 0 when no models loaded
  - Caches result (second call within 30s does not hit HTTP)

### Sweeper + backfill integration

Both call `EnsembleAssistCaller.evaluate(...)` — the flag-gated behavior is inside `evaluate()`, so:
- Sweeper opts in automatically when `parallelEnsemble=true`
- Backfill opts in automatically when `parallelEnsemble=true` (within each batch, both phi4 and gemma3 submissions for a single row can run concurrently — Track A's batch loop would need a small adjustment to overlap the two phases when the flag is on; OK to defer that integration to a follow-up if Track A ships first and parallelism is opted into later)

### Constraints

- DO NOT auto-disable the flag based on runtime memory; the guard falls back per-row but the flag itself is durable
- DO NOT change the OllamaRequest / orchestrator API beyond adding `currentLoadedModelMb`
- DO NOT make memory pressure check block translation calls or any non-ensemble caller — `EnsembleAssistCaller` is the only consumer of the guard
- Run targeted tests: `./gradlew test --tests '*EnsembleAssistCaller*' --tests '*OllamaModelOrchestrator*' --tests '*EnrichmentAssistConfig*' --rerun`

**~150 LOC. Sonnet in ~1.5 hours. Opus reviews the memory-guard semantics and the cache-staleness window.**

---

## Wave 2 — Smoke

### Track C — Track A smoke (human)

1. Restart app (config additions and code changes).
2. Trigger backfill task. Watch logs.
3. Spot-check first batch:
   - INFO log mentions batch boundaries
   - Orchestrator switch count grows by ~1 per batch instead of ~2 per row
4. Let it run ~10 min → confirm faster rate than the ~25 s/row baseline (target ~15–18 s/row).
5. Cancel after enough sample, inspect partial report — counts should match expected.

Acceptance: batched backfill is measurably faster than per-row baseline, no errors, report JSON shape unchanged.

### Track D — Track B smoke (human, optional)

Only if you want to validate parallel mode:

1. Set `enrichment.assist.parallelEnsemble: true` in config.
2. Set `enrichment.assist.parallelEnsembleMemoryBudgetMB` based on what `ollama ps` shows for your typical loaded-model bytes.
3. Restart app.
4. Trigger sweeper or backfill.
5. Watch logs for `[ai-assist] parallel disabled` lines (means guard fired). If they fire constantly, budget is too tight or other apps are competing — raise the budget or close apps.
6. Compare per-row wall-clock vs Track A baseline.

Acceptance: parallel mode runs without OOM, falls back gracefully when guard fires, per-row time approximately halved when active.

If Track D is skipped, the flag stays off and code is dormant — zero risk.

---

## Schedule

```
HOUR 1
  Track A (Sonnet, ~1h)                  ship + commit + push

HOUR 2–3
  Track B (Sonnet, ~1.5h)                ship + commit + push  (flag default false)
                                          + Opus reviews memory guard

WHEN READY
  Track C smoke                          ~15m
  Track D smoke (optional)               ~30m
```

**Total: ~half day if you do both. ~85% Sonnet LOC.**

---

## Out-of-scope for Phase 5

- Model-skip / phi4-solo paths → not shipping (safety regression)
- Translation orchestration changes → not shipping (separate validation requirements)
- Cross-task concurrency (relaxing the atomic-task rule for read-only paths) → defer
- Adaptive batch sizing based on memory pressure → defer; static `backfillBatchSize` is fine
- A UI surface for the parallel-ensemble toggle → defer; YAML edit + restart is fine for an advanced knob

---

## Exit criteria for Phase 5

1. Track A: batched backfill task runs end-to-end, processes the full 1146-row corpus in ≤6 h (vs ~8 h baseline); JSON report produced; orchestrator switch count is ≤(2 × ceil(rows/batchSize)) instead of (2 × rows).
2. Track B (ONLY if enabled): with flag on, parallel mode runs without OOM; memory guard correctly falls back when pressure exceeds budget; per-row time halves when active.
3. Sweeper behavior is unchanged when flag is off (default).
4. All existing tests pass; new tests added for both tracks pass.
5. Translation pipeline behavior unchanged.

Once 1, 3, 4, 5 hold (Track B's 2 is optional), **Phase 5 is done**. The feature is fully optimized within the safety envelope.
