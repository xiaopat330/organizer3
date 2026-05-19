# AI Picker Assist — Initial Proposal

**Status**: Draft for discussion. Not approved. POC complete; design open for redesign by user.

## TL;DR

POC validates that a local-LLM ensemble (phi4 + gemma3:12b, both Metal-accelerated on M5) can **auto-resolve ~87% of ambiguous javdb enrichment results with zero blunders** (N=200 measured). The remaining ~13% are punted to the existing human picker.

Proposal: wire this ensemble into the enrichment pipeline as an "AI assist" layer in front of the existing picker workflow. Shadow-mode first, opt-in auto-apply later.

POC artifacts: `_sandbox/ollama-picker-poc/` (driver + OBSERVATIONS.md).

## What the POC proved

| Property | Status | Evidence |
|---|---|---|
| Auto-resolution is feasible | Yes | 87.5% strict-ensemble agreement at N=200 |
| Safety holds at scale | Yes | 0 hard contradictions where both models picked wrong slug; 0 blunders under strict policy |
| Latency is bounded | Yes | ~8s/call/model on Metal; ensemble = 2 parallel calls = ~10s/row total |
| Hardware fits | Yes | Both models load into 26GB unified memory simultaneously |
| Failure mode is safe | Yes | Disagreement → fall back to existing human picker |

## What the POC did NOT prove

- Performance on **labels/genres outside the sampled distribution** — the 200 sampled rows came from a mix of labels but not a stratified sample. SOD-heavy, less coverage on niche labels.
- Performance on **the currently-open 306 ambiguous rows** — the eval set was historical (already-resolved). Snapshots and javdb schema may have drifted.
- **Long-term stability** — javdb output format, model versions, and the enrichment pipeline all evolve.

## Scope of this proposal

### In scope
- Adding an ensemble caller that processes ambiguous enrichment queue rows
- Storing AI suggestions on the queue row alongside the existing detail snapshot
- Surfacing AI suggestions in the picker UI
- An opt-in "auto-apply when strict ensemble agrees" mode

### Explicitly out of scope (for v1)
- Replacing the human picker (kept as fallback)
- Multi-model orchestration beyond the 2-model ensemble
- Real-time UI calls (ensemble takes ~10s; this is a background job, not a render-blocking call)
- Java post-processing rules for known patterns (e.g., bonus-version detection)
- Model auto-update / version pinning

## Design

### Model scheduling: shared orchestrator

**Key constraint**: 26GB unified memory holds ~2 models at a time. Across this feature + the existing translation pipeline we'll have ≥4 distinct models in play (phi4, gemma3:12b, gemma4:e4b, qwen2.5:14b). Naive per-item processing would pay 5–10s of model-load overhead on every model switch — potentially ~40s/row across an ambiguous→translation pipeline. With 300+ items in the queue this dominates throughput.

**Solution**: a shared `OllamaModelOrchestrator` (singleton) that all Ollama callers submit work through. It picks one model to be "active," drains all queued work for that model, then switches.

```
┌─────────────────────────────────────────────────────────┐
│  OllamaModelOrchestrator (singleton, NEW)               │
│                                                         │
│  active_model: phi4                                     │
│  queues:                                                │
│    phi4         [item_A, item_C, item_F]   ← processing │
│    gemma3:12b   [item_A, item_C, item_F]                │
│    gemma4:e4b   [item_B, item_D, item_E]                │
│    qwen2.5:14b  []                                      │
│                                                         │
│  switch_policy:                                         │
│    - drain current queue, OR                            │
│    - max wall-time on one model (fairness)              │
└─────────────────────────────────────────────────────────┘
       ▲                                  ▲
       │ submit(phi4, picker_payload)     │ submit(gemma4, trans_payload)
       │                                  │
┌──────┴──────────────┐         ┌────────┴────────────┐
│ EnrichmentAssist    │         │ TranslationWorker   │
│ Sweeper (NEW)       │         │ (existing — keeps   │
│ uses orchestrator   │         │  direct calls v1)   │
└─────────────────────┘         └─────────────────────┘
```

**Pipeline staging (multi-pass)**: each work item carries its "remaining model passes." An ambiguous queue row enters with `[phi4, gemma3]` to traverse. Translation items enter with `[gemma4]` (plus `qwen2.5:14b` if primary refuses). After a model finishes an item, the orchestrator re-queues it to its next model in the pass list. The orchestrator itself doesn't model pipelines — it just sees model queues and drains them in the most cache-friendly order.

**Concrete win** for a batch of 50 ambiguous rows arriving together:
- *Without orchestrator*: phi4-load, call, gemma3-load, call (per row) → ≥1000s of load overhead
- *With orchestrator*: phi4-load, 50× call, gemma3-load, 50× call → ~16s of load overhead total

**Ollama keep_alive**: orchestrator sets `keep_alive: "15m"` per request (Ollama default is 5m which is too short for ~7-minute batches). This is per-request, not env-var, so no service restart needed.

**Integration policy** (per design decision):
- v1: orchestrator is built as part of this feature; AI assist uses it from day 1
- TranslationWorker keeps its current direct `HttpOllamaAdapter` calls until a follow-up refactor
- The orchestrator is built with translation migration in mind — its API mirrors enough of `OllamaAdapter` that the switch is mechanical when ready
- Without translation participating, the win is reduced (only intra-AI-assist batching benefits), but the architecture is in place

**Why not merge translation + picker into one queue**: they're conceptually different work units with different SLAs and retry semantics. Translation is closer to user-facing; picker is "whenever." Keeping queues separate preserves independent on/off, independent telemetry, and independent retry policy. What gets shared is the *scheduler*, not the *work*.

### Component placement

```
com.organizer3.ollama/          (NEW package — shared infrastructure)
  OllamaModelOrchestrator       — singleton scheduler, model-affinity policy
  ModelWorkItem (record)        — payload + remaining-model-passes + completion callback
  ActiveModelLease              — RAII-style lock guaranteeing model is loaded

com.organizer3.enrichment.ai/   (NEW package — feature-specific)
  EnsembleAssistCaller          — submits two-stage work (phi4 → gemma3) to orchestrator
  AssistPromptBuilder           — system + user prompt (lifted from POC)
  AssistResult (record)         — outcome, confidence, suggested slug, reasoning
  EnrichmentAssistSweeper       — periodic scan; submits unresolved rows to caller

com.organizer3.translation/     (production change)
  HttpOllamaAdapter             — add formatJson flag to OllamaRequest (back-compat default false)

com.organizer3.enrichment/
  EnrichmentReviewQueueRepository
    + setAiSuggestion(...)      — persist the suggestion
    + listOpenAwaitingAi(...)   — find rows without a suggestion yet
```

Note: the sandbox's `JsonModeOllamaClient` becomes unnecessary once `HttpOllamaAdapter` supports `formatJson`. POC code can be retired.

### Schema additions

```
ALTER TABLE enrichment_review_queue ADD COLUMN ai_suggestion_slug TEXT;
ALTER TABLE enrichment_review_queue ADD COLUMN ai_suggestion_confidence TEXT;  -- 'agreed' | 'phi4_only' | 'gemma_only' | 'conflict' | 'both_abstain'
ALTER TABLE enrichment_review_queue ADD COLUMN ai_suggestion_reason TEXT;      -- short rationale
ALTER TABLE enrichment_review_queue ADD COLUMN ai_suggestion_at TEXT;          -- ISO-8601 timestamp
ALTER TABLE enrichment_review_queue ADD COLUMN ai_auto_applied INTEGER DEFAULT 0;  -- 1 if the suggestion was used to resolve
```

Backfill: NULL on existing rows. SchemaUpgrader V<next> migration; idempotent.

### Ensemble policy (v1)

```
Models: phi4 (Microsoft) and gemma3:12b (Google), parallel calls.
Voting:
  - Both AGREE on same candidate slug → "agreed" (eligible for auto-apply)
  - One picks, one abstains            → "phi4_only" or "gemma_only" (suggest, don't auto-apply)
  - Both abstain                        → "both_abstain"
  - Both pick, different slugs          → "conflict" (split signal, don't suggest)
```

Only `"agreed"` is eligible for auto-apply. Everything else is at most a UI hint.

### Pipeline integration

The AI assist sweeper is a **long-lived utility task** registered with the existing Utilities task runner:
- Atomic per the project rule — only one sweeper runs at a time, only one item processed at a time
- Manual start/stop via the existing utilities surface (Tools menu)
- Preempts cleanly when the user wants the machine quiet
- Periodically scans `enrichment_review_queue` for rows where `reason='ambiguous'` AND `resolved_at IS NULL` AND `ai_suggestion_slug IS NULL`
- Submits each row's `[phi4, gemma3:12b]` work to the orchestrator
- Persists the result via the new schema columns when both models complete
- Honors a global mode flag (`off` / `shadow` / `suggest` / `auto`)

This is intentionally batched and slow — we're trading wall-clock time for being out of the user's render path. Targeting ~6 rows/min throughput (constrained by Ollama serial generation).

### Concurrency and persistence

- **Within a single model: strictly serial inference.** Predictable latency and memory. One Ollama request at a time. Matches how the POC eval ran.
- **Orchestrator queue: in-memory only.** On boot, the sweeper rescans `enrichment_review_queue` and translation tables for unprocessed rows and re-queues them. Restart cost is at most one in-flight batch's worth of redundant work — no schema burden for a persistent queue.
- **Concurrency across models**: still serial (the orchestrator only activates one model at a time by design).

### Model bootstrap (auto-pull on first enable)

When mode flips from `off` → anything else:
1. Check `GET /api/tags` for `phi4` and `gemma3:12b`
2. If either is missing, call `POST /api/pull` and stream progress into the logs viewer
3. If Ollama is unreachable, log a clear error, leave mode effectively-disabled, and surface a "AI assist unavailable — Ollama not running" banner in the review queue UI
4. Once both models are present, mark assist as ready and begin sweeper operation

No silent fallback. If models can't be pulled (disk full, network down), the user sees the actual error rather than discovering picker behavior changed silently.

The model names (`phi4`, `gemma3:12b`) are hard-coded in v1 — they're tied to the prompt that was validated against them. If the user wants to swap models later, that's a Phase 4+ change requiring a new POC eval against the new model.

### Modes

Three operational modes via config:

| Mode | Behavior |
|---|---|
| `off` (default) | AI assist disabled entirely. Existing picker behavior unchanged. |
| `shadow` | Sweeper runs and records suggestions. UI shows nothing. Used to compare suggestion accuracy against actual human picks over time. |
| `suggest` | UI shows the suggestion alongside the picker. Human still confirms every pick. |
| `auto` | When `confidence='agreed'`, suggestion auto-applies after a configurable delay (e.g. 60s) unless the user opens the row first. All others go through `suggest` flow. |

Start every deployment at `shadow`. Promote to `suggest` once a week of shadow data confirms healthy suggestion rates. Promote to `auto` only after explicit user approval per-environment.

### UI

**UI design deferred to Phase 2.** Phase 1 is pure backend + shadow mode — no UI changes. Once a week of real suggestions accumulates in the DB, we'll design the picker affordances against actual data instead of imagined data.

The v2 picker (`modules/v2/enrichment/picker.js`) is the surface that will gain new affordances. Legacy picker (`utilities-enrichment-review.js`) stays untouched per the legacy-UI protection rule.

Phase 2 design will address at minimum:
- Where the "AI suggests" hint appears (inline on candidate, separate banner, modal, etc.)
- What controls the user gets (accept, override, dismiss)
- How the suggestion's reasoning is exposed (always-shown, click-to-expand, hidden by default)
- Whether `auto`-mode auto-applies are surfaced as a notification or summary
- How to handle the rare hard-contradiction case (both models picked different slugs)

### Production code changes required

1. **HttpOllamaAdapter**: add a `formatJson` boolean to `OllamaRequest` (default false). Backward-compatible. Maps to top-level `format: "json"` in request body. The sandbox's `JsonModeOllamaClient` becomes redundant after this.
2. **TranslationConfig**: add `assistModelPrimary` and `assistModelSecondary` fields (defaults `phi4` and `gemma3:12b`). Reuses the existing Ollama base URL.
3. **New repository methods** on `EnrichmentReviewQueueRepository` for the new columns.
4. **New sweeper task** wired into the utilities task runner.
5. **Web routes** for the new picker UI affordances.

No changes to the existing picker flow itself — AI is purely additive.

## Phasing

### Phase 1 — Shadow mode (no UI)
- Schema migration
- `HttpOllamaAdapter` gets `formatJson` flag (production change)
- `OllamaModelOrchestrator` (new shared infrastructure)
- `EnsembleAssistCaller` (uses orchestrator)
- `EnrichmentAssistSweeper` (background task)
- Config flag (off / shadow)
- Logs and metrics: suggestion counts by confidence band, per-model latency, parse failures, **model-load count** (proxy for orchestrator effectiveness)
- **Exit criteria**: 1 week of shadow data showing ≥80% strict-agreement rate, 0 hard contradictions on the live 306 open ambiguous rows, AND <5 model loads per 100 items processed (validates orchestrator working)

### Phase 2 — Suggest mode (UI surface)
- Add AI suggestion display + accept-button to review queue UI
- Track accept-rate, override-rate
- **Exit criteria**: 90%+ user agreement with AI suggestion when shown (i.e. the AI rarely surprises the user). User explicit go-ahead.

### Phase 3 — Auto-apply (opt-in)
- Config flag → `auto`
- "Auto-applied X items, here's what they were" daily summary in logs viewer
- Always-undoable: every auto-application is recordable; can be reverted via existing manual picker workflow

### Deferred (Phase 4+)
- **TranslationWorker migration to orchestrator** — mechanical refactor once Phase 1 is stable. Unlocks the full multi-model batching win across enrichment + translation. Estimate: 1 day eng work.
- Java post-processing rules (e.g. main-vs-bonus detection via title prefix/substring)
- 3-model ensemble experiments
- Stratified-sample re-eval per-label
- Backfill: re-run AI on already-resolved historical rows to find cases where the human likely chose wrong

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Model drift (Ollama updates change behavior) | Pin model versions in config; periodic shadow re-eval |
| New label/genre outside training distribution | Strict ensemble + abstention catches; falls back to manual |
| Ollama unavailable / slow | Sweeper retries with backoff; never blocks UI |
| Wrong auto-applied pick reaches user | Both models must agree (POC measured 0 errors); auto-apply has delay; always reversible |
| Kanji-bridge prompt regression after model update | Re-evaluate the prompt against the POC eval set when bumping models |
| Memory pressure (loading both models) | 26GB hardware is the tested floor; document as minimum. Smaller machines should run single-model. |

## Configuration

New `enrichment.assist` block in `organizer-config.yaml` — independent from `translation:` so each feature can be enabled/disabled separately. Loaded into a new `EnrichmentAssistConfig` record under `com.organizer3.config`.

```yaml
# organizer-config.yaml (additions)
enrichment:
  assist:
    mode: off          # off | shadow | suggest | auto
    primary_model: phi4
    secondary_model: gemma3:12b
    sweeper_interval_seconds: 60
    auto_apply_delay_seconds: 60   # for 'auto' mode
    prompt_version: v7-kanji-bridge   # tracked so we know what produced each suggestion
```

**Gating**: feature is gated at runtime by `mode` only — no compile-time flag, no build switch. Code ships, classes load, nothing runs when `mode: off` (default). Standard feature-flag pattern. If something's broken at startup, the existing app is unaffected.

## Open questions for user

1. **Mode default**: agree on `off` as the default for safety?
2. **One model or two?** Single-phi4 would auto-resolve ~91% with ~1% blunder rate (still very safe). Two-model strict ensemble drops to ~87% auto but with 0 blunders. Which trade-off is preferred?
3. **Suggest vs auto graduation**: should `auto` mode require an explicit per-actress or per-label opt-in, or is global on/off enough?
4. **Backfill of historical rows**: do you want a one-shot AI pass over the 887 historical resolved rows? Could surface cases where the human picked wrong — but creates a triage workload.
5. **UI placement**: review queue is the obvious spot, but should we also surface AI suggestions during initial sync (preemptively, before they hit the queue)?
6. **Reasoning storage**: keep the model's reasoning text in DB (good for debugging, takes space) or discard after the suggestion is applied?

## Cost / effort estimate

| Phase | Eng work | Validation |
|---|---|---|
| Phase 1 (shadow + orchestrator) | 3-4 days — schema, orchestrator, caller, sweeper, config | 1 week of shadow telemetry |
| Phase 2 (suggest UI) | 1-2 days — UI + accept flow | 1-2 weeks of user usage |
| Phase 3 (auto) | 0.5 day — flag + auto-apply path | Ongoing monitoring |
| Phase 4 (translation migration) | 1 day — refactor TranslationWorker | Reuse existing translation tests |

## Alternative designs (lower-effort)

These are worth considering if the above is heavier than warranted:

- **Suggestion-only, no auto**: skip Phase 3 entirely. Always require human confirmation. Simpler, less risk, lower upside.
- **Single-model (phi4) only**: 91% auto-resolve, 1% blunders. Simpler code (no ensemble voting), faster (~5s vs ~10s). Worse safety.
- **Manual command, no sweeper**: provide an MCP tool `ai_suggest_picker(id)` that runs ensemble on demand. Avoids the background scheduler entirely. User triggers, user reviews.
- **Replace, don't augment**: when strict ensemble agrees, the resolve happens automatically during sync — never even creates a queue row. Bypasses the review surface entirely. Stronger automation but harder to audit.
