package com.organizer3.translation.repository;

import com.organizer3.translation.TranslationCacheRow;

import java.util.Optional;

/**
 * Persistence operations for {@link TranslationCacheRow} rows.
 *
 * <p>The cache key is {@code (sourceHash, strategyId)} where {@code sourceHash} is the SHA-256
 * of the NFKC-normalised, trimmed, whitespace-collapsed source text.
 */
public interface TranslationCacheRepository {

    /**
     * Look up a cache entry by the normalised source hash and strategy.
     * Returns empty if not yet cached.
     */
    Optional<TranslationCacheRow> findByHashAndStrategy(String sourceHash, long strategyId);

    /**
     * Insert a cache row. The {@code (sourceHash, strategyId)} pair must not already exist;
     * callers should check {@link #findByHashAndStrategy} first or use upsert if the caller
     * controls the write path.
     *
     * @return the assigned row id
     */
    long insert(TranslationCacheRow row);

    /**
     * Update an existing cache row's translation outcome fields.
     * Used when a previously-failed row is retried successfully.
     */
    void updateOutcome(long cacheRowId,
                       String englishText,
                       String failureReason,
                       String retryAfter,
                       Integer latencyMs,
                       Integer promptTokens,
                       Integer evalTokens,
                       Long evalDurationNs);

    /**
     * Set the human-corrected text on a cache row (decision #6 — human correction wins).
     * Does not overwrite {@code english_text} — both are preserved for auditing.
     */
    void updateHumanCorrection(long cacheRowId, String humanCorrectedText, String humanCorrectedAt);

    /** Total number of rows in the cache. */
    long countTotal();

    /** Number of rows with a non-null {@code english_text}. */
    long countSuccessful();

    /** Number of rows with a non-null {@code failure_reason}. */
    long countFailed();
}
