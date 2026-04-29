package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

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
}
