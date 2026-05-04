# Proposal: Local Translation Service

> **Status: IMPLEMENTED** — shipped 2026-05-03 in PRs #37-40
**Origin:** Enrichment fills many catalog fields (`title_original`, `series`, `maker`, profile bios) with Japanese text from JavDB. Today these fields are stored as-is and surfaced raw in the UI. A POC against five local LLMs via Ollama (`reference/translation_poc/SUMMARY.md`) showed that local translation is feasible: gemma4:e4b + a hardened few-shot prompt achieves 95% acceptable output on the explicit-content test set, with qwen2.5:14b as a fallback for the 5% it refuses. This proposal designs the service that will host that capability.

### Design decisions locked (2026-05-03)

1. **Storage:** translations land in `*_en` columns on existing tables (not a separate join table). New columns: `title_javdb_enrichment.title_original_en`, `_series_en`, `_maker_en`, `_publisher_en`. `actresses.biography_en` reserved for future bio work.
2. **UI display:** EN-only everywhere except actress/title detail pages, which show EN+JP side-by-side. JP raw value is the fallback when no EN exists.
3. **Auto-trigger policy:** hybrid. Bounded-vocab fields (maker, series, publisher) are auto-translated when enrichment writes them. Title-originals are bulk-only, kicked off explicitly from Tools UI. Bios are explicit-only and out of scope for this proposal.
4. **Total-failure display:** show JP raw value with a subtle "translation unavailable" marker. Failure is non-blocking — same surface as untranslated.
5. **Bio integration:** Phase 0a validates the `prose` strategy (cheap, locks in the strategy default), but no bio pipeline ships in this proposal. Defer to a future spec when bios get a real UI.
6. **Human correction:** `human_corrected_text` + `human_corrected_at` columns on the cache row. Service serves human text when present; never re-translates a corrected row across strategy version bumps.
7. **Prose strategy / swap conflict (RESOLVED 2026-05-03):** Phase 0a measurement (`reference/translation_poc/PHASE0_REPORT.md`) showed gemma4:e4b wins on prose by 4.5× speed *and* lower sanitization/leak rates. `prose` defaults to gemma4:e4b primary, qwen2.5:14b fallback. No swap tradeoff needed — gemma4 is the only model loaded for normal workloads; qwen2.5 loads only for periodic tier-2 batch sweeps.

---

## 1. Problem statement

The catalog accumulates Japanese metadata faster than a human can reasonably translate it. Concrete examples:

- **Title originals** — every enriched title has a `title_original` field; ~25k titles in the library, growing.
- **Series, maker** — a smaller bounded vocabulary (~hundreds), but reused across thousands of titles, and currently rendered raw in the UI.
- **Profile bios** — actress YAML files have JP bio paragraphs in the source data that have never been surfaced because there is no translation pipeline.
- **Future enrichment fields** — any new scraper that pulls from JP sources (DMM, etc.) will widen the gap.

The POC proved that local LLM translation works at acceptable quality, but each translation takes 10–90 seconds. That is too long for synchronous request handling and too valuable to throw away after a single use. The system needs:

1. A clean Java surface for "translate this Japanese string" that the rest of the app can call without knowing about Ollama.
2. Caching so the same input is never translated twice.
3. Async scheduling so translation never blocks the request that asked for it.
4. A way to deliver the translated string back to the field that needs it once available.

---

## 2. Design principles

1. **Two layers, one direction of dependency.** A low-level adapter that knows about Ollama and nothing about the catalog; a high-level service that knows about the catalog and uses the adapter. The service may call the adapter directly for short, latency-tolerant operations (smoke tests, model warmups); the rest of the app may only call the service.

2. **Cache before LLM, always.** A `(source_text, model_id, prompt_version) → english_text` cache table is the first thing every translation request consults. Cache hits are the common case after the catalog has been processed once.

3. **Fire-and-forget from the caller.** The caller schedules a translation and gets back a request id. The translation lands in the catalog in its own time. The caller never blocks on Ollama.

4. **Multi-tier model fallback is built-in.** When the primary model returns a refusal token, the service automatically retries with the secondary model. Callers don't see this — they get the eventual successful output or a permanent failure.

5. **Per-model prompt strategy.** The POC showed that a single prompt does not work across models (the hardened prompt that fixes gemma4 makes aya-expanse refuse 100%). Prompt + model are bound together as a **translation strategy**, versioned, and stored alongside cached results.

6. **Respect Ollama's parallelism, don't fight it.** Ollama serializes per-model requests but can hold multiple models in memory and run them concurrently. The service queues per-model and submits with controlled concurrency rather than implementing its own request-level locking.

7. **One translation primitive, many usage points.** The service's contract is "string in, string out (eventually)". Where that is wired up — title originals, bio paragraphs, series names — is out of scope for the initial build. Adding a usage point is "call the service from the right place plus subscribe to the result", not a service change.

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Application code (enrichment, UI, batch jobs)                   │
│  Calls: translationService.requestTranslation(text, callback)    │
└───────────────────────────┬──────────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────────┐
│  TranslationService (high level)                                 │
│   • Cache lookup (translation_cache table)                       │
│   • Stage-name shortcut (curated romanization table)             │
│   • Strategy selection (which model + prompt for which content)  │
│   • Queue management (translation_queue table)                   │
│   • Result delivery (callback dispatch / DB write)               │
│   • Sanitization-detector quality alarm                          │
└───────────────────────────┬──────────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────────┐
│  OllamaAdapter (low level)                                       │
│   • HTTP client to /api/generate, /api/tags, /api/show           │
│   • Model presence + auto-pull                                   │
│   • Per-call options (think:false, temperature, num_predict)     │
│   • Raw JSON in/out, returns total_duration + token counts       │
│   • No knowledge of catalog, queues, or prompts                  │
└───────────────────────────┬──────────────────────────────────────┘
                            │
                       Ollama HTTP API (localhost:11434)
```

---

## 4. The low-level adapter: `OllamaAdapter`

**Responsibility:** Translate a JSON-shaped request to/from Ollama. That is all.

**Interface (rough):**

```java
public interface OllamaAdapter {
    /** Block until response. Used by service for cache-miss work. */
    OllamaResponse generate(OllamaRequest req);

    /** Streaming variant for future use (e.g. progress in long bio translations). */
    void generateStreaming(OllamaRequest req, Consumer<OllamaToken> onToken);

    /** List installed models. */
    List<OllamaModel> listModels();

    /** Pull a model if missing. Blocking with progress callback. */
    void ensureModel(String modelId, ProgressCallback cb);

    /** Health check — Ollama daemon reachable + responds. */
    boolean isHealthy();
}
```

**`OllamaRequest`** carries: model id, prompt, system message, options map (`think`, `temperature`, `num_predict`, `stop`), timeout. It is a transport object — no domain semantics, no notion of "translation".

**`OllamaResponse`** returns: response text, total_duration, prompt_eval_count, eval_count, eval_duration. The latter four enable per-call metrics without the service having to time anything itself.

**Configuration:**
- Base URL (`http://localhost:11434`), timeout, retry policy on connection failure (not on 5xx — model errors are real signal).
- No prompt construction. No model-specific behavior. No catalog awareness.

**Why this layer exists:**
- The HTTP body shape, the `think: false` footgun, the streaming protocol — these are Ollama-specific concerns. Isolating them means swapping to a different inference runtime later (vLLM, MLX, llama.cpp directly) is a one-class change.
- Tests of the high-level service don't need a real Ollama — they can mock `OllamaAdapter` and assert on request shape.

---

## 5. The high-level service: `TranslationService`

**Responsibility:** Provide a clean catalog-facing API for translation, with caching, queueing, fallback, and quality monitoring.

### 5.1 Data model

Three new tables.

**`translation_strategy`** — versioned (model + prompt) pairs. Mostly static; rows added rarely.

```sql
CREATE TABLE translation_strategy (
    id              INTEGER PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,    -- 'gemma4_hardened_v1', 'qwen25_hardened_v1'
    model_id        TEXT NOT NULL,           -- 'gemma4:e4b'
    prompt_template TEXT NOT NULL,           -- includes {jp} placeholder
    options_json    TEXT,                    -- '{"think":false,"temperature":0.2}'
    is_active       INTEGER NOT NULL DEFAULT 1
);
```

**`translation_cache`** — the pile of work the service has already done.

```sql
CREATE TABLE translation_cache (
    id                INTEGER PRIMARY KEY,
    source_hash       TEXT NOT NULL,           -- SHA-256 of normalized source_text
    source_text       TEXT NOT NULL,           -- normalized form (NFKC + trimmed)
    strategy_id       INTEGER NOT NULL REFERENCES translation_strategy(id),
    english_text         TEXT,                 -- nullable: NULL = failure (model never produced output)
    human_corrected_text TEXT,                 -- nullable; populated by human edit (decision #6)
    human_corrected_at   TEXT,                 -- when the human correction was made
    failure_reason       TEXT,                 -- 'refused', 'sanitized_both_tiers', 'unreachable'
    retry_after          TEXT,                 -- nullable; set for transient failures
    latency_ms           INTEGER,
    prompt_tokens        INTEGER,
    eval_tokens          INTEGER,
    eval_duration_ns     INTEGER,
    cached_at            TEXT NOT NULL,
    UNIQUE(source_hash, strategy_id)
);
CREATE INDEX idx_tc_strategy ON translation_cache(strategy_id);
```

The unique constraint on `(source_hash, strategy_id)` is the cache key. The hash column keeps the index small even for multi-KB bio inputs; `source_text` is kept alongside for inspection and re-translation. Cache strategy detail in §5.6.

**`translation_queue`** — pending work.

```sql
CREATE TABLE translation_queue (
    id              INTEGER PRIMARY KEY,
    source_text     TEXT NOT NULL,
    strategy_id     INTEGER NOT NULL,
    submitted_at    TEXT NOT NULL,
    started_at      TEXT,
    completed_at    TEXT,
    status          TEXT NOT NULL,           -- 'pending', 'in_flight', 'done', 'failed'
    callback_kind   TEXT,                    -- 'title.title_original_en', 'actress.bio_en', null
    callback_id     INTEGER,                 -- the row id to update on success
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT
);
CREATE INDEX idx_tq_status ON translation_queue(status, submitted_at);
```

The `callback_kind` + `callback_id` pair is how the service gets the result back to the original requester without callers having to register Java callbacks (which don't survive a restart). It is a tagged dispatch table — the service knows how to write `english_text` for each kind.

### 5.2 Public API

```java
public interface TranslationService {

    /** Synchronous lookup. Returns cached translation if present, else empty. Never calls Ollama. */
    Optional<String> getCached(String sourceText);

    /** Submit translation work. Returns request id. If already cached, completes immediately. */
    long requestTranslation(TranslationRequest req);

    /** Fast lookup table for actress stage names. Hits the curated kanji→romaji table first. */
    Optional<String> resolveStageName(String kanjiName);

    /** Operational view for the Tools UI. */
    TranslationServiceStats stats();
}

public record TranslationRequest(
    String sourceText,
    @Nullable String callbackKind,    // 'title.title_original_en' etc., or null for fire-and-forget
    @Nullable Long callbackId,
    Priority priority                 // immediate (UI), normal (enrichment), bulk (backfill)
) {}
```

Three usage modes naturally fall out:

1. **Cached lookup only** (`getCached`) — for UI that wants to display the translation if it's been done, but not block on translating it. Fastest path; pure DB read.

2. **Fire-and-forget** (`requestTranslation` with null callback) — caller wants the translation cached for future requests but does not need the result delivered back. Used for opportunistic warmup.

3. **Scheduled with callback** (`requestTranslation` with callback) — the common enrichment path. "Translate this; when done, write the result to `titles.title_original_en` on row 12345."

### 5.3 Worker loop

A single background thread consumes `translation_queue`. For each pending row:

1. Re-check cache (covers race where two requests for the same string get queued before the first completes).
2. If callback specified, check that the target row still exists and the english field is still null (avoid clobbering a manual edit).
3. Pick strategy: tier-1 if first attempt, tier-2 if first attempt failed with refusal.
4. Call `OllamaAdapter.generate`.
5. Run sanitization-detector regex on (input, output). If suspect, mark in cache as `failure_reason='sanitized'` and try tier-2.
6. On success: write to cache, dispatch callback, mark queue row done.
7. On failure: bump `attempt_count`. If `< maxAttempts`, leave pending. Else mark failed.

Single-thread by default. If we want concurrency for short tasks, configure Ollama with `OLLAMA_NUM_PARALLEL` and bump the worker pool size — Ollama batches per-model requests internally, so multiple workers against the same model is fine (within memory limits).

### 5.4 Strategy selection

The `translation_strategy` table seeds with three strategies, each with its own tier-2 fallback. They differ in prompt shape and (for `prose`) primary model:

| Strategy | Prompt shape | Primary model | Tier-2 fallback | Use case |
|---|---|---|---|---|
| `label_basic` | minimal: one line "Translate Japanese to English…" | gemma4:e4b | qwen2.5:14b | Short, non-explicit labels (maker, publisher, plain series) |
| `label_explicit` | hardened: system message + 3-example few-shot covering adult vocab | gemma4:e4b | qwen2.5:14b | Long descriptive titles, explicit series, anything tripping the explicit-JP regex |
| `prose` | "Translate this Japanese paragraph to natural English, preserve paragraph structure" + larger `num_predict` | gemma4:e4b | qwen2.5:14b | Biographies, legacy text, long-form synopses |

`pickStrategy(sourceText, contextHint, attempt)`:

1. **Caller-provided hint wins.** `contextHint` of `"prose"` → `prose` strategy regardless of length. The enrichment pipeline knows it's writing a bio vs. a maker; let it say so.
2. **Heuristic when hint is absent.** Input contains explicit JP token (`中出し|輪姦|痴漢|青姦|生中出し|種付け|レイプ|レ×プ|ハメ撮り|ザーメン|淫|変態|キメセク|M男`) **or** input length > 50 chars → `label_explicit`. Else → `label_basic`.
3. **On retry (`attempt > 1`)** → use the tier-2 fallback for whichever strategy was first selected.

**Why short prompts on short inputs.** The hardened prompt is ~200 tokens of system message + examples. A maker name is ~3 tokens. Using the hardened prompt for everything means ~70× overhead per call with no quality benefit, and (per the POC's aya-expanse finding) needlessly elevates refusal risk on safety-tuned models when the explicit-vocab dictionary is included for inputs that don't need it.

**Why gemma4 leads on prose (resolved by Phase 0a, see `reference/translation_poc/PHASE0_REPORT.md`).** Initial hypothesis was that qwen2.5's 14B parameter count would help on long-form. Phase 0a measurement on 8 real bio paragraphs disproved this: gemma4 was 4.5× faster (37 s vs 166 s avg), produced no sanitization slips (vs 12.5% for qwen2.5), and no CJK leakage (vs 12.5% for qwen2.5). gemma4's broader Japanese cultural exposure translates directly to better prose handling. qwen2.5 stays as tier-2 fallback only.

### 5.5 Cache strategy

Caching is the design's load-bearing assumption — most fields in the catalog repeat (one maker name applied to thousands of titles), and translation latency at 30–60 s/call would be prohibitive without it. This section spells out exactly what the cache promises.

#### 5.5.1 What gets cached

Every completed translation attempt — including failures — produces a `translation_cache` row. Four outcome shapes:

- **Success.** `english_text` populated, `failure_reason` null. Served on every future request.
- **Human-corrected.** `human_corrected_text` populated. Served in preference to `english_text` whenever present (per decision #6). Survives strategy version bumps without being re-translated; the audit value of the original `english_text` is preserved alongside.
- **Permanent failure.** `english_text` null, `failure_reason` ∈ {`refused`, `sanitized_both_tiers`}. Both tier-1 and tier-2 strategies declined or silently rewrote the input. Never retried unless the strategy version bumps. Prevents re-spending GPU on inputs we've proven we can't translate cleanly. The UI surfaces this as JP raw + "translation unavailable" marker (per decision #4).
- **Transient failure.** `failure_reason` = `unreachable` (Ollama daemon down, network blip), `retry_after` set to a near-future timestamp. The next request after that timestamp passes the cache and re-attempts.

**Lookup priority:** `human_corrected_text` > `english_text` > fallback (raw JP with marker for permanent failure, raw JP without marker for missing/pending).

#### 5.5.2 Cache key and normalization

The lookup key is `(source_hash, strategy_id)`. Normalization runs before hashing and is critical to hit rate:

- Trim leading/trailing whitespace.
- Apply NFKC normalization — half-width katakana matches full-width, full-width digits match ASCII digits, etc. Common in JavDB scrapes where the same logical name appears with different widths.
- Collapse internal runs of whitespace to a single space.
- **Do not** lowercase or strip punctuation. Case and `！` vs `!` can carry meaning in titles.

The normalized form is what the cache stores in `source_text`; the original raw input is not retained. If `"アイエナジー "` and `"アイエナジー"` arrive from different callers, they hit the same row.

`source_hash` (SHA-256 of normalized `source_text`) is the indexed lookup column. For long inputs (bios, future scraper output) this keeps the index size bounded — text-equality on multi-KB blobs at scale is slower than expected.

#### 5.5.3 Strategy versioning (the invalidation story)

Lazy invalidation. When a prompt changes:

1. Insert a new `translation_strategy` row (e.g. `label_basic_v2`); deactivate the old (`is_active=0`).
2. New requests route to v2, miss the cache, do the work, write a new cache row.
3. Old cache rows remain. UI surfaces that still reference v1 results continue to serve. No mass re-translation, no downtime.

A bulk re-translate can be triggered manually from the Tools UI (§7.1) per-strategy ("re-translate all maker fields with `label_basic_v2`"). Always opt-in, never automatic on version bump.

#### 5.5.4 Cache hits do not cross strategies

If `title_original` for a given title is cached under `label_explicit`, a request for the same string under `label_basic` is a **miss**. Different strategies produce meaningfully different output (label-style vs prose vs explicit-vocab); serving the wrong shape would be a silent quality regression. The strategy id is part of the key by design.

#### 5.5.5 No eviction in v1

Appendix A projects ~4,100 unique strings × 3 active strategies = ~12k cache rows max. SQLite handles that trivially. Eviction code is complexity we don't need until usage proves otherwise.

The only janitor is a daily sweep that deletes `failure_reason='unreachable'` rows whose `retry_after` is in the past — these have no value once expired and would otherwise accumulate.

#### 5.5.6 No proactive cache warming

The Tools UI bulk-submit path (§7.1) handles the "translate everything" workflow when a human asks for it. Background warming on startup risks Ollama contention with whatever else the user is doing on their machine. Opt-in beats opt-out for compute-intensive operations on a single GPU.

#### 5.5.7 Observability

The Tools UI service stats panel (§7.1) surfaces:

- Cache hit rate, rolling 7-day, broken down per strategy.
- Cache row count by outcome shape (success / permanent / transient).
- Top-N most-frequently-served entries — sanity check that bounded-vocab fields (maker, series, publisher) are getting the reuse the design assumes.
- Permanent-failure list with sample inputs, so a human can review what the LLMs gave up on and decide whether prompt tuning is warranted.

Per-call metadata (`prompt_tokens`, `eval_tokens`, `eval_duration_ns`) is retained on every cache row. Aggregating across rows produces per-strategy throughput trends over time without needing to re-run anything — the eval is more honest with this data preserved.

### 5.6 Sanitization detector

A regex pair: a set of explicit JP tokens (`中出し|輪姦|痴漢|...`) and a set of explicit EN equivalents (`creampie|gangbang|molester|...`). If the input matches the JP set and the output matches none of the EN set, the translation is suspected sanitized.

This is the same heuristic the POC's `score.sh` already uses. It is not perfect — it has false positives on inputs where the explicit term is incidental — but it is a useful alarm at scale and it is the only automated way to catch translategemma-style silent failures.

---

## 6. Curated lookup tables for proper-noun fields

The fundamental difficulty axis for translation is **whether the right answer comes from understanding meaning (generation) or from knowing a specific established mapping (retrieval)**. LLMs are excellent at the first and surprisingly bad at the second. Stage names are the textbook retrieval-not-generation case (see §6.1), and at least one other catalog field — studio names — has the same shape (see §6.2). Both should bypass the LLM and serve from curated tables when an entry exists.

### 6.1 Stage-name shortcut (Phase 5)

Stage names are dictionary lookups masquerading as translation. The POC found 67% best-case LLM accuracy on a 15-name sample (gemma4) and 40% on qwen2.5 — too low to trust as canonical data, and unnecessary because the actress YAML files already contain the human-curated romanization for every actress in the catalog.

**Design:** A `stage_name_lookup` table populated from the existing actress YAML files at startup. `TranslationService.resolveStageName(kanji)` consults this first and returns the curated romanization if found. Only unknown names route through the LLM, and those results are written back as suggestions for human review (not auto-merged into the YAML).

The error patterns observed in the POC reinforce why human curation is non-negotiable for canonical data:
- gemma4 errors are usually one phoneme off (Naoko vs Nao, Niodou vs Nikaido) — recognizable.
- qwen2.5 errors are *entire wrong names* (湊莉久 → "Mio Reiko", 鈴村あいり → "Ai Irie") — would be silently unrecognizable to a human looking up the actress.

This keeps name data canonical (human-curated) while still capturing the model's best guess for previously unseen names as a starting point for human review.

### 6.2 Studio-name lookup (future enhancement)

The same pattern likely applies to studios (`maker`, `publisher`). These look like simple short labels but are actually proper nouns with *official* English brand names that aren't derivable from the kanji:

| JP source | LLM transliteration | Official English brand |
|---|---|---|
| アイエナジー | "Ai Energy" | i-Energy |
| クリスタル映像 | "Crystal-Eizou" | Crystal Pictures |
| マルクス兄弟 | "Marx Brothers" | Marx Brothers (matches) |
| エスワン | "S One" | S1 No.1 Style |

The LLM can transliterate these, and Phase 0b shows it does so cleanly (0% sanitization, low latency). But the *correct* answer is often the studio's chosen English brand name, which has to be looked up. **For a catalog where studios are clickable filters, the wrong name will fragment the data** — half the titles tagged "Crystal Pictures", half tagged "Crystal-Eizou", and the user can't tell they're the same studio.

**Status:** out of scope for this proposal. Flagged here so the §5 architecture decisions don't preclude it. Implementation would mirror §6.1: a `studio_lookup` table seeded from a curated YAML file (or, more likely, hand-edited based on observed LLM transliterations), consulted before the LLM falls through. The seed corpus is small — ~117 distinct makers + ~130 distinct publishers per Appendix A. A first pass could be one focused afternoon of human review.

Until that table exists, the LLM transliterations are usable for display but should be treated as "best-guess display name" rather than "groupable identifier." The catalog UI should NOT treat the EN studio field as a join key.

---

## 7. Operational surface

### 7.1 Tools UI page

A `Tools → Translation` page exposing:

- **Service stats:** queue depth, in-flight count, cache size, recent throughput.
- **Strategy table:** active strategies with model/prompt.
- **Recent failures:** last N rows where `failure_reason` is non-null.
- **Manual translate:** text box → strategy dropdown → result. For ad-hoc verification and prompt tuning.
- **Bulk submit:** "translate all `title_original` for actress X" or "for volume Y" — schedules a batch into the queue.

### 7.2 Logging

Every translation completion logs at INFO level with model, latency, token count, source-length, cache-hit-or-miss. The Logs viewer (already in use for other observability) becomes the "is the service actually working" check.

### 7.3 Health gating

Service exposes `isHealthy()`:
- Ollama daemon reachable.
- Both tier-1 and tier-2 models present (auto-pull on startup if missing).
- Recent translation latency within bounds.

If unhealthy, `requestTranslation` queues normally but the worker loop pauses until health returns. Callers don't need to know.

---

## 8. Failure modes and how the design handles them

| Failure | Behavior |
|---|---|
| Ollama daemon down | Adapter returns failure; service marks attempt; worker loop polls health and resumes when back. |
| Tier-1 model refuses input | Service auto-retries on tier-2. |
| Both tiers refuse | Cache row written with `failure_reason='refused'`. Permanent — won't retry until strategy version bumps. |
| Sanitization detected | Same as refusal — fall through to tier-2. |
| Callback target row deleted between schedule and completion | Worker logs, drops the callback, still caches the translation. |
| Worker crashes mid-call | Queue row stays in `in_flight`. Sweeper job (every N minutes) resets stuck `in_flight` rows older than threshold back to `pending`. |
| Strategy prompt updated | Bumping the strategy id (new row, old row deactivated) invalidates the cache for that strategy without losing the old data. |

---

## 9. What is explicitly NOT in this proposal

- **Where the service is wired up.** The enrichment integration the user mentioned is the obvious first usage point, but other consumers (UI on-demand translation, batch backfills, profile bios) are out of scope for the architectural design. Each usage point will be a small, separate piece of work once the service exists.
- **Fancy quality scoring.** Beyond the sanitization detector, no automated quality model. Adding one (e.g. round-trip JP→EN→JP comparison) is interesting but speculative.
- **Streaming-to-UI translation.** The adapter exposes streaming, but the service initially uses only the blocking variant. UI streaming is a future enhancement on top of the existing API.
- **GPU offload to a remote server.** Pure local-only for v1. The adapter abstraction means swapping the URL from localhost to a LAN host is a config change later.
- **"Retranslate with a better model" user action.** Captured as a future-enhancement idea: a manual, user-initiated action on a per-row basis that re-runs an existing translation through the more expensive model when the user judges the current output unsatisfactory. Distinct from initial translation (always user-triggered, never automatic). Implementation would reuse the existing strategy + cache mechanism — likely a new `retranslate_with` strategy id or a one-shot variant of the bulk-submit flow. Not in scope for this proposal; flagged here so the architecture decisions in §5 don't accidentally preclude it.

---

## 10. Phased build (rough)

0. **Phase 0 — strategy validation. (COMPLETE 2026-05-03 — see `reference/translation_poc/PHASE0_REPORT.md`)**

   - **0a Prose primary:** gemma4:e4b confirmed primary; qwen2.5:14b fallback. gemma4 beat qwen2.5 on speed (4.5×), sanitization (0% vs 12.5%), and CJK leakage (0% vs 12.5%) across 8 real biographical paragraphs.
   - **0b `label_basic` primary:** gemma4:e4b confirmed primary; the earlier hypothesis about flipping to qwen2.5 was based on a cold-load artifact and did not survive a clean 90-item re-measurement. gemma4 p95 on makers is 5.7 s (not 137 s as originally observed), 6–7× faster than qwen2.5 with no quality regression.
   - **Net:** all three strategies (`label_basic`, `label_explicit`, `prose`) default to gemma4:e4b primary, qwen2.5:14b fallback. gemma4 is the only model loaded for normal workloads. Phase 1 unblocked.
1. **Phase 1 — adapter + cache.** `OllamaAdapter` + `TranslationService.getCached` + `requestTranslation` with synchronous (in-thread) execution. Three strategies seeded (`label_basic`, `label_explicit`, `prose`). No queue table yet. Wires up enough for ad-hoc testing.
2. **Phase 2 — queue + worker.** Promote to async via `translation_queue`. Single worker thread. Sweeper for stuck rows.
3. **Phase 3 — multi-tier + sanitization detector.** Add tier-2 fallback and the sanitization regex check.
4. **Phase 4 — Tools UI page + logging polish.** Operational visibility.
5. **Phase 5 — stage-name shortcut.** Curated lookup table from actress YAMLs.

Each phase is independently shippable. Phases 1+2 give us a working service even with no fallbacks.

---

## 11. Open questions

- **Strategy versioning bulk re-translate UX.** §5.5.3 establishes lazy re-translation with an opt-in bulk button. Open: should the bulk button be visible even when no version bump has happened (allowing a "translate everything" one-shot for a clean catalog) or only after a version bump (forcing the user through Tools UI to schedule the work)? Lean: always visible, scoped per strategy.
- **Concurrency setting.** Start with `worker_pool=1` and `OLLAMA_NUM_PARALLEL=1`. Tune up only if queue depth is consistently growing.
- **What counts as "long enough" for the in-flight reset sweeper?** Initial guess: 5× p95 latency for the strategy. Refine after observing real workloads.

---

## Appendix A — Translation targets (live DB inventory, 2026-05-03)

Counts taken directly from `~/.organizer3/organizer.db`. "JP rows" = rows whose value contains hiragana, katakana, or CJK ideograph characters. "Bounded vocab" means the same string is reused across many catalog rows, making each translation high-leverage.

### A.1 In the database

| Field | JP rows | Distinct JP values | Class |
|---|---|---|---|
| `title_javdb_enrichment.title_original` | 2,818 | ~2,818 | High-volume, mostly unique |
| `title_javdb_enrichment.series` | — | 941 distinct | **Bounded vocab — high reuse** |
| `title_javdb_enrichment.publisher` | — | 130 distinct | Bounded vocab |
| `title_javdb_enrichment.maker` | — | 117 distinct | Bounded vocab |
| `actresses.stage_name` | 106 | 106 | Routed to curated YAML lookup (§6.1) |
| `actress_aliases.alias_name` | 57 | 57 | Routed to curated YAML lookup (§6.1) |

### A.2 In actress YAML files (not yet in the DB schema)

| Field | Notes |
|---|---|
| `profile.biography` | Long-form prose (~1–4 KB); mixed JP/EN today, some entries pure JP |
| `profile.legacy` | Same shape, shorter |
| `profile.name.reading` | Hiragana reading of the kanji name — useful seed data for the stage-name lookup table |
| `profile.name.alternate_names` | JP aliases not yet mirrored into `actress_aliases` |
| `awards[*].category` | Already bilingual in the format `優秀女優賞 (Excellent Actress Award)` for many; pure JP for older entries |

### A.3 Surprisingly NOT JP (verified empty)

- `enrichment_tag_definitions.name` — JavDB's `/en` storefront returns English tag names. **0 JP entries.**
- `actresses.birthplace` — populated in English by the upstream enrichment pipeline. **0 JP entries.**
- `labels.label_name`, `labels.company` — short codes, mostly already English.

### A.4 Suggested batch ordering

The bounded-vocab fields are the cheapest, highest-impact first batch: **~1,188 distinct strings across `series` + `publisher` + `maker`**, translating each one once unlocks English rendering across thousands of catalog rows.

| Phase | Target | Rough cost (gemma4:e4b @ ~30 s/call) | Why first |
|---|---|---|---|
| Batch 1 | maker + publisher + series (~1,188 distinct) | ~10 hours | Bounded; one-time cost; powers UI everywhere |
| Batch 2 | title_original (~2,818 unique) | ~24 hours | Mostly unique; one-shot per title |
| Batch 3 | YAML bios (longer inputs) | Variable | Higher per-call cost (longer outputs); test num_predict ceiling first |

Batches 1 and 2 are runnable as bulk submissions through the Tools UI (§7.1). Batch 3 needs a YAML round-trip pipeline that doesn't yet exist.

Future scraper output (DMM, FANZA, Wikipedia-JP) will dwarf all of the above by character count. The adapter's streaming variant (§4) is sized for that future, even though v1 doesn't use it.

---

## Appendix B — Field-to-strategy mapping

Default strategy for each Appendix A field. The caller can override via `contextHint` on the `TranslationRequest`; this table is what the heuristic in §5.4 produces when no hint is supplied.

| Field | Strategy | Reasoning |
|---|---|---|
| `title_javdb_enrichment.title_original` | `label_explicit` | Long, often explicit; matches the POC's primary test set |
| `title_javdb_enrichment.series` | `label_explicit` (heuristic falls through to `label_basic` for short non-explicit ones) | Mixed — explicit content present in 22% of POC sample |
| `title_javdb_enrichment.maker` | `label_basic` | Studio names; bounded vocab; no explicit content |
| `title_javdb_enrichment.publisher` | `label_basic` | Same shape as maker |
| `actresses.stage_name` | (curated YAML lookup, §6.1) | Not routed through LLM — dictionary-resolution problem |
| `actress_aliases.alias_name` | (curated YAML lookup, §6.1) | Same |
| `profile.biography` (YAML) | `prose` | Long-form prose; needs paragraph structure preservation and the 14B model's better long-input handling |
| `profile.legacy` (YAML) | `prose` | Same shape as biography |
| `profile.name.reading` (YAML) | (no translation; seed for stage-name lookup table) | Already canonical |
| `profile.name.alternate_names` (YAML) | (curated YAML lookup, §6.1) | Same as stage_name |
| `awards[*].category` (YAML) | `label_basic` | Short formal phrases like `優秀女優賞`; many already bilingual; treat unilingual ones as plain labels |

---

## Appendix C — Phase 0 prose-strategy test corpus

Raw JP biographical text already available in `reference/actresses/<actress>/` from prior research work. Use these as the source for the Phase 0 validation. Slice 5–8 paragraphs of varying length to cover both short and long inputs.

| Actress dir | File | Total JP chars (est.) | Notes |
|---|---|---|---|
| `yuma_asami/` | `yuma_asami_wikipedia_ja_raw.txt` | ~8,275 | Longest single source; full Wikipedia article |
| `yuma_asami/` | `yuma_asami_dmm_raw.json` | ~7,971 | DMM-style structured prose; different register from Wikipedia |
| `mikami_yua/` | `mikami_yua_wikipedia_ja_notes.txt` | ~3,089 | Notes file but contains real prose chunks |
| `tsujimoto_an/` | `tsujimoto_an_wikipedia_ja_raw.txt` | ~2,373 | |
| `nana_ogura/` | `nana_ogura_wikipedia_ja_raw.txt` | ~2,274 | |
| `amami_rei/` | `amami_rei_wikipedia_ja_raw.txt` | ~1,917 | |
| `sora_aoi/` | `sora_aoi_wikipedia_ja_raw.txt` | ~1,322 | |
| `aida_yua/` | `aida_yua_wikipedia_ja_raw.txt` | ~1,100 | Note: file is a structured summary, not pure raw text — use selectively |
| `yoshikawa_aimi/` | `yoshikawa_aimi_wikipedia_ja_raw.txt` | ~1,052 | |

**Suggested slicing strategy:** pick 2 short paragraphs (~100 chars), 3 medium (~300–500 chars), 2–3 long (~1000+ chars) from across these files. Keep the source attribution alongside each slice so the final report can cite where each test paragraph came from. Skip files under 500 JP chars (`sakura_momo`, `moka`, `hoshino_riko`, `aoi_tsukasa`) — too short to be representative of the bio use case.

---

## Appendix E — Operational measurements (2026-05-03)

Empirical Ollama behavior on the target hardware (M-series, 24 GB unified). Captured by `reference/translation_poc/measure_ops.sh`; raw output in `MEASURE_OPS.md`.

### E.1 Co-residency

**Both gemma4:e4b and qwen2.5:14b cannot stay loaded simultaneously.** Loading the second model causes Ollama to evict the first automatically. Confirmed by `ollama ps` after sequential loads:

```
After loading gemma4:e4b:
  NAME          SIZE     PROCESSOR    UNTIL
  gemma4:e4b    10 GB    100% CPU     4 minutes from now

After loading qwen2.5:14b (without explicitly stopping gemma4):
  NAME           SIZE      PROCESSOR    UNTIL
  qwen2.5:14b    8.9 GB    100% CPU     4 minutes from now
```

Design implication: treat the loaded model as a single-slot resource. `OLLAMA_MAX_LOADED_MODELS=1` is the intended (and effectively forced) configuration.

### E.2 Swap cost

| Transition | Wall time (median across 3 cycles) |
|---|---|
| Same model, warm call | **~750 ms** |
| Cold load → gemma4 | **~12 s** |
| Cold load → qwen2.5 | **~40 s** |
| Round-trip (gemma4 ↔ qwen2.5) | **~50–55 s** |

Asymmetric: loading qwen2.5 is ~3× more expensive than loading gemma4. Bigger weights take longer to page in over unified memory.

Design implications:

- **Tier-2 batching threshold must amortize the swap.** Per-item overhead for a single tier-2 retry is ~82 s (40 s swap + ~30 s translate + 12 s swap-back) — economically untenable. For a 10-item tier-2 batch, amortized per-item overhead drops to ~35 s. The threshold in §5.3 should be **10 pending items OR 1 hour timeout, whichever first** (not 20 / 30 min as earlier drafts suggested).
- **Live UI translation requests must never trigger a swap.** A user clicking "translate this now" cannot wait 40 s. UI requests are dispatched only against the currently-loaded model; refusals return a JP fallback immediately and queue a `tier_2_pending` row for the next planned batch.
- **Bulk backfill workflow must drain tier-1 fully before any swap.** Interleaving costs 50 s every transition. Natural batching avoids this.

### E.3 In-model concurrency (`OLLAMA_NUM_PARALLEL`)

Against a warm gemma4:e4b with 4 distinct short prompts:

| Concurrent requests | Total wall | Per-request avg | Throughput vs N=1 |
|---|---|---|---|
| 1 | 7.0 s | 7.0 s | 1.0× |
| **2** | **8.7 s** | **4.3 s** | **1.6×** |
| 4 | 14.0 s | 3.5 s | 2.0×, with **high latency variance** (1.3 s to 13.9 s per call) |

Design implications:

- **`OLLAMA_NUM_PARALLEL=2` is the recommended daemon setting.** Free 60% throughput improvement with minimal complexity.
- **`=4` buys diminishing returns and unfair latency** — one request can finish in 1.3 s while another in the same batch takes 13.9 s. Bad for any workload mixing live UI with background work.
- **Worker pool size scales with `NUM_PARALLEL`.** With `NUM_PARALLEL=2`, set worker pool to 2; more workers buy nothing because Ollama batches the same way.

### E.4 Service state tracking

The service must track "which model is currently loaded" as live state, queryable from the worker loop before scheduling work. `ollama ps` is the authoritative source; cache the result for short TTL (~5 s) to avoid hammering the daemon.

Pre-dispatch logic:

- If pending request's strategy uses the currently-loaded model → dispatch immediately (no swap).
- If a different model → check `tier_2_pending` queue depth + last-batch-time; trigger swap-and-drain if threshold met, else hold the request and let it batch.

---

## Appendix D — Phase 0b label-strategy test corpus

Source these directly from the live DB at run time so the sample reflects current catalog reality. Three queries against `~/.organizer3/organizer.db`:

```sql
-- ~30 makers
SELECT DISTINCT maker FROM title_javdb_enrichment
 WHERE maker GLOB '*[ぁ-んァ-ヶ一-龯]*' AND maker != ''
 ORDER BY RANDOM() LIMIT 30;

-- ~30 publishers
SELECT DISTINCT publisher FROM title_javdb_enrichment
 WHERE publisher GLOB '*[ぁ-んァ-ヶ一-龯]*' AND publisher != ''
 ORDER BY RANDOM() LIMIT 30;

-- ~30 series, restricted to short non-explicit ones (label_basic territory)
SELECT DISTINCT series FROM title_javdb_enrichment
 WHERE series GLOB '*[ぁ-んァ-ヶ一-龯]*' AND series != ''
   AND length(series) < 30
   AND series NOT GLOB '*中出*' AND series NOT GLOB '*姦*'
   AND series NOT GLOB '*痴漢*' AND series NOT GLOB '*種付*'
 ORDER BY RANDOM() LIMIT 30;
```

**Run plan:** ~90 calls × 2 models = 180 total. At an upper-bound ~30 s/call this is ~90 minutes wall-clock per model serially. Score with the existing `score.sh` framework; the metrics that matter are **p95 latency** (not average), **CJK leak rate** (qwen3 had this; verify qwen2.5 doesn't), and **sanitization-suspect rate** (should be near-zero for both on bounded-vocab non-explicit content).

**Decision rule:** if qwen2.5's p95 is materially lower than gemma4's (≥30% lower) and quality on a manual spot-check is comparable, flip the `label_basic` default to qwen2.5. Otherwise keep gemma4.
