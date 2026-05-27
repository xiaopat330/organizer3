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
                           q.reason, q.resolver_source, q.created_at, q.detail,
                           q.ai_suggestion_slug, q.ai_suggestion_confidence,
                           q.ai_suggestion_reason, q.ai_suggestion_at, q.ai_auto_applied,
                           q.ai_auto_apply_attempts,
                           q.ai_phi4_slug, q.ai_gemma_slug
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
            return q.map((rs, ctx) -> mapOpenRow(rs)).list();
        });
    }

    private static OpenRow mapOpenRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OpenRow(
                rs.getLong("id"),
                rs.getLong("title_id"),
                rs.getString("title_code"),
                rs.getString("slug"),
                rs.getString("reason"),
                rs.getString("resolver_source"),
                rs.getString("created_at"),
                rs.getString("detail"),
                rs.getString("ai_suggestion_slug"),
                rs.getString("ai_suggestion_confidence"),
                rs.getString("ai_suggestion_reason"),
                rs.getString("ai_suggestion_at"),
                rs.getInt("ai_auto_applied") != 0,
                rs.getInt("ai_auto_apply_attempts"),
                rs.getString("ai_phi4_slug"),
                rs.getString("ai_gemma_slug")
        );
    }

    /**
     * Lists open {@code ambiguous} queue rows that have not yet had an AI suggestion attached
     * (i.e. {@code ai_suggestion_at IS NULL}), ordered by {@code created_at ASC} so the oldest
     * untried rows are handed to the sweeper first.
     *
     * <p>Used by the AI picker-assist sweeper (Phase 1) to find rows it should attempt to
     * suggest a slug for. Joins {@code titles} to include the product code (LEFT JOIN so
     * orphan rows whose title was deleted are still surfaced).
     *
     * @param limit maximum number of rows to return
     */
    public List<OpenRow> listOpenAwaitingAi(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT q.id, q.title_id, t.code AS title_code, q.slug,
                               q.reason, q.resolver_source, q.created_at, q.detail,
                               q.ai_suggestion_slug, q.ai_suggestion_confidence,
                               q.ai_suggestion_reason, q.ai_suggestion_at, q.ai_auto_applied,
                               q.ai_auto_apply_attempts,
                               q.ai_phi4_slug, q.ai_gemma_slug
                        FROM enrichment_review_queue q
                        LEFT JOIN titles t ON t.id = q.title_id
                        WHERE q.resolved_at IS NULL
                          AND q.reason = 'ambiguous'
                          AND q.ai_suggestion_at IS NULL
                        ORDER BY q.created_at ASC
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map((rs, ctx) -> mapOpenRow(rs))
                        .list());
    }

    /**
     * Lists open {@code ambiguous} queue rows that are eligible for AI auto-apply: the
     * ensemble agreed on a non-null slug, the row has not yet been auto-applied, and the
     * suggestion has aged at least {@code minAgeSeconds} seconds (a soak window so a human
     * has had a chance to intervene before the sweeper acts).
     *
     * <p>Ordered by {@code ai_suggestion_at ASC} (oldest first). LEFT JOIN on {@code titles}
     * so orphan rows are still surfaced. Uses {@code julianday()} on the ISO-8601 TEXT
     * {@code ai_suggestion_at} column to compute age in seconds.
     *
     * @param limit         maximum number of rows to return
     * @param minAgeSeconds minimum age (in seconds) of {@code ai_suggestion_at} for the row to be eligible
     */
    public List<OpenRow> listAutoApplyReady(int limit, int minAgeSeconds, int maxAttempts) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT q.id, q.title_id, t.code AS title_code, q.slug,
                               q.reason, q.resolver_source, q.created_at, q.detail,
                               q.ai_suggestion_slug, q.ai_suggestion_confidence,
                               q.ai_suggestion_reason, q.ai_suggestion_at, q.ai_auto_applied,
                               q.ai_auto_apply_attempts,
                               q.ai_phi4_slug, q.ai_gemma_slug
                        FROM enrichment_review_queue q
                        LEFT JOIN titles t ON t.id = q.title_id
                        WHERE q.resolved_at IS NULL
                          AND q.reason = 'ambiguous'
                          AND q.ai_suggestion_confidence = 'agreed'
                          AND q.ai_suggestion_slug IS NOT NULL
                          AND q.ai_auto_applied = 0
                          AND q.ai_suggestion_at IS NOT NULL
                          AND q.ai_auto_apply_attempts < :maxAttempts
                          AND (julianday('now') - julianday(q.ai_suggestion_at)) * 86400 >= :minAgeSeconds
                        ORDER BY q.ai_suggestion_at ASC
                        LIMIT :limit
                        """)
                        .bind("limit",         limit)
                        .bind("minAgeSeconds", minAgeSeconds)
                        .bind("maxAttempts",   maxAttempts)
                        .map((rs, ctx) -> mapOpenRow(rs))
                        .list());
    }

    /**
     * Increments the {@code ai_auto_apply_attempts} counter for a queue row. Called by
     * {@link com.organizer3.enrichment.ai.EnrichmentAutoApplier} on every auto-apply
     * failure so a persistently-failing slug eventually drops out of the sweeper's
     * auto-apply queue (see {@link #listAutoApplyReady(int, int, int)}).
     */
    public void incrementAutoApplyAttempts(long queueRowId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET ai_auto_apply_attempts = ai_auto_apply_attempts + 1
                        WHERE id = :id
                        """)
                        .bind("id", queueRowId)
                        .execute());
    }

    /**
     * Records an AI suggestion against an open queue row. Writes all four
     * {@code ai_suggestion_*} columns atomically. Passing {@code null} for {@code slug}
     * persists an explicit abstain (the row has been considered but no suggestion offered).
     *
     * <p><b>{@code confidence} column contents</b> (TEXT, no CHECK constraint): the sweeper
     * (Track G) writes the ensemble {@link com.organizer3.enrichment.ai.AssistResult#outcome()
     * outcome} string here — one of {@code agreed}, {@code agreed_with_override} (Phase 4
     * Track B; see {@link com.organizer3.enrichment.ai.PostProcessingRules}), {@code phi4_only},
     * {@code gemma_only}, {@code conflict}, {@code both_abstain}, or the sentinel {@code error}
     * when caller evaluation throws. (The plan doc described this column as model "confidence";
     * Phase 1 reuses it for the richer outcome label since the column is not constrained.)
     *
     * @param queueRowId the row id
     * @param slug       the suggested javdb slug, or null for an abstain
     * @param confidence the assist outcome label; see allowed values above
     * @param reason     short rationale string from the model (or the error message for {@code error})
     * @param at         when the suggestion was produced
     */
    public void setAiSuggestion(long queueRowId, String slug, String confidence, String reason, Instant at) {
        setAiSuggestion(queueRowId, slug, confidence, reason, at, null, null);
    }

    /**
     * Records an AI suggestion and the per-model slugs (V64+). {@code phi4Slug} and
     * {@code gemmaSlug} are the individual javdb slugs each model voted for, or {@code null}
     * when that model abstained. Passing both as {@code null} is fine for error sentinels.
     *
     * @param phi4Slug  slug phi4 voted for, or null if it abstained
     * @param gemmaSlug slug gemma3 voted for, or null if it abstained
     */
    public void setAiSuggestion(long queueRowId, String slug, String confidence, String reason,
                                Instant at, String phi4Slug, String gemmaSlug) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET ai_suggestion_slug       = :slug,
                            ai_suggestion_confidence = :confidence,
                            ai_suggestion_reason     = :reason,
                            ai_suggestion_at         = :at,
                            ai_phi4_slug             = :phi4Slug,
                            ai_gemma_slug            = :gemmaSlug
                        WHERE id = :id
                        """)
                        .bind("slug",       slug)
                        .bind("confidence", confidence)
                        .bind("reason",     reason)
                        .bind("at",         at != null ? at.toString() : null)
                        .bind("phi4Slug",   phi4Slug)
                        .bind("gemmaSlug",  gemmaSlug)
                        .bind("id",         queueRowId)
                        .execute());
    }

    /**
     * Lists already-resolved ambiguous queue rows for the AI-assist historical backfill task
     * (Phase 4 Track C). Joins {@code title_javdb_enrichment} to surface the ground-truth
     * slug that was ultimately written for the title — used to score the ensemble's
     * historical accuracy.
     *
     * <p>Predicate: {@code resolved_at IS NOT NULL AND reason='ambiguous' AND detail IS NOT NULL}
     * AND a {@code title_javdb_enrichment.javdb_slug} exists for the same {@code title_id}.
     * Ordered by {@code id ASC} for deterministic processing.
     *
     * @param limit maximum rows; pass {@link Integer#MAX_VALUE} for unlimited
     */
    public List<BackfillCandidate> listResolvedAmbiguousForBackfill(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT q.id              AS id,
                               q.title_id        AS title_id,
                               t.code            AS title_code,
                               q.detail          AS detail,
                               e.javdb_slug      AS ground_truth_slug
                        FROM enrichment_review_queue q
                        JOIN title_javdb_enrichment e ON e.title_id = q.title_id
                        LEFT JOIN titles t ON t.id = q.title_id
                        WHERE q.resolved_at IS NOT NULL
                          AND q.reason = 'ambiguous'
                          AND q.detail IS NOT NULL
                          AND e.javdb_slug IS NOT NULL
                        ORDER BY q.id ASC
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new BackfillCandidate(
                                rs.getLong("id"),
                                rs.getLong("title_id"),
                                rs.getString("title_code"),
                                rs.getString("detail"),
                                rs.getString("ground_truth_slug")))
                        .list());
    }

    /**
     * Resolves the prompt-context companions to {@link OpenRow} that aren't carried in the
     * row itself: the title's active (non-stale) folder path on disk and the canonical
     * (romaji) names of the linked, non-sentinel actresses.
     *
     * <p>Used by the AI picker-assist sweeper to give the ensemble caller richer hints than
     * what's snapshotted in {@code detail}, matching the POC's 87.5% agreement input.
     *
     * <p>Determinism:
     * <ul>
     *   <li>{@code folderPath} — a title can have multiple live locations; we pick the
     *       lexicographically first {@code (volume_id, path)} so repeated calls return the
     *       same value. {@code null} when the title has no live location (orphan).</li>
     *   <li>{@code actressNames} — empty list if the title has no linked non-sentinel
     *       actresses; ordered by canonical_name for stable prompts.</li>
     * </ul>
     *
     * <p>Returns {@code AssistContext(null, List.of())} when the title id doesn't exist.
     *
     * @param titleId the canonical title id (from {@link OpenRow#titleId()})
     */
    public AssistContext findContextForAssist(long titleId) {
        return jdbi.withHandle(h -> {
            String folderPath = h.createQuery("""
                    SELECT path FROM title_locations
                    WHERE title_id = :titleId
                      AND stale_since IS NULL
                    ORDER BY volume_id, path
                    LIMIT 1
                    """)
                    .bind("titleId", titleId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);

            List<String> actressNames = h.createQuery("""
                    SELECT a.canonical_name
                    FROM title_actresses ta
                    JOIN actresses a ON a.id = ta.actress_id
                    WHERE ta.title_id = :titleId
                      AND COALESCE(a.is_sentinel, 0) = 0
                      AND a.canonical_name IS NOT NULL
                    ORDER BY a.canonical_name
                    """)
                    .bind("titleId", titleId)
                    .mapTo(String.class)
                    .list();

            return new AssistContext(folderPath, actressNames);
        });
    }

    /** Marks the given queue row as auto-applied by the AI sweeper ({@code ai_auto_applied = 1}). */
    public void markAiAutoApplied(long queueRowId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET ai_auto_applied = 1
                        WHERE id = :id
                        """)
                        .bind("id", queueRowId)
                        .execute());
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
                               q.reason, q.resolver_source, q.created_at, q.detail,
                               q.ai_suggestion_slug, q.ai_suggestion_confidence,
                               q.ai_suggestion_reason, q.ai_suggestion_at, q.ai_auto_applied,
                               q.ai_auto_apply_attempts,
                               q.ai_phi4_slug, q.ai_gemma_slug
                        FROM enrichment_review_queue q
                        LEFT JOIN titles t ON t.id = q.title_id
                        WHERE q.id = :id AND q.resolved_at IS NULL
                        """)
                        .bind("id", queueRowId)
                        .map((rs, ctx) -> mapOpenRow(rs))
                        .findOne());
    }

    /**
     * Returns a single queue row by id regardless of resolved state. Used by lightweight
     * read-only endpoints (e.g. the AI suggestion refresh poll) that need to surface the
     * latest {@code ai_suggestion_*} columns even after a row has been resolved.
     *
     * <p>Like {@link #findOpenById}, uses LEFT JOIN on titles so orphan rows still come back.
     */
    public java.util.Optional<OpenRow> findById(long queueRowId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT q.id, q.title_id, t.code AS title_code, q.slug,
                               q.reason, q.resolver_source, q.created_at, q.detail,
                               q.ai_suggestion_slug, q.ai_suggestion_confidence,
                               q.ai_suggestion_reason, q.ai_suggestion_at, q.ai_auto_applied,
                               q.ai_auto_apply_attempts,
                               q.ai_phi4_slug, q.ai_gemma_slug
                        FROM enrichment_review_queue q
                        LEFT JOIN titles t ON t.id = q.title_id
                        WHERE q.id = :id
                        """)
                        .bind("id", queueRowId)
                        .map((rs, ctx) -> mapOpenRow(rs))
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

    /**
     * For a {@code slug_conflict} open row, looks up canonical name, stage_name, and tier for
     * both the claimant and incumbent actress IDs recorded in the detail JSON.
     *
     * <p>Used by the review-queue list serializer to embed triage context without adding
     * extra repositories to the caller.
     *
     * @param claimantActressId  the actress who was being backfilled (the new claimant)
     * @param incumbentActressId the actress who already owns the slug
     * @return enrichment context, or empty if either actress cannot be found
     */
    public java.util.Optional<SlugConflictContext> findSlugConflictContext(
            long claimantActressId, long incumbentActressId) {
        return jdbi.withHandle(h -> {
            var claimant = h.createQuery("""
                    SELECT id, canonical_name, stage_name, tier
                    FROM actresses WHERE id = :id
                    """)
                    .bind("id", claimantActressId)
                    .map((rs, ctx) -> new ConflictActress(
                            rs.getLong("id"),
                            rs.getString("canonical_name"),
                            rs.getString("stage_name"),
                            rs.getString("tier")))
                    .findOne()
                    .orElse(null);

            var incumbent = h.createQuery("""
                    SELECT id, canonical_name, stage_name, tier
                    FROM actresses WHERE id = :id
                    """)
                    .bind("id", incumbentActressId)
                    .map((rs, ctx) -> new ConflictActress(
                            rs.getLong("id"),
                            rs.getString("canonical_name"),
                            rs.getString("stage_name"),
                            rs.getString("tier")))
                    .findOne()
                    .orElse(null);

            if (claimant == null || incumbent == null) return java.util.Optional.empty();
            return java.util.Optional.of(new SlugConflictContext(claimant, incumbent));
        });
    }

    /**
     * Prompt-context companion to {@link OpenRow}: the title's live folder path and the
     * canonical (romaji) names of its linked non-sentinel actresses. Returned by
     * {@link #findContextForAssist(long)}. Either field may be empty for orphan / unjoined
     * titles; both are passed through to {@link com.organizer3.enrichment.ai.AssistPromptBuilder}
     * which handles missing hints gracefully.
     */
    public record AssistContext(String folderPath, List<String> actressNames) {
        public AssistContext {
            actressNames = actressNames == null ? List.of() : List.copyOf(actressNames);
        }
    }

    /** Enrichment context for a cast_anomaly row's title: raw cast JSON + linked actresses. */
    public record CastAnomalyContext(String castJson, List<LinkedActress> linkedActresses) {}

    /** An actress linked to a title, for use in the inline triage panel. */
    public record LinkedActress(long id, String canonicalName) {}

    /** Context for a slug_conflict row: the two actresses involved in the conflict. */
    public record SlugConflictContext(ConflictActress claimant, ConflictActress incumbent) {}

    /** One side of a slug conflict — the claimant or incumbent actress. */
    public record ConflictActress(long id, String canonicalName, String stageName, String tier) {}

    // ── AI-assist dashboard aggregates ───────────────────────────────────────

    /**
     * Count of open (unresolved) ambiguous rows that have not yet received an AI suggestion.
     * Mirrors the WHERE clause of {@link #listOpenAwaitingAi}: {@code resolved_at IS NULL
     * AND reason='ambiguous' AND ai_suggestion_at IS NULL}.
     */
    public int countAwaitingAi() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM enrichment_review_queue
                        WHERE resolved_at IS NULL
                          AND reason = 'ambiguous'
                          AND ai_suggestion_at IS NULL
                        """)
                        .mapTo(Integer.class).one());
    }

    /**
     * Total count of rows that have had an AI suggestion recorded ({@code ai_suggestion_at IS NOT NULL}).
     * Includes both open and resolved rows.
     */
    public int countProcessed() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_review_queue WHERE ai_suggestion_at IS NOT NULL")
                        .mapTo(Integer.class).one());
    }

    /**
     * Total count of rows where {@code ai_auto_applied = 1}.
     */
    public int countAutoApplied() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_review_queue WHERE ai_auto_applied = 1")
                        .mapTo(Integer.class).one());
    }

    /**
     * Returns a map of {@code ai_suggestion_confidence} → count for all rows that have been
     * processed ({@code ai_suggestion_at IS NOT NULL}). A null confidence value is mapped to
     * the key {@code "unknown"}. Results are returned in a {@link LinkedHashMap} in insertion
     * order (driven by GROUP BY on SQLite, which is stable for practical purposes).
     */
    public Map<String, Integer> outcomeCounts() {
        return jdbi.withHandle(h -> {
            var rows = h.createQuery("""
                    SELECT COALESCE(ai_suggestion_confidence, 'unknown') AS outcome,
                           COUNT(*) AS cnt
                    FROM enrichment_review_queue
                    WHERE ai_suggestion_at IS NOT NULL
                    GROUP BY ai_suggestion_confidence
                    """)
                    .map((rs, ctx) -> Map.entry(rs.getString("outcome"), rs.getInt("cnt")))
                    .list();
            Map<String, Integer> out = new LinkedHashMap<>();
            rows.forEach(e -> out.put(e.getKey(), e.getValue()));
            return out;
        });
    }

    /**
     * Lists recently processed rows ({@code ai_suggestion_at IS NOT NULL}), optionally
     * filtered to rows processed after {@code sinceIso} (exclusive). Ordered by
     * {@code ai_suggestion_at DESC}. Joins {@code titles} for the product code.
     *
     * @param limit     maximum number of rows
     * @param sinceIso  ISO-8601 string lower bound (exclusive); pass {@code null} or blank to disable
     */
    public List<RecentProcessedRow> listRecentlyProcessed(int limit, String sinceIso) {
        return jdbi.withHandle(h -> {
            boolean hasSince = sinceIso != null && !sinceIso.isBlank();
            String sql = """
                    SELECT q.id AS review_queue_id,
                           t.code AS title_code,
                           q.ai_suggestion_confidence AS outcome,
                           q.ai_suggestion_slug AS slug,
                           q.ai_suggestion_reason AS ai_reason,
                           q.ai_auto_applied AS auto_applied,
                           q.ai_suggestion_at AS at
                    FROM enrichment_review_queue q
                    LEFT JOIN titles t ON t.id = q.title_id
                    WHERE q.ai_suggestion_at IS NOT NULL
                    """ + (hasSince ? "AND q.ai_suggestion_at > :since\n" : "")
                    + "ORDER BY q.ai_suggestion_at DESC\n"
                    + "LIMIT :limit";
            var q = h.createQuery(sql).bind("limit", limit);
            if (hasSince) q = q.bind("since", sinceIso);
            return q.map((rs, ctx) -> new RecentProcessedRow(
                    rs.getLong("review_queue_id"),
                    rs.getString("title_code"),
                    rs.getString("outcome"),
                    rs.getString("slug"),
                    rs.getString("ai_reason"),
                    rs.getInt("auto_applied") != 0,
                    rs.getString("at")))
                    .list();
        });
    }

    /**
     * Row returned by {@link #listRecentlyProcessed}: a processed queue row with AI-suggestion
     * summary fields. {@code outcome} is {@code ai_suggestion_confidence}; {@code at} is
     * {@code ai_suggestion_at}.
     */
    public record RecentProcessedRow(long reviewQueueId, String code, String outcome,
                                     String slug, String reason, boolean autoApplied, String at) {}

    /**
     * Row returned by {@link #listResolvedAmbiguousForBackfill(int)} — an already-resolved
     * ambiguous queue row paired with the slug eventually written to
     * {@code title_javdb_enrichment} (the human-picked ground truth).
     */
    public record BackfillCandidate(long id, long titleId, String titleCode,
                                    String detail, String groundTruthSlug) {}

    /**
     * A single open queue row returned by {@link #listOpen}, {@link #findOpenById},
     * and {@link #listOpenAwaitingAi}. The {@code aiSuggestion*} fields are null when no
     * AI suggestion has been attached yet. {@code aiPhi4Slug} / {@code aiGemmaSlug} are
     * populated when the ensemble caller records per-model picks (V64+); null means the
     * model abstained or AI has not run yet.
     */
    public record OpenRow(long id, long titleId, String titleCode, String slug,
                          String reason, String resolverSource, String createdAt,
                          String detail,
                          String aiSuggestionSlug, String aiSuggestionConfidence,
                          String aiSuggestionReason, String aiSuggestionAt,
                          boolean aiAutoApplied,
                          int aiAutoApplyAttempts,
                          String aiPhi4Slug,
                          String aiGemmaSlug) {

        /** Convenience overload preserving the pre-AI-picker call signature for existing tests/callers. */
        public OpenRow(long id, long titleId, String titleCode, String slug,
                       String reason, String resolverSource, String createdAt, String detail) {
            this(id, titleId, titleCode, slug, reason, resolverSource, createdAt, detail,
                    null, null, null, null, false, 0, null, null);
        }

        /** Convenience overload preserving the pre-Phase-4 (Track D) call signature. */
        public OpenRow(long id, long titleId, String titleCode, String slug,
                       String reason, String resolverSource, String createdAt, String detail,
                       String aiSuggestionSlug, String aiSuggestionConfidence,
                       String aiSuggestionReason, String aiSuggestionAt,
                       boolean aiAutoApplied) {
            this(id, titleId, titleCode, slug, reason, resolverSource, createdAt, detail,
                    aiSuggestionSlug, aiSuggestionConfidence, aiSuggestionReason, aiSuggestionAt,
                    aiAutoApplied, 0, null, null);
        }

        /** Convenience overload preserving the pre-V64 (ai_auto_apply_attempts) call signature. */
        public OpenRow(long id, long titleId, String titleCode, String slug,
                       String reason, String resolverSource, String createdAt, String detail,
                       String aiSuggestionSlug, String aiSuggestionConfidence,
                       String aiSuggestionReason, String aiSuggestionAt,
                       boolean aiAutoApplied, int aiAutoApplyAttempts) {
            this(id, titleId, titleCode, slug, reason, resolverSource, createdAt, detail,
                    aiSuggestionSlug, aiSuggestionConfidence, aiSuggestionReason, aiSuggestionAt,
                    aiAutoApplied, aiAutoApplyAttempts, null, null);
        }
    }
}
