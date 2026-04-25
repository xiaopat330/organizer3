package com.organizer3.javdb.enrichment;

/**
 * Projected columns for a row in {@code javdb_title_staging}.
 */
public record JavdbTitleStagingRow(
        long titleId,
        String status,        // 'fetched' | 'not_found' | 'fetch_error'
        String javdbSlug,
        String rawPath,       // relative to dataDir; null when not_found
        String rawFetchedAt,
        String titleOriginal,
        String releaseDate,
        Integer durationMinutes,
        String maker,
        String publisher,
        String series,
        Double ratingAvg,
        Integer ratingCount,
        String tagsJson,
        String castJson,
        String coverUrl,
        String thumbnailUrlsJson
) {
    public static final String STATUS_FETCHED = "fetched";
    public static final String STATUS_NOT_FOUND = "not_found";
    public static final String STATUS_FETCH_ERROR = "fetch_error";
}
