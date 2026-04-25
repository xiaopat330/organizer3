package com.organizer3.web;

import com.organizer3.javdb.enrichment.EnrichmentRunner;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Read-only queries for the javdb Discovery screen.
 *
 * Pulls from actresses, title_actresses, javdb_title_staging, javdb_actress_staging,
 * and javdb_enrichment_queue — no mutations.
 */
public class JavdbDiscoveryService {

    private final Jdbi jdbi;
    private final EnrichmentRunner runner;

    public JavdbDiscoveryService(Jdbi jdbi, EnrichmentRunner runner) {
        this.jdbi   = jdbi;
        this.runner = runner;
    }

    // ── Response records ───────────────────────────────────────────────────

    public record ActressRow(
            long id,
            String canonicalName,
            String stageName,
            int totalTitles,
            int enrichedTitles,
            String actressStatus   // null | 'slug_only' | 'fetched'
    ) {}

    public record TitleRow(
            long titleId,
            String code,
            String status,         // null when no staging row
            String javdbSlug,
            String titleOriginal,
            String releaseDate,
            String maker,
            String publisher
    ) {}

    public record ProfileRow(
            String javdbSlug,
            String status,
            String rawFetchedAt,
            String nameVariantsJson,
            String avatarUrl,
            String twitterHandle,
            String instagramHandle,
            Integer titleCount
    ) {}

    public record QueueStatus(int pending, int inFlight, int failed, boolean paused) {}

    public record ConflictRow(
            long titleId,
            String code,
            String ourActressName,
            String castJson
    ) {}

    // ── Queries ────────────────────────────────────────────────────────────

    /**
     * Returns all actresses that have at least one title, ordered by canonical name.
     * Each row carries title counts and the actress's current staging status.
     */
    public List<ActressRow> listActresses() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT
                  a.id,
                  a.canonical_name,
                  a.stage_name,
                  COUNT(DISTINCT ta.title_id)                                                       AS total_titles,
                  COUNT(DISTINCT CASE WHEN jts.status = 'fetched' THEN ta.title_id END)             AS enriched_titles,
                  jas.status                                                                         AS actress_status
                FROM actresses a
                JOIN title_actresses ta ON ta.actress_id = a.id
                LEFT JOIN javdb_title_staging jts ON jts.title_id = ta.title_id
                LEFT JOIN javdb_actress_staging jas ON jas.actress_id = a.id
                GROUP BY a.id, a.canonical_name, a.stage_name, jas.status
                ORDER BY a.canonical_name
                """)
                .map((rs, ctx) -> new ActressRow(
                        rs.getLong("id"),
                        rs.getString("canonical_name"),
                        rs.getString("stage_name"),
                        rs.getInt("total_titles"),
                        rs.getInt("enriched_titles"),
                        rs.getString("actress_status")
                ))
                .list());
    }

    /**
     * Returns the title staging rows for the actress's titles, null status when no
     * staging row has been created for a given title yet.
     */
    public List<TitleRow> getActressTitles(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT
                  t.id   AS title_id,
                  t.code,
                  jts.status,
                  jts.javdb_slug,
                  jts.title_original,
                  jts.release_date,
                  jts.maker,
                  jts.publisher
                FROM title_actresses ta
                JOIN titles t ON t.id = ta.title_id
                LEFT JOIN javdb_title_staging jts ON jts.title_id = ta.title_id
                WHERE ta.actress_id = :actressId
                ORDER BY t.code
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> new TitleRow(
                        rs.getLong("title_id"),
                        rs.getString("code"),
                        rs.getString("status"),
                        rs.getString("javdb_slug"),
                        rs.getString("title_original"),
                        rs.getString("release_date"),
                        rs.getString("maker"),
                        rs.getString("publisher")
                ))
                .list());
    }

    /**
     * Returns the actress staging profile row, or null if none exists.
     */
    public ProfileRow getActressProfile(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT javdb_slug, status, raw_fetched_at, name_variants_json,
                       avatar_url, twitter_handle, instagram_handle, title_count
                FROM javdb_actress_staging
                WHERE actress_id = :actressId
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> new ProfileRow(
                        rs.getString("javdb_slug"),
                        rs.getString("status"),
                        rs.getString("raw_fetched_at"),
                        rs.getString("name_variants_json"),
                        rs.getString("avatar_url"),
                        rs.getString("twitter_handle"),
                        rs.getString("instagram_handle"),
                        rs.getObject("title_count") != null ? rs.getInt("title_count") : null
                ))
                .findOne()
                .orElse(null));
    }

    /**
     * Returns titles in conflict for the actress: enriched titles where no javdb cast entry's
     * slug maps back to this actress's javdb_actress_staging row.
     */
    public List<ConflictRow> getActressConflicts(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT t.id AS title_id, t.code, a.canonical_name AS our_actress_name, ts.cast_json
                FROM javdb_title_staging ts
                JOIN titles t ON t.id = ts.title_id
                JOIN title_actresses ta ON ta.title_id = t.id AND ta.actress_id = :actressId
                JOIN actresses a ON a.id = :actressId
                WHERE ts.status = 'fetched'
                  AND NOT EXISTS (
                    SELECT 1 FROM json_each(ts.cast_json) je
                    JOIN javdb_actress_staging jas
                        ON jas.javdb_slug = json_extract(je.value, '$.slug')
                    WHERE jas.actress_id = :actressId
                  )
                ORDER BY t.code
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> new ConflictRow(
                        rs.getLong("title_id"),
                        rs.getString("code"),
                        rs.getString("our_actress_name"),
                        rs.getString("cast_json")
                ))
                .list());
    }

    /**
     * Returns current queue counts broken down by status.
     */
    public QueueStatus getQueueStatus() {
        boolean isPaused = runner.isPaused();
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT
                  SUM(CASE WHEN status = 'pending'   THEN 1 ELSE 0 END) AS pending,
                  SUM(CASE WHEN status = 'in_flight' THEN 1 ELSE 0 END) AS in_flight,
                  SUM(CASE WHEN status = 'failed'    THEN 1 ELSE 0 END) AS failed
                FROM javdb_enrichment_queue
                """)
                .map((rs, ctx) -> new QueueStatus(
                        rs.getInt("pending"),
                        rs.getInt("in_flight"),
                        rs.getInt("failed"),
                        isPaused
                ))
                .one());
    }
}
