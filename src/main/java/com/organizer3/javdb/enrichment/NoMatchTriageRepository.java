package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository queries for the no-match enrichment triage UI.
 *
 * <p>"No match" titles are those whose enrichment queue row has
 * {@code status='failed'} and {@code last_error='no_match_in_filmography'}.
 * This class provides read + repair operations for those rows.
 */
@RequiredArgsConstructor
public class NoMatchTriageRepository {

    private final Jdbi jdbi;

    // ── list ──────────────────────────────────────────────────────────────────

    /**
     * Returns all no-match queue rows joined with title, actress, and location data.
     * Rows with multiple actress links surface each actress as a separate entry so the
     * caller can decide which to display.
     *
     * <p>When a title has no {@code title_actresses} row the actress columns are null
     * (the "orphan" sub-flow).
     */
    public List<NoMatchRow> listNoMatchRows() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT
                    q.id            AS queue_id,
                    t.id            AS title_id,
                    t.code          AS code,
                    t.base_code     AS base_code,
                    a.id            AS actress_id,
                    a.stage_name    AS actress_stage_name,
                    tl.path         AS folder_path,
                    tl.volume_id    AS volume_id,
                    q.attempts      AS attempts,
                    q.updated_at    AS updated_at
                FROM javdb_enrichment_queue q
                JOIN titles t
                  ON t.id = q.target_id AND q.job_type = 'fetch_title'
                LEFT JOIN title_actresses ta
                  ON ta.title_id = t.id
                LEFT JOIN actresses a
                  ON a.id = ta.actress_id
                 AND COALESCE(a.is_sentinel, 0) = 0
                LEFT JOIN title_locations tl
                  ON tl.title_id = t.id
                WHERE q.status = 'failed'
                  AND q.last_error = 'no_match_in_filmography'
                ORDER BY t.code ASC, a.stage_name ASC
                """)
                .map((rs, ctx) -> {
                    long titleId = rs.getLong("title_id");
                    long actressIdRaw = rs.getLong("actress_id");
                    Long actressId = rs.wasNull() ? null : actressIdRaw;
                    return new NoMatchRow(
                            rs.getLong("queue_id"),
                            titleId,
                            rs.getString("code"),
                            rs.getString("base_code"),
                            actressId,
                            rs.getString("actress_stage_name"),
                            rs.getString("folder_path"),
                            rs.getString("volume_id"),
                            rs.getInt("attempts"),
                            rs.getString("updated_at"));
                })
                .list());
    }

    // ── candidate lookup ──────────────────────────────────────────────────────

    /**
     * Searches {@code javdb_actress_filmography_entry} for all actresses whose cached
     * filmography contains {@code productCode}. Excludes sentinel actresses.
     *
     * <p>This is the "try other actress" candidate pool: cheap indexed local lookup.
     */
    public List<FilmographyCandidate> findActressesByFilmographyCode(String productCode) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT
                    a.id                   AS actress_id,
                    a.stage_name           AS stage_name,
                    jas.javdb_slug         AS javdb_slug,
                    e.title_slug           AS title_slug,
                    f.fetched_at           AS fetched_at,
                    f.last_release_date    AS last_release_date
                FROM javdb_actress_filmography_entry e
                JOIN javdb_actress_filmography f
                  ON f.actress_slug = e.actress_slug
                JOIN javdb_actress_staging jas
                  ON jas.javdb_slug = e.actress_slug
                JOIN actresses a
                  ON a.id = jas.actress_id
                WHERE e.product_code = :code
                  AND COALESCE(a.is_sentinel, 0) = 0
                ORDER BY a.stage_name ASC
                """)
                .bind("code", productCode)
                .map((rs, ctx) -> new FilmographyCandidate(
                        rs.getLong("actress_id"),
                        rs.getString("stage_name"),
                        rs.getString("javdb_slug"),
                        rs.getString("title_slug"),
                        rs.getString("fetched_at"),
                        rs.getString("last_release_date")))
                .list());
    }

    // ── repair operations ─────────────────────────────────────────────────────

    /**
     * Marks the no-match queue row as cancelled with
     * {@code last_error='user_marked_no_javdb_data'} — the "mark resolved" action.
     *
     * @return true if a row was updated (false = already gone or not in failed state)
     */
    public boolean markResolved(long titleId) {
        int rows = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status      = 'cancelled',
                    last_error  = 'user_marked_no_javdb_data',
                    updated_at  = :now
                WHERE job_type = 'fetch_title'
                  AND target_id = :titleId
                  AND status    = 'failed'
                  AND last_error = 'no_match_in_filmography'
                """)
                .bind("titleId", titleId)
                .bind("now", now())
                .execute());
        return rows > 0;
    }

    /**
     * Optionally inserts an actress link for the title (additive — does not delete existing
     * links), then re-enqueues the failed fetch_title row at HIGH priority by deleting the
     * failed row and inserting a fresh pending one.
     *
     * @param titleId           the title to re-queue
     * @param actressIdOverride if non-null, a new actress link is added to title_actresses
     */
    public void clearNoMatchAndReQueue(long titleId, Long actressIdOverride) {
        jdbi.useTransaction(h -> {
            // Optionally add the actress link (additive — do not remove existing rows).
            if (actressIdOverride != null) {
                h.createUpdate("""
                        INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                        VALUES (:titleId, :actressId)
                        """)
                        .bind("titleId", titleId)
                        .bind("actressId", actressIdOverride)
                        .execute();
            }

            // Delete the failed no-match row.
            h.createUpdate("""
                    DELETE FROM javdb_enrichment_queue
                    WHERE job_type  = 'fetch_title'
                      AND target_id = :titleId
                      AND status    = 'failed'
                      AND last_error = 'no_match_in_filmography'
                    """)
                    .bind("titleId", titleId)
                    .execute();

            // Re-enqueue at HIGH priority.
            Long actressId = actressIdOverride;
            if (actressId == null) {
                // Fall back to the first linked actress (any) for the queue row.
                actressId = h.createQuery("""
                        SELECT actress_id FROM title_actresses
                        WHERE title_id = :titleId
                        LIMIT 1
                        """)
                        .bind("titleId", titleId)
                        .mapTo(Long.class)
                        .findOne()
                        .orElse(null);
            }
            final Long finalActressId = actressId;
            h.createUpdate("""
                    INSERT INTO javdb_enrichment_queue
                        (job_type, target_id, actress_id, source, priority, status,
                         attempts, next_attempt_at, created_at, updated_at, sort_order)
                    SELECT 'fetch_title', :titleId, :actressId, 'actress', 'HIGH', 'pending',
                           0, :now, :now, :now,
                           (SELECT COALESCE(MAX(sort_order), 0) + 1
                            FROM javdb_enrichment_queue
                            WHERE status IN ('pending', 'paused'))
                    WHERE NOT EXISTS (
                        SELECT 1 FROM javdb_enrichment_queue
                        WHERE job_type = 'fetch_title' AND target_id = :titleId
                          AND status IN ('pending', 'in_flight')
                    )
                    """)
                    .bind("titleId", titleId)
                    .bind("actressId", finalActressId)
                    .bind("now", now())
                    .execute();
        });
    }

    /**
     * Returns the folder path for a title's primary location, or empty if not known.
     */
    public Optional<FolderInfo> findFolderInfo(long titleId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT path, volume_id FROM title_locations
                WHERE title_id = :titleId
                ORDER BY id ASC
                LIMIT 1
                """)
                .bind("titleId", titleId)
                .map((rs, ctx) -> new FolderInfo(rs.getString("path"), rs.getString("volume_id")))
                .findOne());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * One row from {@link #listNoMatchRows()}: represents a single no-match queue entry,
     * potentially with a linked actress (or null for orphan titles).
     */
    public record NoMatchRow(
            long queueId,
            long titleId,
            String code,
            String baseCode,
            Long actressId,
            String actressStageName,
            String folderPath,
            String volumeId,
            int attempts,
            String updatedAt
    ) {}

    /**
     * One actress whose cached filmography contains a given product code.
     * Returned by {@link #findActressesByFilmographyCode(String)}.
     */
    public record FilmographyCandidate(
            long actressId,
            String stageName,
            String javdbSlug,
            String titleSlug,
            String fetchedAt,
            String lastReleaseDate  // nullable; used to detect stale caches
    ) {}

    /** Location info for the title's primary folder on disk. */
    public record FolderInfo(String path, String volumeId) {}

    // ── private ───────────────────────────────────────────────────────────────

    private String now() {
        return Instant.now().toString();
    }
}
