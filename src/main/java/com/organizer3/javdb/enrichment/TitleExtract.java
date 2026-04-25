package com.organizer3.javdb.enrichment;

import java.util.List;

/**
 * All fields extracted from a javdb title detail page.
 *
 * <p>This is the intermediate representation serialized as JSON to
 * {@code <dataDir>/javdb_raw/title/{slug}.json}. Extract liberally — all
 * identifiable fields are captured even if not currently projected into staging.
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
        String fetchedAt
) {

    public record CastEntry(String slug, String name, String gender) {}
}
