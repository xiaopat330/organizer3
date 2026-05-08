package com.organizer3.repository;

import java.text.Normalizer;

/**
 * Normalizes {@code stage_name} values before they are written to the database.
 *
 * <p>All writes to the {@code actresses.stage_name} column must pass through
 * {@link #normalize(String)} so that whitespace drift and Unicode decomposition
 * divergence cannot accumulate silently. This class has no state and exposes a
 * single static method.
 *
 * <p>Normalization steps applied in order:
 * <ol>
 *   <li>Null passthrough — {@code null} in, {@code null} out.</li>
 *   <li>{@link String#trim()} — removes leading/trailing ASCII whitespace.</li>
 *   <li>Unicode NFC via {@link Normalizer#normalize(CharSequence, Normalizer.Form)} — collapses
 *       decomposed sequences (e.g., combining dakuten) to their precomposed form. This prevents
 *       single-codepoint divergence such as {@code 三上悠亜} vs {@code 三上悠亞}.</li>
 *   <li>Internal space removal — strips any remaining ASCII space ({@code U+0020}) and ideographic
 *       space ({@code U+3000}) characters. These are the whitespace classes observed in real data
 *       (e.g., {@code "椎名 そら"} stored but javdb cast_json uses {@code "椎名そら"}).</li>
 *   <li>Empty collapse — if the result is empty after all transforms, returns {@code null}.
 *       Callers that receive {@code null} should treat it as "clear stage_name".</li>
 * </ol>
 */
public final class StageNameNormalizer {

    private StageNameNormalizer() {
        // utility class — no instances
    }

    /**
     * Normalizes a stage name for storage.
     *
     * @param stageName the raw input (may be {@code null})
     * @return the normalized value, or {@code null} if the input was {@code null} or
     *         reduced to empty by normalization
     */
    public static String normalize(String stageName) {
        if (stageName == null) {
            return null;
        }

        // Step 1: trim leading/trailing ASCII whitespace
        String result = stageName.trim();

        // Step 2: NFC Unicode normalization (collapses decomposed sequences)
        result = Normalizer.normalize(result, Normalizer.Form.NFC);

        // Step 3: remove internal ASCII space (U+0020) and ideographic space (U+3000)
        result = result.replace(" ", "").replace("　", "");

        // Step 4: empty → null
        return result.isEmpty() ? null : result;
    }
}
