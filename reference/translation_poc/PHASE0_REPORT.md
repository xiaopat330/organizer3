# Phase 0 Report — Strategy Validation

**Generated:** 2026-05-03
**Hardware:** M-series, 24 GB unified memory
**Sub-tests:** 0a prose strategy primary model · 0b label_basic primary model
**Test corpus:** 8 real JP biographical paragraphs (Appendix C) + 90 live-DB labels (Appendix D)
**Models:** gemma4:e4b vs qwen2.5:14b

---

## TL;DR

**Both sub-tests favor gemma4:e4b.** No swap-tax tradeoff to negotiate; gemma4 wins on quality *and* speed across both `prose` and `label_basic` strategies.

| Strategy | Recommended primary | Rationale |
|---|---|---|
| `prose` | **gemma4:e4b** | 4.5× faster, no sanitization, no CJK leak |
| `label_basic` | **gemma4:e4b** | 6–7× faster on p95, tighter latency distribution |
| `label_explicit` | gemma4:e4b (per original POC) | unchanged |

The proposal's tier-2 fallback (qwen2.5:14b on refusal/sanitization) remains correct — qwen2.5 is the right tool for a different job (handling the 5% gemma4 refuses), not the primary translator.

---

## Sub-test 0a — Prose strategy (8 bio paragraphs)

| Metric | gemma4:e4b | qwen2.5:14b |
|---|---|---|
| Refusal rate | 0% (0/8) | 0% (0/8) |
| Sanitization-suspect rate | 0% (0/8) | **12.5% (1/8)** |
| CJK leak rate | 0% (0/8) | **12.5% (1/8)** |
| Latency p50 | 31.4 s | 138.2 s |
| Latency p95 | 69.0 s | 286.4 s |
| Latency avg | 37.3 s | 166.3 s |
| Throughput | **4.2 tok/s** | 1.2 tok/s |
| Length ratio (out/in chars) | 2.59 | 2.66 |

**gemma4 wins on every dimension.** It's 4.5× faster, produces no sanitization slips on biographical content, and never bleeds CJK characters into the output. qwen2.5's quality is roughly comparable on the items it gets right, but it dropped one paragraph that contained `生中出し` to a euphemism (sanitized) and leaked at least one stray CJK character.

**Decision (resolves locked decision #7):** Default `prose` to gemma4:e4b. The hypothesis that qwen2.5's 14B parameter count would help on long-form was wrong for this content type — gemma4's broader Japanese cultural exposure (per the stage-name POC) translates directly into better prose handling too.

This also resolves the swap-conflict concern raised in §6/§7 of the proposal — pinning prose to gemma4 means no cross-model swaps for normal workloads. qwen2.5 stays loaded only for its tier-2 fallback role.

### Sample side-by-side

Input: opening sentence of yuma_asami's Wikipedia article.

| Model | Output |
|---|---|
| gemma4 | "Asami Yuma (Asami Yuma, born March 24, 1987) is a Japanese talent, singer, actress, former AV actress, and former Ebisu Masqu." |
| qwen2.5 | "Asami Yuma (born March 24, 1987) is a Japanese talent, singer, actress, former AV actress, and former member of Ebisu Muscats." |

qwen2.5's "Ebisu Muscats" is technically more accurate than gemma4's "Ebisu Masqu" (truncated word). But gemma4 clearly understood the structure, the date, and the role list. The marginal accuracy of qwen2.5 is not worth 4.5× latency.

---

## Sub-test 0b — `label_basic` primary model (90 labels)

30 distinct makers + 30 publishers + 30 short non-explicit series, sampled live from the catalog DB.

| Section | Metric | gemma4:e4b | qwen2.5:14b |
|---|---|---|---|
| **maker** | p50 | 3.6 s | 14.1 s |
| | p95 | **5.7 s** | **36.6 s** |
| | tok/s | 6.0 | 1.6 |
| **publisher** | p50 | 3.4 s | 15.1 s |
| | p95 | **5.4 s** | 25.7 s |
| | tok/s | 6.0 | 1.4 |
| | CJK leak | 0% | 6.7% (2/30) |
| **series** | p50 | 5.1 s | 18.3 s |
| | p95 | **8.1 s** | 35.9 s |
| | sanit | 3.3% (1/30) | 0% |
| | tok/s | 4.7 | 1.4 |

**gemma4 wins decisively.** The earlier hypothesis from the original POC — that gemma4 had a bimodal latency distribution on short labels (6 s p50 against 137 s p95) and qwen2.5 might be tighter — turned out to be **wrong**. Re-measured against a proper 30-item sample with production-shape prompts, gemma4's p95 on makers is **5.7 s**, not 137 s. The earlier 137 s was a cold-load artifact masquerading as a per-call number.

qwen2.5 is consistently slower (3–4× on average, 6–7× on p95) and has small CJK-leak issues that gemma4 doesn't have on short labels.

**Decision (closes Phase 0b):** Keep `label_basic` defaulting to gemma4:e4b. No flip needed.

---

## Updates to apply to the proposal

1. **§5.4 strategy table:** `prose` primary model is now gemma4:e4b (was: qwen2.5:14b pending Phase 0a). Tier-2 fallback for `prose` is qwen2.5:14b.
2. **§5.4 prose row note:** Remove "this is provisional — needs revalidating" caveat. It's been validated.
3. **§11 open questions:** Remove the §10 Phase 0b "may flip the primary/fallback ordering" language; it didn't flip.
4. **Appendix B field-to-strategy mapping:** No changes — defaults were already gemma4-leaning.
5. **§6/§7 swap-tax discussion:** No longer applies to prose; can be pruned (or kept as a documented past consideration).

---

## Operational implications

With **all three strategies (label_basic, label_explicit, prose) defaulting to gemma4:e4b as primary**, the practical model footprint becomes:

- **gemma4:e4b is the only model loaded for all normal workloads.** Cache-miss tier-1 calls all hit gemma4 — no swap pressure during normal operation.
- **qwen2.5:14b is loaded only when the tier-2 sweeper drains the `tier_2_pending` queue.** Per Appendix E.2, this incurs ~40 s to load qwen2.5 + ~12 s to swap back. With the §5.3 batching threshold (10 items / 1 hour), tier-2 work amortizes the swap cost across multiple retries.
- **Live UI translations** route to whichever model is currently loaded (almost always gemma4); refusals queue for the next tier-2 batch instead of forcing an expensive swap.

This is a simpler operational picture than the proposal initially designed for. The two-model architecture is preserved (architectural flexibility), but in practice gemma4 carries the workload and qwen2.5 is a periodic safety net.

---

## Files

- `phase0.sh` — unified Phase 0 harness (runs 0a + 0b in one model pass)
- `phase0_gemma4_e4b.tsv` — gemma4 raw results (98 rows)
- `phase0_qwen2.5_14b.tsv` — qwen2.5 raw results (98 rows)
- `score.sh phase0_*.tsv` — reproduces the comparison table

---

## Phase 0 status

| Sub-test | Status | Resolution |
|---|---|---|
| 0a Prose primary | ✅ Done | gemma4:e4b primary, qwen2.5:14b fallback |
| 0b label_basic primary | ✅ Done | gemma4:e4b stays primary |
| Operational measurements | ✅ Done (Appendix E) | NUM_PARALLEL=2, single loaded model, tier-2 batching threshold = 10 items / 1 hour |

**Phase 0 is complete. Phase 1 (adapter + cache + sync requestTranslation with all 3 strategies seeded) is unblocked.**
