# AI Picker Assist — Phase 1 Implementation Plan

Companion to `spec/PROPOSAL_AI_PICKER_ASSIST.md`. Phase 1 = backend + shadow mode, no UI changes.

**Estimated total**: ~1.5 days wall-clock with parallel dispatch.

## Convention markers
- 🟢 = Sonnet-clean (mechanical, well-specified)
- 🟡 = Sonnet with Opus review of policy logic
- 🔴 = Opus (wiring, judgement calls)

---

## Wave 1 — Foundation (3 parallel tracks, independent)

### Track A 🟢 — Schema migration

- New `SchemaUpgrader.applyV<next>()` method
- `ALTER TABLE enrichment_review_queue` adding columns:
  - `ai_suggestion_slug TEXT`
  - `ai_suggestion_confidence TEXT` (one of: `agreed`, `phi4_only`, `gemma_only`, `conflict`, `both_abstain`)
  - `ai_suggestion_reason TEXT`
  - `ai_suggestion_at TEXT` (ISO-8601)
  - `ai_auto_applied INTEGER DEFAULT 0`
- Idempotent — `IF NOT EXISTS` or columninfo-driven guard
- Schema test verifying columns exist after upgrade

**~30 LOC. Sonnet dispatches in 15 minutes.**

### Track B 🟢 — `HttpOllamaAdapter` `formatJson` flag

- Add `formatJson` boolean field to `OllamaRequest` record (default false)
- In `HttpOllamaAdapter.generate`, when `formatJson=true`, set top-level `body.put("format", "json")`
- Unit tests covering both flags + back-compat (existing translation calls unchanged)
- The sandbox's `JsonModeOllamaClient` becomes redundant but DO NOT delete it yet (referenced by sandbox POC code)

**~80 LOC including tests. Sonnet dispatches in 30 minutes.**

### Track C 🟢 — POC port: prompt, result record, config

- Port `_sandbox/ollama-picker-poc/.../PickerPromptBuilder.java` → `com.organizer3.enrichment.ai.AssistPromptBuilder` (verbatim port of system + user prompts including kanji-bridge rule)
- New `AssistResult` record (`outcome`, `confidence`, `suggestedSlug`, `reason`, `phi4Pick`, `gemmaPick`)
- New `EnrichmentAssistConfig` record under `com.organizer3.config`:
  - `mode: String` (`off` | `shadow` | `suggest` | `auto`)
  - `primaryModel: String` (default `phi4`)
  - `secondaryModel: String` (default `gemma3:12b`)
  - `sweeperIntervalSeconds: int` (default 60)
  - `autoApplyDelaySeconds: int` (default 60)
  - `promptVersion: String` (default `v7-kanji-bridge` — tracked for telemetry)
- Update `AppConfig` to load `enrichment.assist` YAML block
- Tests for config loading (valid + missing block defaults to off-mode)

**~150 LOC. Sonnet dispatches in 45 minutes.**

---

## Wave 2 — Building blocks (2 parallel tracks, depend on Wave 1)

### Track D 🟢 — Repository methods

**Depends on**: Wave 1.A (schema)

- `EnrichmentReviewQueueRepository`:
  - `void setAiSuggestion(long queueRowId, String slug, String confidence, String reason, Instant at)` — UPDATE
  - `List<QueueRow> listOpenAwaitingAi(int limit)` — open ambiguous rows where `ai_suggestion_at IS NULL`
  - `void markAiAutoApplied(long queueRowId)` — sets `ai_auto_applied=1`
- Tests against in-memory SQLite (matches existing repo test pattern)
- Verify CRUD round-trip with all 5 new columns

**~100 LOC including tests. Sonnet in 30 minutes.**

### Track E 🟡 — `OllamaModelOrchestrator`

**Depends on**: Wave 1.B (`formatJson` flag exists)

- New package `com.organizer3.ollama`
- `OllamaModelOrchestrator` (singleton-friendly, but not strictly singleton)
- API:
  ```java
  CompletableFuture<OllamaResponse> submit(String model, OllamaRequest request);
  ```
- Internal state:
  - `Map<String, BlockingDeque<WorkItem>>` keyed by model
  - `String activeModel` (volatile)
  - `Instant activeModelStartedAt`
  - Per-model `Lock` for serialization
- Scheduling thread:
  - Loop: pick active model = whichever non-empty queue has most items (or stay on current if queue still has work)
  - Switch when: current queue empty, OR `now - activeModelStartedAt > maxModelDurationSeconds` (default 600s = 10 min fairness cap)
  - Within a model: serially process work items, calling `HttpOllamaAdapter.generate` with `keep_alive: "15m"` and `formatJson=true`
- Tests:
  - Submit 100 items to one model → all complete in order, one model load
  - Submit alternating models → orchestrator batches them, NOT round-robin
  - Fairness: 1 item on model B while 1000 on model A → B is reached within max-duration
  - Exception in one call doesn't poison subsequent items

**~250 LOC. Sonnet in ~1h. Opus reviews the switch-policy logic explicitly.**

---

## Wave 3 — Feature wiring (2 sequential tracks)

### Track F 🟡 — `EnsembleAssistCaller`

**Depends on**: Wave 1.A/B/C, Wave 2.E

- Class in `com.organizer3.enrichment.ai`
- API:
  ```java
  AssistResult evaluate(EnrichmentReviewQueueRow row);
  ```
- Logic:
  1. Build prompt via `AssistPromptBuilder`
  2. Submit to orchestrator for `primaryModel` (phi4)
  3. Wait, parse JSON, extract `(pickIndex, confidence, reason)`
  4. Submit same prompt to `secondaryModel` (gemma3)
  5. Wait, parse, extract
  6. Apply voting policy:
     - Both picked same valid index → `agreed`, suggestedSlug = candidates[index-1].slug
     - Phi4 picked valid, gemma abstained → `phi4_only`, suggestedSlug = phi4's pick
     - Gemma picked valid, phi4 abstained → `gemma_only`, suggestedSlug = gemma's pick
     - Both abstained → `both_abstain`, suggestedSlug = null
     - Both picked, different indices → `conflict`, suggestedSlug = null
     - Out-of-range index from either → treat as abstain for that model
- Tests covering all 5 voting outcomes with mocked orchestrator + JSON responses

**~150 LOC. Sonnet in 45 minutes. Opus reviews the voting truth table.**

### Track G 🟡 — `EnrichmentAssistSweeper`

**Depends on**: Wave 1.C, Wave 2.D, Wave 3.F, existing `TaskRunner`

- Implements the existing utility task interface (look at any current utility task as the template)
- Loop:
  1. If `mode == "off"`, exit task
  2. `repo.listOpenAwaitingAi(1)` → one row at a time (atomic-operations rule)
  3. `caller.evaluate(row)` → `AssistResult`
  4. `repo.setAiSuggestion(row.id, result.suggestedSlug, result.confidence, result.reason, Instant.now())`
  5. Log at INFO level (visible in logs viewer): `[ai-assist] {code} → {confidence} ({reason[:60]})`
  6. Brief sleep (configurable; default 1s) between rows
- Honors task cancellation/preemption per TaskRunner contract
- Tests verifying mode-gating and persistence side-effect

**~200 LOC. Sonnet in 1 hour. Opus reviews TaskRunner integration.**

---

## Wave 4 — Integration & ops (Opus + manual)

### Track H 🔴 — Application wiring

- Update `Application.java`:
  - Construct `OllamaModelOrchestrator` (one singleton)
  - Wire it into `EnsembleAssistCaller`
  - Wire caller into `EnrichmentAssistSweeper`
  - Register sweeper with `TaskRunner`
  - Add menu item / discoverable surface in Utilities for starting the sweeper
- Build `enrichment.assist` config field threading from YAML → records → injected into above

**Opus does this directly. ~1 hour.**

### Track I 🟡 — Auto-pull bootstrap

- New `OllamaModelBootstrap` class:
  - `boolean ensureModelsReady(List<String> models)` — calls `/api/tags`, pulls any missing via streaming `/api/pull`, surfaces progress to logs
  - Called on mode flip from `off` → anything via a config-watcher (or simply on app start if mode is non-off)
  - If Ollama unreachable: log + return false, leave assist mode effectively-disabled, log a clear "AI assist unavailable" warning in the logs viewer
- Tests for the happy path + failure paths (network error, partial pull, disk full)

**~150 LOC. Sonnet writes; Opus reviews error paths. ~45 minutes.**

### Track J 🟢 — Telemetry / logging

- Add metric counters to orchestrator (model-load count, per-model latency)
- Log at INFO on all critical paths so behavior is visible in logs viewer
- Daily log line summarizing: items processed today, by confidence band
- No new dashboards — surface via existing logs viewer

**~50 LOC. Sonnet in 20 minutes.**

### Track K — Manual smoke test

- Set `mode: shadow` in config
- Start app
- Verify model auto-pull (or manual pull if already present)
- Start sweeper from Utilities menu
- Watch logs viewer for 10 minutes
- Spot-check 5 random rows from DB: `SELECT code, ai_suggestion_confidence, ai_suggestion_reason FROM enrichment_review_queue WHERE ai_suggestion_at IS NOT NULL ORDER BY ai_suggestion_at DESC LIMIT 5`
- Confirm 0 errors, suggestions look plausible

**Human-driven. ~30 minutes.**

---

## Schedule

```
HALF-DAY 1
  Morning  ─ Wave 1 parallel: [A] [B] [C]       (3 Sonnets, ~2h max)
  Afternoon ─ Wave 2 parallel: [D] [E]           (2 Sonnets, ~3h max)
                                                  + Opus reviews E switch-policy

HALF-DAY 2
  Morning   ─ Wave 3 sequential: [F] then [G]    (Sonnet ~2h)
                                                  + Opus reviews F voting policy
  Afternoon ─ Wave 4: [H] (Opus) + [I] (Sonnet) + [J] (Sonnet) + [K] (human)
                                                  ~3h
```

**Total: ~1.5 days wall-clock, ~80% Sonnet LOC, ~20% Opus oversight.**

---

## Out-of-scope for Phase 1 (don't let scope creep in)

- Any v2 picker UI change → Phase 2
- Any auto-apply path → Phase 3
- TranslationWorker refactor to use orchestrator → Phase 4
- Java post-processing rules → Phase 4+
- Backfill of historical rows → Phase 4+
- Stratified per-label re-eval → Phase 4+

---

## Exit criteria for Phase 1

1. App boots with `mode: off` and no regression (verify by running existing app smoke test)
2. App boots with `mode: shadow`, sweeper running, logs show suggestions arriving
3. After ≥100 suggestions accumulated:
   - ≥80% of rows are confidence `agreed` (matches POC ratio)
   - 0 hard contradictions encountered (`conflict` rate < 2%)
   - Model-load count in orchestrator log < N/2 (where N is items processed) — proves batching is working
   - No unhandled exceptions in logs
4. Daily summary log line present and accurate
5. New tests passing; existing tests passing; coverage at or above current baseline

Once all five hold, Phase 1 is done and we can decide Phase 2 timing.
