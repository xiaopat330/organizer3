package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Projects extracted javdb data into staging table row shapes.
 *
 * <p>The extractor produces raw POJOs; the projector maps them to the flat
 * column layout expected by {@link JavdbStagingRepository}.
 */
public class JavdbProjector {

    private final ObjectMapper json;

    public JavdbProjector(ObjectMapper json) {
        this.json = json;
    }

    public JavdbTitleStagingRow projectTitle(long titleId, TitleExtract extract, String rawPath) {
        return new JavdbTitleStagingRow(
                titleId,
                JavdbTitleStagingRow.STATUS_FETCHED,
                extract.javdbSlug(),
                rawPath,
                extract.fetchedAt(),
                extract.titleOriginal(),
                extract.releaseDate(),
                extract.durationMinutes(),
                extract.maker(),
                extract.publisher(),
                extract.series(),
                extract.ratingAvg(),
                extract.ratingCount(),
                toJson(extract.tags()),
                toJson(extract.cast()),
                extract.coverUrl(),
                toJson(extract.thumbnailUrls())
        );
    }

    public JavdbActressStagingRow projectActress(long actressId, ActressExtract extract, String rawPath) {
        return new JavdbActressStagingRow(
                actressId,
                extract.slug(),
                null, // sourceTitleCode is set externally (from slug discovery)
                JavdbActressStagingRow.STATUS_FETCHED,
                rawPath,
                extract.fetchedAt(),
                toJson(extract.nameVariants()),
                extract.avatarUrl(),
                extract.twitterHandle(),
                extract.instagramHandle(),
                extract.titleCount()
        );
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize to JSON", e);
        }
    }
}
