package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Reasons that cannot be resolved by any automatic or user action once they appear —
     * aged out after 72 hours.
     */
    static final Set<String> UNRECOVERABLE_REASONS = Set.of("no_match", "orphan_enriched");

    /** Recoverable reasons are aged out after 7 days. */
    static final long UNRECOVERABLE_TTL_HOURS = 72;
    static final long RECOVERABLE_TTL_DAYS    = 7;

    private final Jdbi jdbi;

    /**
     * Auto-resolves stale open rows with {@code resolution = 'aged_out'}:
     * <ul>
     *   <li>Unrecoverable reasons ({@code no_match}, {@code orphan_enriched}): after 72 hours</li>
     *   <li>All other (recoverable) reasons: after 7 days</li>
     * </ul>
     * Called before every list/count operation so the UI never shows expired items.
     *
     * @return number of rows auto-resolved
     */
    public int purgeStale() {
        Instant now       = Instant.now();
        String cutoff72h  = now.minus(UNRECOVERABLE_TTL_HOURS, ChronoUnit.HOURS).toString();
        String cutoff7d   = now.minus(RECOVERABLE_TTL_DAYS,    ChronoUnit.DAYS).toString();
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET resolved_at = :now, resolution = 'aged_out'
                        WHERE resolved_at IS NULL
                          AND (
                            (reason IN ('no_match', 'orphan_enriched') AND created_at < :cutoff72h)
                            OR (reason NOT IN ('no_match', 'orphan_enriched') AND created_at < :cutoff7d)
                          )
                        """)
                        .bind("now",       now.toString())
                        .bind("cutoff72h", cutoff72h)
                        .bind("cutoff7d",  cutoff7d)
                        .execute());
    }

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
                           q.reason, q.resolver_source, q.created_at, q.detail
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
                    rs.getString("created_at"),
                    rs.getString("detail")
            )).list();
        });
    }

    /**
     * Returns a single open (unresolved) queue row by id, including its {@code detail} JSON.
     * Returns empty if the row does not exist or is already resolved.
     *
     * <p>Uses LEFT JOIN on titles so that rows whose title was deleted (orphan rows) are still
     * returned — the {@code titleCode} field will be null in that case.
     */
    public java.util.Optional<OpenRow> findOpenById(long queueRowId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT q.id, q.title_id, t.code AS title_code, q.slug,
                               q.reason, q.resolver_source, q.created_at, q.detail
                        FROM enrichment_review_queue q
                        LEFT JOIN titles t ON t.id = q.title_id
                        WHERE q.id = :id AND q.resolved_at IS NULL
                        """)
                        .bind("id", queueRowId)
                        .map((rs, ctx) -> new OpenRow(
                                rs.getLong("id"),
                                rs.getLong("title_id"),
                                rs.getString("title_code"),
                                rs.getString("slug"),
                                rs.getString("reason"),
                                rs.getString("resolver_source"),
                                rs.getString("created_at"),
                                rs.getString("detail")
                        ))
                        .findOne());
    }

    /**
     * Inserts an open review-queue entry with a candidate snapshot in {@code detail}.
     * If an equivalent open entry already exists (same title_id + reason), the INSERT is
     * a no-op but the detail is updated if it was previously NULL.
     */
    public void enqueueWithDetail(long titleId, String slug, String reason,
                                  String resolverSource, String detailJson) {
        jdbi.useHandle(h -> enqueueWithDetail(titleId, slug, reason, resolverSource, detailJson, h));
    }

    /**
     * Handle-scoped variant of {@link #enqueueWithDetail} for use inside transactions.
     */
    public void enqueueWithDetail(long titleId, String slug, String reason,
                                  String resolverSource, String detailJson, Handle h) {
        h.createUpdate("""
                        INSERT OR IGNORE INTO enrichment_review_queue
                            (title_id, slug, reason, resolver_source)
                        VALUES (:titleId, :slug, :reason, :resolverSource)
                        """)
                .bind("titleId",        titleId)
                .bind("slug",           slug)
                .bind("reason",         reason)
                .bind("resolverSource", resolverSource)
                .execute();
        if (detailJson != null) {
            h.createUpdate("""
                            UPDATE enrichment_review_queue
                            SET detail = :detail, last_seen_at = :now
                            WHERE title_id = :titleId AND reason = :reason
                              AND resolved_at IS NULL AND detail IS NULL
                            """)
                    .bind("detail",  detailJson)
                    .bind("now",     Instant.now().toString())
                    .bind("titleId", titleId)
                    .bind("reason",  reason)
                    .execute();
        }
    }

    /**
     * Updates the {@code detail} and {@code last_seen_at} of an existing open queue row.
     * Used by {@code refresh_review_candidates} to populate or refresh the candidate snapshot.
     *
     * @param queueRowId the row id
     * @param detailJson the fresh snapshot JSON
     */
    public void updateDetail(long queueRowId, String detailJson) {
        jdbi.useHandle(h -> updateDetail(queueRowId, detailJson, h));
    }

    /**
     * Handle-scoped variant of {@link #updateDetail} for use inside transactions.
     */
    public void updateDetail(long queueRowId, String detailJson, Handle h) {
        h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET detail = :detail, last_seen_at = :now
                        WHERE id = :id AND resolved_at IS NULL
                        """)
                .bind("detail", detailJson)
                .bind("now",    Instant.now().toString())
                .bind("id",     queueRowId)
                .execute();
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

    /** Handle-scoped variant of {@link #resolveOne} for use inside an existing transaction. */
    public boolean resolveOne(long id, String resolution, org.jdbi.v3.core.Handle h) {
        int updated = h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET resolved_at = :now, resolution = :resolution
                        WHERE id = :id AND resolved_at IS NULL
                        """)
                .bind("now",        Instant.now().toString())
                .bind("resolution", resolution)
                .bind("id",         id)
                .execute();
        return updated > 0;
    }

    /**
     * Resolves all open queue rows for a title in the given handle (for use inside a transaction).
     *
     * @param titleId    the title whose open rows should be resolved
     * @param resolution the resolution value (e.g. {@code manual_override})
     * @param h          an open JDBI Handle
     * @return count of rows resolved
     */
    public int resolveAllOpenForTitle(long titleId, String resolution, org.jdbi.v3.core.Handle h) {
        return h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET resolved_at = :now, resolution = :resolution
                        WHERE title_id = :titleId AND resolved_at IS NULL
                        """)
                .bind("now",        Instant.now().toString())
                .bind("resolution", resolution)
                .bind("titleId",    titleId)
                .execute();
    }

    /**
     * Inserts an open {@code orphan_enriched} review-queue entry inside an existing transaction.
     * No-ops if an equivalent open entry already exists (partial unique index on title_id + reason
     * where resolved_at IS NULL). Does not set detail if {@code detailJson} is null.
     *
     * @param titleId    the orphaned title
     * @param slug       the javdb slug from title_javdb_enrichment (may be null)
     * @param detailJson snapshot JSON; pass null to omit
     * @param h          open JDBI handle (caller owns the transaction)
     */
    public void enqueueOrphanFlag(long titleId, String slug, String detailJson, Handle h) {
        h.createUpdate("""
                        INSERT OR IGNORE INTO enrichment_review_queue
                            (title_id, slug, reason, resolver_source)
                        VALUES (:titleId, :slug, 'orphan_enriched', 'sync_orphan')
                        """)
                .bind("titleId", titleId)
                .bind("slug",    slug)
                .execute();
        if (detailJson != null) {
            h.createUpdate("""
                            UPDATE enrichment_review_queue
                            SET detail = :detail, last_seen_at = :now
                            WHERE title_id = :titleId AND reason = 'orphan_enriched'
                              AND resolved_at IS NULL AND detail IS NULL
                            """)
                    .bind("detail",  detailJson)
                    .bind("now",     Instant.now().toString())
                    .bind("titleId", titleId)
                    .execute();
        }
    }

    /**
     * Looks up the title_id for a queue row (open or resolved) by its row id.
     * Returns empty if the row does not exist.
     */
    public java.util.Optional<Long> findTitleIdByQueueRowId(long queueRowId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT title_id FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", queueRowId)
                        .mapTo(Long.class)
                        .findOne());
    }

    /**
     * For a title linked to a {@code cast_anomaly} open row, returns the {@code cast_json}
     * from {@code title_javdb_enrichment} and the list of linked actress IDs + canonical names.
     *
     * <p>Used by the review-queue list serializer to embed inline triage context for the
     * "Add as alias" action without adding extra repositories to the caller.
     *
     * @param titleId the title to look up
     * @return enrichment context, or empty if no enrichment row exists for this title
     */
    public java.util.Optional<CastAnomalyContext> findCastAnomalyContext(long titleId) {
        return jdbi.withHandle(h -> {
            String castJson = h.createQuery("""
                    SELECT cast_json FROM title_javdb_enrichment
                    WHERE title_id = :titleId
                    """)
                    .bind("titleId", titleId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);

            List<LinkedActress> actresses = h.createQuery("""
                    SELECT a.id, a.canonical_name
                    FROM title_actresses ta
                    JOIN actresses a ON a.id = ta.actress_id
                    WHERE ta.title_id = :titleId
                    """)
                    .bind("titleId", titleId)
                    .map((rs, ctx) -> new LinkedActress(
                            rs.getLong("id"),
                            rs.getString("canonical_name")))
                    .list();

            if (castJson == null && actresses.isEmpty()) return java.util.Optional.empty();
            return java.util.Optional.of(new CastAnomalyContext(castJson, actresses));
        });
    }

    /** Enrichment context for a cast_anomaly row's title: raw cast JSON + linked actresses. */
    public record CastAnomalyContext(String castJson, List<LinkedActress> linkedActresses) {}

    /** An actress linked to a title, for use in the inline triage panel. */
    public record LinkedActress(long id, String canonicalName) {}

    /** A single open queue row returned by {@link #listOpen} and {@link #findOpenById}. */
    public record OpenRow(long id, long titleId, String titleCode, String slug,
                          String reason, String resolverSource, String createdAt,
                          String detail) {}
}
