package com.organizer3.javdb.enrichment;

import java.util.List;

/**
 * All fields extracted from a javdb actress profile page.
 *
 * <p>This is the intermediate representation serialized as JSON to
 * {@code <dataDir>/javdb_raw/actress/{slug}.json}.
 */
public record ActressExtract(
        String slug,
        List<String> nameVariants,
        String avatarUrl,
        String twitterHandle,
        String instagramHandle,
        Integer titleCount,
        String fetchedAt
) {}
