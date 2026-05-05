# Phase 6 Title-Translation Strategy Spot-Check

> **Status: COMPLETE** — run 2026-05-04 against gemma4:e4b. Decision: use existing
> `label_basic` strategy for Phase 6a. No new strategy needed.

## Goal

Pick the translation strategy for full Japanese AV-title strings
(`title_javdb_enrichment.title_original`). Three candidates evaluated:

- `label_basic` — production prompt, tuned for short proper-noun labels.
- `prose` — production prompt, tuned for paragraph-structure bio text.
- `title` — Phase 6 candidate, tuned for one-line AV titles with name/code preservation.

## Method

`reference/translation_poc/phase6.sh` sampled 21 distinct titles (7 each from
short/medium/long buckets) from `title_javdb_enrichment.title_original`, ran
each through all three prompts on gemma4:e4b at temperature 0.2, and wrote
`phase6_gemma4_e4b.tsv`.

```bash
MODEL=gemma4:e4b ./phase6.sh
```

## Results

| Strategy    | n  | Avg latency | Avg out chars | Refusals | Sanitization slips | Verdict        |
|-------------|----|-------------|---------------|----------|--------------------|----------------|
| label_basic | 21 | **23.6 s**  | 113           | 0        | 0                  | **WINNER**     |
| prose       | 21 | 23.6 s      | 117           | 0        | 1 (creampie→"Live-In") | second |
| title       | 21 | 31.7 s (+35%) | 103         | 0        | partial            | rejected       |

All three produce clean single-line output with no preambles or
"here is the translation" framing — the existing prompts already work for
title-shaped input.

### Key contrast — sanitization on `中出しソープランドに堕ちた女子大生 湊莉久`

| Strategy | Output |
|---|---|
| label_basic | College student who fell for a soapland with internal ejaculation |
| prose | College Girl Falls into a Live-In Soapland, Riku Minato |
| title | College girl who fell into a wet-sex soapland, Riku Minato |

`prose` invented "Live-In Soapland" — a sanitization slip that drops the
explicit term entirely. `title` softened to "wet-sex." `label_basic` preserved
"internal ejaculation" — the most faithful rendering. This pattern is consistent
with `prose`'s tuning (smoother, more narrative) clashing with the catalog
requirement that explicit terms survive.

### Name preservation

All three strategies preserved actress names in the majority of cases. Quirks:

- `label_basic` and `prose` use Western order ("Mami Yuma", "Riku Minato").
- `title` occasionally inverts to JP order ("Aoi Sora", "Matsumoto Ichika").
- All three transliterate rather than canonicalize — Phase 6b's stage-name
  resolver will eventually correct these post-translation.

### Why `title` underperformed

The Phase 6 candidate `title` prompt was longer and more rule-laden ("Preserve
names verbatim", "Output ONE LINE", "Do not soften"). Three observations:

1. **Latency cost**: longer system prompt → +8 s/call without quality benefit.
2. **Verbatim instruction was ignored**: model still romanized names anyway.
3. **No measurable accuracy lift**: produced no better output than `label_basic`
   on the explicit-term axis or the name-preservation axis.

The lesson: prompt complexity beyond what's needed for shape ("one line, no
preamble") buys nothing on this model.

## Decision

**Use `label_basic` for Phase 6a title sweeps.** No new strategy version
required. This means:

- `TitleTranslationSweeper` submits requests with `strategyKey="label_basic"`.
- Existing strategy version, prompt, and tier-2 fallback (qwen2.5:14b) all
  apply — no `TranslationStrategySeeder` changes needed.
- The translation cache may already contain hits from prior manual bulk-submit
  runs that used `label_basic` — bonus warm cache.

## Next step

Implement `TitleTranslationSweeper` per spec §3 of
`spec/PROPOSAL_TRANSLATION_PHASE6.md`. The strategy-choice section of that
spec is now resolved — update before code lands.
