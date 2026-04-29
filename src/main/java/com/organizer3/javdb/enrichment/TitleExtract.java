package com.organizer3.javdb.enrichment;

import java.util.List;

/**
 * All fields extracted from a javdb title detail page.
 *
 * <p>This is the intermediate representation serialized as JSON to
 * {@code <dataDir>/javdb_raw/title/{slug}.json}. Extract liberally — all
 * identifiable fields are captured even if not currently projected into staging.
 *
 * <p>{@code castEmpty} is {@code true} when the page contained an Actor block but
 * no entries (genuine cast-not-listed). {@code castParseFailed} is {@code true}
 * when cast extraction threw an unexpected exception — these two flags drive the
 * 1E empty-cast trichotomy in the write-time gate.
 */
public record TitleExtract(
        String code,
        String javdbSlug,
        String titleOriginal,
        String releaseDate,
        Integer durationMinutes,
        String maker,
        String publisher,
        String series,
        Double ratingAvg,
        Integer ratingCount,
        List<String> tags,
        List<CastEntry> cast,
        String coverUrl,
        List<String> thumbnailUrls,
        String fetchedAt,
        boolean castEmpty,
        boolean castParseFailed
) {

    public record CastEntry(String slug, String name, String gender) {}
}
