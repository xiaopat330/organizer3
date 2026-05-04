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
        long cacheFailed
) {}
