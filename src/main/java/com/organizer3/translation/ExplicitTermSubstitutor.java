package com.organizer3.translation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-substitutes known explicit Japanese terms with their English equivalents
 * before the prompt is sent to the LLM.
 *
 * <p>Both gemma4:e4b and qwen2.5:14b struggle with explicit JAV titles: gemma
 * outright refuses to translate them, and qwen silently drops the explicit
 * terms (producing output that reads cleanly but is missing meaning). Both
 * outcomes get caught by {@link SanitizationDetector} and recorded as
 * permanent failures (cf. PRs #66/#67 + the {@code sanitized_both_tiers}
 * dashboard tile).
 *
 * <p>This pre-substitution sidesteps both problems by handing the model an
 * input where the explicit kanji has already been replaced with the English
 * term we want to see in the output. The model then translates the
 * surrounding context normally and threads our substituted English term
 * through verbatim. The detector passes because the EN token is now present.
 *
 * <p>Validated empirically against HMN-035
 * ({@code 放課後の乳首開発ハーレム中出し倶楽部 …}) on 2026-05-05: with
 * {@code 中出し→creampie} substitution applied, qwen produced
 * "After-school nipple development harem creampie club …" (detector pass);
 * gemma still refused — but the tier-1→tier-2 cascade then succeeds via
 * qwen, which is the relevant outcome.
 *
 * <p><b>Substitution order matters.</b> Longer patterns must be tried before
 * shorter substrings to prevent partial replacement (e.g. {@code 生中出し}
 * before {@code 中出し}). The {@link LinkedHashMap} below is iterated in
 * insertion order; keep longer keys first.
 *
 * <p><b>Conservatism.</b> Single common kanji that appear in many compounds
 * ({@code 姦}, {@code 淫}) are deliberately omitted to avoid clobbering
 * legitimate non-explicit compounds. Add them later only if a real false
 * negative is observed and the compound risk is judged acceptable.
 */
public final class ExplicitTermSubstitutor {

    /** JP → EN substitutions. Order: longer keys first (longest-match wins). */
    private static final Map<String, String> SUBSTITUTIONS;
    static {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        // Multi-char compounds first
        m.put("生中出し",   "raw creampie");
        m.put("ハメ撮り",   "POV sex");
        m.put("キメセク",   "drug-fueled sex");
        m.put("中出し",     "creampie");
        m.put("ザーメン",   "semen");
        m.put("種付け",     "breeding");
        m.put("痴漢",       "molestation");
        m.put("青姦",       "outdoor sex");
        m.put("輪姦",       "gangbang");
        m.put("輪●",        "gangbang");
        m.put("レイプ",     "rape");
        m.put("レ×プ",     "rape");      // full-width × censoring variant
        m.put("レxプ",      "rape");      // ASCII x variant
        m.put("生中",       "raw creampie");
        m.put("変態",       "pervert");
        m.put("M男",        "submissive male");
        SUBSTITUTIONS = m;
    }

    private ExplicitTermSubstitutor() {}

    /**
     * Replaces every known explicit JP term in {@code input} with its English
     * equivalent. Returns {@code input} unchanged if it is null, empty, or
     * contains none of the patterns.
     *
     * <p>The result is a mixed JP/EN string suitable for direct use as the
     * model prompt — both gemma and qwen handle mixed-language input
     * gracefully.
     */
    public static String substitute(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input;
        for (Map.Entry<String, String> entry : SUBSTITUTIONS.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
