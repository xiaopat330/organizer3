package com.organizer3.translation;

/**
 * Row from the {@code stage_name_lookup} table.
 *
 * <p>The curated kanji→romaji mapping seeded from actress YAMLs at startup.
 * The unique key is {@code kanji_form} (NFKC-normalised).
 */
public record StageNameLookupRow(
        long id,
        String kanjiForm,
        String romanizedForm,
        String actressSlug,
        String source,
        String seededAt
) {}
