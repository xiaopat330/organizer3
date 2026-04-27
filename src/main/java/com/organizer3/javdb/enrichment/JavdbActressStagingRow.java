package com.organizer3.javdb.enrichment;

/**
 * Projected columns for a row in {@code javdb_actress_staging}.
 */
public record JavdbActressStagingRow(
        long actressId,
        String javdbSlug,
        String sourceTitleCode,
        String status,         // 'slug_only' | 'fetched' | 'fetch_error'
        String rawPath,
        String rawFetchedAt,
        String nameVariantsJson,
        String avatarUrl,
        String twitterHandle,
        String instagramHandle,
        Integer titleCount,
        String localAvatarPath
) {
    public static final String STATUS_SLUG_ONLY = "slug_only";
    public static final String STATUS_FETCHED = "fetched";
    public static final String STATUS_FETCH_ERROR = "fetch_error";
}
