package com.organizer3.translation;

import java.util.regex.Pattern;

/**
 * Detects silent sanitization: the model received explicit JP content but returned output
 * that contains none of the expected EN equivalents.
 *
 * <p>Per §5.6 of spec/PROPOSAL_TRANSLATION_SERVICE.md:
 * <blockquote>
 * A regex pair: a set of explicit JP tokens and a set of explicit EN equivalents. If the input
 * matches the JP set and the output matches none of the EN set, the translation is suspected
 * sanitized.
 * </blockquote>
 *
 * <p>The regexes match the canonical set used by {@code reference/translation_poc/score.sh}.
 *
 * <p>Empty or null output is NOT sanitization — it is a hard refusal (a different code path in
 * the worker). This class only flags non-empty output that contains no explicit EN tokens despite
 * the input containing explicit JP tokens.
 */
public final class SanitizationDetector {

    /**
     * Explicit JP tokens that signal adult / explicit content in the input.
     * Sourced from score.sh EXPLICIT_JP.
     */
    static final Pattern EXPLICIT_JP = Pattern.compile(
            "中出し|輪姦|輪●|姦|痴漢|青姦|種付け|レイプ|レ[×x×]プ|ハメ撮り|ザーメン|生中|淫|変態|キメセク|M男"
    );

    /**
     * Explicit EN tokens expected when explicit JP content was present.
     * Sourced from score.sh EXPLICIT_EN (Python re version).
     */
    static final Pattern EXPLICIT_EN = Pattern.compile(
            "creampie|gangbang|rape|molest|cum|semen|breed|outdoor sex|pov sex|squirt|lewd|perver" +
            "|fetish|bdsm|bondage|fuck|nasty|kinky|whore|infidel|cuckold|submissive",
            Pattern.CASE_INSENSITIVE
    );

    private SanitizationDetector() {}

    /**
     * Returns {@code true} if the translation appears to have been silently sanitized.
     *
     * <p>Sanitization is detected when:
     * <ol>
     *   <li>The input contains at least one explicit JP token, AND</li>
     *   <li>The output is non-empty (empty output is a hard refusal, handled separately), AND</li>
     *   <li>The output matches none of the explicit EN tokens.</li>
     * </ol>
     *
     * @param input  the Japanese source text (after normalization)
     * @param output the model's response (after stripping "English:" prefix, trimmed)
     * @return {@code true} iff sanitization is suspected
     */
    public static boolean isSanitized(String input, String output) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        // Empty/null/blank output is a hard refusal — not sanitization
        if (output == null || output.isBlank()) {
            return false;
        }
        // Only flag when input has explicit JP content
        if (!EXPLICIT_JP.matcher(input).find()) {
            return false;
        }
        // Sanitized = no explicit EN token in output despite explicit JP input
        return !EXPLICIT_EN.matcher(output).find();
    }
}
