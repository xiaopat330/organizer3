package com.organizer3.translation;

/**
 * Basic operational statistics for the translation service.
 *
 * <p>Returned by {@link TranslationService#stats()}. A more detailed view is deferred
 * to Phase 4 (Tools UI page).
 */
public record TranslationServiceStats(
        long cacheTotal,
        long cacheSuccessful,
        long cacheFailed,
        int queuePending,
        int queueInFlight,
        int queueDone,
        int queueFailed,
        int queueTier2Pending,
        long stageNameLookupSize,
        long stageNameSuggestionsUnreviewed
) {
    /** Compact display format for logging. */
    @Override
    public String toString() {
        return String.format(
                "TranslationServiceStats{cache: total=%d ok=%d fail=%d, queue: pending=%d in_flight=%d done=%d failed=%d tier2_pending=%d, stage_names: lookup=%d unreviewed=%d}",
                cacheTotal, cacheSuccessful, cacheFailed, queuePending, queueInFlight, queueDone, queueFailed, queueTier2Pending,
                stageNameLookupSize, stageNameSuggestionsUnreviewed);
    }
}
