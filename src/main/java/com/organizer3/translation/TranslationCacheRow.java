package com.organizer3.translation;


/**
 * Row from the {@code translation_cache} table.
 *
 * <p>The unique key is {@code (sourceHash, strategyId)}. {@code sourceText} stores the
 * NFKC-normalised form of the input (not the raw input).
 *
 * <p>Lookup priority per §5.5.1: {@code humanCorrectedText} > {@code englishText} > miss.
 */
public record TranslationCacheRow(
        long id,
        String sourceHash,
        String sourceText,
        long strategyId,
        String englishText,
        String humanCorrectedText,
        String humanCorrectedAt,
        String failureReason,
        String retryAfter,
        Integer latencyMs,
        Integer promptTokens,
        Integer evalTokens,
        Long evalDurationNs,
        String cachedAt
) {
    /**
     * Returns the best available translation per the lookup-priority rule.
     * Returns {@code null} if neither a human correction nor an LLM translation is present.
     */
    public String bestTranslation() {
        if (humanCorrectedText != null) return humanCorrectedText;
        return englishText;
    }
}
