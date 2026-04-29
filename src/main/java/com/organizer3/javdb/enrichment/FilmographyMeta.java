package com.organizer3.javdb.enrichment;

/**
 * Metadata row for a persisted actress filmography (one row per actress in
 * {@code javdb_actress_filmography}).
 *
 * @param fetchedAt       ISO-8601 timestamp when the filmography was last fetched/upserted
 * @param pageCount       number of actress pages consumed on the last fetch
 * @param lastReleaseDate ISO date of the most recent title in the filmography, or null if unknown
 * @param source          {@code "http"} or {@code "imported_backup"}
 * @param lastDriftCount  number of drift events (changed/vanished-but-referenced pairs) on last re-fetch
 * @param lastFetchStatus last fetch outcome: {@code "ok"} | {@code "not_found"} | {@code "fetch_failed"}
 */
public record FilmographyMeta(
        String actressSlug,
        String fetchedAt,
        int pageCount,
        String lastReleaseDate,
        String source,
        int lastDriftCount,
        String lastFetchStatus
) {}
