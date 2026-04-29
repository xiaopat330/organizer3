package com.organizer3.javdb.enrichment;

import java.util.List;

/**
 * The outcome of one complete multi-page filmography fetch, ready to be persisted.
 *
 * @param fetchedAt       ISO-8601 timestamp of when the fetch completed
 * @param pageCount       total pages consumed
 * @param lastReleaseDate ISO date of the most recent title, or null when the parser
 *                        doesn't extract release dates (current state)
 * @param source          {@code "http"} | {@code "imported_backup"}
 * @param entries         deduplicated list of (productCode, titleSlug) pairs
 */
public record FetchResult(
        String fetchedAt,
        int pageCount,
        String lastReleaseDate,
        String source,
        List<FilmographyEntry> entries
) {}
