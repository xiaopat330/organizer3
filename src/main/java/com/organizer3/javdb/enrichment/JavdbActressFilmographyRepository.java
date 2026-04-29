package com.organizer3.javdb.enrichment;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence layer for per-actress javdb filmography caches (L2 behind the in-process L1 map).
 *
 * <p>Two backing tables: {@code javdb_actress_filmography} (one metadata row per actress) and
 * {@code javdb_actress_filmography_entry} (one row per (actress, code) pair).
 */
public interface JavdbActressFilmographyRepository {

    /** Returns the metadata row for this actress, or empty if she hasn't been persisted yet. */
    Optional<FilmographyMeta> findMeta(String actressSlug);

    /**
     * Returns the complete {@code productCode → titleSlug} map for this actress.
     * Returns an empty map if she hasn't been persisted yet.
     */
    Map<String, String> getCodeToSlug(String actressSlug);

    /**
     * Returns the title slug for a single (actress, code) pair, or empty if not found.
     */
    Optional<String> findTitleSlug(String actressSlug, String productCode);

    /**
     * Atomically replaces all data for this actress: drops the old metadata and entry rows,
     * then inserts the new ones in a single transaction. The DB always holds a complete
     * fetch — never a partial one.
     */
    void upsertFilmography(String actressSlug, FetchResult result);

    /** Drops all persisted data for this actress (metadata + entries). */
    void evict(String actressSlug);

    /**
     * Records a 404 outcome for this actress: upserts the metadata row with
     * {@code last_fetch_status = 'not_found'} and marks all cached entries {@code stale=1}.
     * Does not delete any entries — cached data is preserved for triage.
     *
     * @param fetchedAt ISO-8601 timestamp of the failed fetch attempt
     * @return number of entry rows marked stale
     */
    int markNotFound(String actressSlug, String fetchedAt);

    /**
     * Returns all actress slugs that have a persisted metadata row, in no particular order.
     * Used by export/archive tools that operate across the full filmography cache.
     */
    java.util.List<String> findAllActressSlugs();

    /**
     * Returns {@code true} if this actress's cached filmography is absent or past its TTL.
     *
     * <p>TTL is soft: if {@code lastReleaseDate} is more than 2 years ago the actress is
     * treated as "settled" (retired catalog) and the TTL is skipped — she won't be
     * re-fetched even after {@code ttlDays} elapse.
     *
     * @param ttlDays how many days before a fresh cache is considered stale
     * @param clock   injectable clock (use {@link Clock#systemUTC()} in production)
     */
    boolean isStale(String actressSlug, int ttlDays, Clock clock);
}
