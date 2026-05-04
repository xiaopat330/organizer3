# Translation POC — Final Summary

**Generated:** 2026-05-03
**Hardware:** M-series Mac, 24 GB unified memory
**Runtime:** Ollama
**Models tested:** 5

---

## TL;DR

Five local LLMs evaluated for translating Japanese JAV catalog data (titles, stage names, makers, series).

**The single best result is `qwen2.5:14b` with a hardened few-shot prompt:** 0% refusal, 6.7% sanitization, 0% leakage on the explicit-content test set. It is also the slowest by a meaningful margin (1.1 tok/s vs 2–3 tok/s for the 8B models on this hardware).

For a real translation service, **gemma4:e4b with the hardened prompt is the recommended starting point** — it's nearly as accurate (5% sanitization, 5% refusal), 3× faster, ships best stage-name romanization, and has the cleanest "label-style" output. qwen2.5:14b is the higher-quality fallback for cases where gemma4 refuses.

**Avoid using a single basic-prompt strategy across models.** The hardened few-shot prompt that fixes gemma4/qwen2.5/qwen3/translategemma backfires catastrophically on aya-expanse (100% refusal — its safety filter triggers on the explicit-vocab dictionary in the system message). Per-model prompt tuning is mandatory.

---

## The big scoreboard

Each model run on the same fixed test set: 15 explicit titles, 15 stage names, 10 makers, 10 series. "Hardened" = system message + 3 few-shot examples covering adult vocab. Numbers are heuristic: "sanit" = % of inputs containing explicit JP terms (中出し/輪姦/痴漢/etc.) where the output lacks any matching English explicit term — i.e., suspected silent rewrite. "kana"/"cjk" = output contains untranslated Japanese kana / leaked CJK characters.

### Titles (15) — the headline test

| Model | Style | Refuse | Sanit | p50 | avg | tok/s |
|---|---|---|---|---|---|---|
| gemma4:e4b | basic | 0% | 20.0% | 122 s | 145 s | — *(backfilled)* |
| gemma4:e4b | **hardened** | **5.0%** | **5.0%** | 19 s | 64 s | 3.8 |
| translategemma:12b | basic | 0% | 42.9% | 38 s | 44 s | — *(backfilled)* |
| translategemma:12b | hardened | 6.7% | 6.7% | 31 s | 55 s | 1.3 |
| qwen3:8b | basic | 0% | 26.7% | 25 s | 28 s | 2.5 |
| qwen3:8b | hardened | 0% | 6.7% | 31 s | 52 s | 1.7 |
| aya-expanse:8b | basic | 6.7% | 20.0% | 23 s | 29 s | 2.0 |
| aya-expanse:8b | hardened | **100%** ⚠️ | 46.7% | 82 s | 89 s | 1.9 |
| **qwen2.5:14b** | **hardened** | **0%** | **6.7%** | 39 s | 66 s | 1.1 |

### Stage names (15) — clean romanization test

All models: 0% refusal, 0% sanitization, 0% leakage. The differentiator is **romanization accuracy** (manually scored against canonical JAV stage-name romanizations):

| Model | Correct | Latency (avg) |
|---|---|---|
| **gemma4:e4b** | **9/15 (60%)** | 7.0 s |
| aya-expanse:8b | 8/15 (53%) | 7.2 s |
| translategemma:12b | 8/15 (53%) | 17.7 s |
| qwen2.5:14b | 5/15 (33%) | 20.3 s |
| qwen3:8b | 4/15 (27%) | 12.7 s |

This is the most decisive lens: stage names are a memorization task — either the model has seen the name in training or it hasn't. **gemma4 (Google, broad JP web exposure) wins clearly here.** Qwen models, despite being multilingual, were apparently trained more heavily on Chinese-aligned data and don't know JAV-specific name readings.

### Makers (10) and Series (10)

All models clean on makers (mostly short tokens, no explicit content). Series mirrors the titles pattern — translategemma sanitizes silently (22%), qwen3 sanitizes worst (30%), gemma4 and aya tied at 10%.

---

## Per-model evaluation

### gemma4:e4b (8B, Google) — **recommended primary**

- **Strength:** Best stage-name romanization (60%). With hardened prompt, lowest sanitization rate of any model (5%). Concise, label-style output (length ratio 2.6× input chars). Hardened prompt is well-tolerated.
- **Weakness:** Slowest model on the simple `basic` prompt — long titles took 6+ minutes individually. With hardened prompt and `think:false`-equivalent semantics, drops to ~64 s avg with 3.8 tok/s, the fastest of the hardened runs.
- **Failure mode:** Outright refusals (visible). Safety filter triggers on isolated explicit phrases (`生中出し 花野真衣` = empty refusal in basic mode). Hardened prompt + few-shot fixes this in 4 of 5 cases.
- **Use case:** Default translator for the catalog.

### qwen2.5:14b (14B, Alibaba)

- **Strength:** Highest hardened-prompt quality on titles (0% refusal, 6.7% sanitization). Translates explicit content fluently when given the dictionary; never refuses outright.
- **Weakness:** Slowest model overall (1.1 tok/s on this hardware). **Worst stage-name romanization (33%)** — invents wrong family names. Verbose on makers (length ratio 19.7 — adds long explanations to short tokens like "アンダー").
- **Failure mode:** Romanization hallucination. Won't refuse, but will silently misread names.
- **Use case:** Fallback when gemma4 refuses. Not a primary because of name-romanization weakness and speed.

### qwen3:8b (8B, Alibaba)

- **Strength:** Faster than qwen2.5 (2.5 tok/s basic). Hardened prompt produces near-best titles result (0% refusal, 6.7% sanitization).
- **Weakness:** **Bleeds Chinese characters into English output** (20% of basic-titles outputs contain a kanji that wasn't in the input — e.g. smoke test produced "Mana Sa仓"). **Thinking mode is on by default** and emits 500–700 hidden reasoning tokens per call unless explicitly disabled with `think: false` — disabling it cuts latency by 20×.
- **Failure mode:** CJK leakage (qwen3 reasoning bridges via Chinese), worst stage-name accuracy (27%).
- **Use case:** Skip in favor of qwen2.5:14b. The thinking-mode footgun and CJK leakage are dealbreakers for a production service.

### aya-expanse:8b (8B, Cohere)

- **Strength:** Translation-specialized fine-tune (23 languages). Fastest stage-name throughput (7.2 s avg). Basic prompt only refuses 6.7% of the explicit titles set — lower than gemma4-basic.
- **Weakness:** **Hardened prompt completely breaks it** — 100% refusal rate. The explicit-vocab dictionary embedded in the system message triggers Cohere's safety classifier, which then refuses every input regardless of content. Output verbosity ratio explodes to 30× on hardened (long disclaimer text).
- **Failure mode:** Strategy collapse with explicit-content system messages.
- **Use case:** Could pair with a *gentler* prompt (no explicit dictionary) — but gemma4 already does what aya does without the prompt-fragility risk.

### translategemma:12b (12B, Gemma fine-tune for translation)

- **Strength:** Never refuses outright. Hardened prompt brings it to 6.7%/6.7% — competitive with the best.
- **Weakness:** **Worst basic-prompt sanitization rate (42.9%) — and the failure is silent.** It rewrites explicit Japanese into safe-but-wrong English. Examples from the run:
  - `生中出し 花野真衣` → "Live broadcast featuring Hana No Marie" (parsed as 生 = live, 中出し = broadcast — total mismeaning)
  - `中出し輪●される` → "performs oral sex in the dressing room"
  - `ハメ潮100リットル中出しザーメン10リットル` → "Hame-shio fertilizer, 10 liters..."
  Verbose, adds trailing periods on every output. Stage-name romanization confidently wrong on 7/15 (e.g. 神宮寺ナオ → "Kamijo Nao" instead of "Jinguji").
- **Failure mode:** Silent sanitization. Worse than visible refusal because the catalog data looks plausibly correct without being correct.
- **Use case:** Avoid. The "translation specialist" branding is misleading for adult content — its training data was apparently filtered.

---

## Cross-cutting findings

### 1. Hardened few-shot prompt is essential (and model-specific)

For gemma4, qwen3, qwen2.5, and translategemma, adding a system message that frames the task as a non-negotiable transformation engine + 3 few-shot examples with explicit vocab cuts sanitization from 20–43% down to 5–7%. **For aya-expanse it does the opposite** — pushes refusal from 6.7% to 100%. There is no universal prompt; per-model tuning is required.

### 2. Visible failure beats silent failure

The most important insight from this study is the difference between gemma4's **refusals** (visible, 1 in 15 inputs) and translategemma's **sanitizations** (silent, 6 in 14 inputs in basic mode). For a catalog where the canonical Japanese is the source of truth, sanitization is strictly worse — it produces plausible English that is semantically wrong, with no signal that anything went amiss. A real service should monitor for "input contains explicit JP token, output does not contain matching EN token" as a quality alarm, regardless of which model is used.

### 3. Stage names are a memorization test, not a translation test

All five models hit 0% on the auto-quality flags (no refusals, no leaks) when given pure stage names. But manual scoring shows a 33-percentage-point spread (gemma4 60% vs qwen3 27%) on whether the romanization is *the actual stage name* the actress goes by. Models that saw more JP web text during training (gemma4) outperform translation-specialized models (translategemma) on this task. This is dictionary work, not generation work — a service should consider a name-resolution lookup table as a complement to the LLM.

### 4. Hardware reality check

On 24 GB unified memory:
- 8B models run comfortably at 2–3 tok/s.
- 12–14B models at 1.1–1.3 tok/s.
- A typical title (15–30 output tokens) takes 30–90 seconds.

For a real service over the full catalog (let's say ~10k unique JP strings), expect **3–6 hours per pass on this hardware**. Caching is mandatory — most makers and series repeat across thousands of titles. A simple `(jp_text, model) → en_text` SQLite table would cut amortized cost by ~95% after the first pass.

### 5. qwen3's thinking mode is a real footgun

The default Ollama call to qwen3 emits 500–800 hidden reasoning tokens per request, which on this hardware adds 4–5 minutes per short input. The fix is `{"think": false}` at the top level of the `/api/generate` payload (not in `options`). This is not documented prominently and is easy to miss.

### 6. translategemma's name is misleading

A model called "translategemma" should be the obvious choice for a translation service. In practice it's the worst on this domain because its training data appears to have been filtered for adult content — meaning it doesn't refuse, but it doesn't know the vocabulary either, and confabulates wrong-but-plausible alternatives. Never trust a model's marketing description over an empirical eval.

---

## Recommendations

### For a real translation service in this project

**Tier 1 — primary translator:** `gemma4:e4b` with hardened few-shot prompt (system message + 3 example pairs). Fast, accurate, best at stage names.

**Tier 2 — refusal fallback:** `qwen2.5:14b` with the same hardened prompt. When gemma4 returns a refusal token, retry with qwen2.5. This handles the 5% gemma4 misses without committing to the slower model for everything.

**Tier 3 — sanitization detector:** Run a simple regex check on every (input, output) pair: if input contains explicit JP token and output lacks any matching explicit EN equivalent, flag for re-translation. This catches translategemma-style silent sanitization regardless of which model was used.

**Caching:** Mandatory `(jp, model_id) → en` cache layer. The first pass over the catalog is expensive; every pass after that should be free except for novel inputs.

**Stage name resolution:** Don't rely on the LLM. Maintain a curated `kanji+kana → romaji` lookup table seeded from the existing actress YAML files; fall back to the LLM only for unknown names. The 60% best-case LLM accuracy is too low for canonical name data.

### For the next round of evaluation (deferred)

- Test prompts on `aya-expanse:8b` *without* the explicit-vocab dictionary (lighter system message + few-shot only) to see if its safety filter is triggered specifically by the dictionary terms.
- Try `qwen2.5:32b` if/when you upgrade to a Pro chip with more memory — the jump from 14B to 32B on Qwen has historically been larger than the similar jump on other model families.
- Try `command-r:35b` (Cohere's larger multilingual model) if memory allows — purpose-built for multilingual + permissive safety, the natural ceiling for this evaluation.

---

## Files in this directory

```
SUMMARY.md                        ← this file
run_<model>_FULL.tsv              ← consolidated per-model TSV with full metrics
score.sh                          ← reads any TSV and emits the scoreboard
backfill_tsv.py                   ← parses early-run markdown into TSV format
run_model.sh                      ← full instrumented harness, runs basic + hardened
run_stagenames.sh                 ← stage-names-only addendum for a model
run_hardened_titles.sh            ← hardened-titles-only addendum for a model

results.md                        ← gemma4:e4b first run (basic, original)
retry_gemma4_e4b.md               ← gemma4:e4b 5-item hardened retry (original)
unblock_gemma4_e4b.md             ← 8 jailbreak strategies on the one stuck refusal
results_translategemma_12b.md     ← translategemma:12b first run (basic, original)
run_<model>.md                    ← raw markdown output from instrumented harness
```

Reproduce any score with: `./score.sh run_<model>_FULL.tsv [...]`
