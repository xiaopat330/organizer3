package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes rows to {@code enrichment_review_queue} when the write-time gate cannot
 * (or should not) proceed with a normal enrichment INSERT.
 *
 * <p>Enqueue is idempotent: if an open row (resolved_at IS NULL) already exists
 * for the same (title_id, reason), the INSERT is silently ignored via the partial
 * unique index on those columns.
 */
@RequiredArgsConstructor
public class EnrichmentReviewQueueRepository {

    private final Jdbi jdbi;

    /**
     * Inserts an open review-queue entry for the given title, or no-ops if an
     * equivalent open entry already exists.
     *
     * @param titleId        the title that triggered the gate decision
     * @param slug           the javdb slug resolved for this title (may be null for sentinel short-circuits)
     * @param reason         one of {@code cast_anomaly}, {@code ambiguous}, {@code fetch_failed}
     * @param resolverSource the resolver source that produced this outcome (e.g. {@code actress_filmography},
     *                       {@code code_search_fallback}, {@code unknown})
     */
    public void enqueue(long titleId, String slug, String reason, String resolverSource) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT OR IGNORE INTO enrichment_review_queue
                            (title_id, slug, reason, resolver_source)
                        VALUES (:titleId, :slug, :reason, :resolverSource)
                        """)
                        .bind("titleId",        titleId)
                        .bind("slug",           slug)
                        .bind("reason",         reason)
                        .bind("resolverSource", resolverSource)
                        .execute());
    }

    /** Count of open (unresolved) queue entries with the given reason. */
    public int countOpen(String reason) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_review_queue WHERE reason = :reason AND resolved_at IS NULL")
                        .bind("reason", reason)
                        .mapTo(Integer.class).one());
    }

    /** Total count of queue entries (open + resolved) with the given reason. */
    public int countTotal(String reason) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_review_queue WHERE reason = :reason")
                        .bind("reason", reason)
                        .mapTo(Integer.class).one());
    }

    /** Returns {@code true} if there is an open entry for the given (title_id, reason) pair. */
    public boolean hasOpen(long titleId, String reason) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM enrichment_review_queue
                        WHERE title_id = :titleId AND reason = :reason AND resolved_at IS NULL
                        """)
                        .bind("titleId", titleId)
                        .bind("reason",  reason)
                        .mapTo(Integer.class).one()) > 0;
    }

    /**
     * Lists open (unresolved) queue rows, optionally filtered by reason, ordered by
     * {@code created_at DESC}. Joins {@code titles} to include the product code.
     *
     * @param reason filter to a specific reason; pass {@code null} to return all reasons
     * @param limit  maximum number of rows
     * @param offset zero-based row offset for pagination
     */
    public List<OpenRow> listOpen(String reason, int limit, int offset) {
        return jdbi.withHandle(h -> {
            String sql = """
                    SELECT q.id, q.title_id, t.code AS title_code, q.slug,
                           q.reason, q.resolver_source, q.created_at
                    FROM enrichment_review_queue q
                    JOIN titles t ON t.id = q.title_id
                    WHERE q.resolved_at IS NULL
                    """ + (reason != null ? "AND q.reason = :reason\n" : "")
                    + "ORDER BY q.created_at DESC\n"
                    + "LIMIT :limit OFFSET :offset";
            var q = h.createQuery(sql)
                    .bind("limit",  limit)
                    .bind("offset", offset);
            if (reason != null) q = q.bind("reason", reason);
            return q.map((rs, ctx) -> new OpenRow(
                    rs.getLong("id"),
                    rs.getLong("title_id"),
                    rs.getString("title_code"),
                    rs.getString("slug"),
                    rs.getString("reason"),
                    rs.getString("resolver_source"),
                    rs.getString("created_at")
            )).list();
        });
    }

    /**
     * Returns a map of reason → open count for every reason that has at least one open row.
     */
    public Map<String, Integer> countOpenByReason() {
        return jdbi.withHandle(h -> {
            var rows = h.createQuery("""
                    SELECT reason, COUNT(*) AS cnt
                    FROM enrichment_review_queue
                    WHERE resolved_at IS NULL
                    GROUP BY reason
                    """)
                    .map((rs, ctx) -> Map.entry(rs.getString("reason"), rs.getInt("cnt")))
                    .list();
            Map<String, Integer> out = new LinkedHashMap<>();
            rows.forEach(e -> out.put(e.getKey(), e.getValue()));
            return out;
        });
    }

    /**
     * Resolves one open queue row by id.
     *
     * @param id         the row id
     * @param resolution the resolution value (e.g. {@code accepted_gap}, {@code marked_resolved})
     * @return {@code true} if the row was found and was open (and is now resolved);
     *         {@code false} if the row does not exist or was already resolved
     */
    public boolean resolveOne(long id, String resolution) {
        int updated = jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET resolved_at = :now, resolution = :resolution
                        WHERE id = :id AND resolved_at IS NULL
                        """)
                        .bind("now",        Instant.now().toString())
                        .bind("resolution", resolution)
                        .bind("id",         id)
                        .execute());
        return updated > 0;
    }

    /** A single open queue row returned by {@link #listOpen}. */
    public record OpenRow(long id, long titleId, String titleCode, String slug,
                          String reason, String resolverSource, String createdAt) {}
}
