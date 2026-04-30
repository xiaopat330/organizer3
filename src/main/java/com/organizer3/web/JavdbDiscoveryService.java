package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import com.organizer3.model.Title;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Read-only queries for the javdb Discovery screen.
 *
 * Pulls from actresses, title_actresses, title_javdb_enrichment, javdb_actress_staging,
 * and javdb_enrichment_queue — no mutations.
 */
public class JavdbDiscoveryService {

    private final Jdbi jdbi;
    private final EnrichmentRunner runner;
    private final CoverPath coverPath;

    public JavdbDiscoveryService(Jdbi jdbi, EnrichmentRunner runner, CoverPath coverPath) {
        this.jdbi      = jdbi;
        this.runner    = runner;
        this.coverPath = coverPath;
    }

    // ── Response records ───────────────────────────────────────────────────

    public record ActressRow(
            long id,
            String canonicalName,
            String stageName,
            int totalTitles,
            int enrichedTitles,
            String actressStatus,  // null | 'slug_only' | 'fetched'
            boolean favorite,
            boolean bookmark,
            int activeJobs         // count of pending + in_flight fetch_title jobs for this actress
    ) {}

    public record TitleRow(
            long titleId,
            String code,
            String status,         // null when no enrichment row
            String javdbSlug,
            String titleOriginal,
            String releaseDate,
            String maker,
            String publisher,
            Double ratingAvg,
            Integer ratingCount,
            String queueStatus     // null | 'pending' | 'in_flight' | 'failed' | 'done'
    ) {}

    /**
     * Filter parameters for the per-actress surfacing query.
     * All fields are optional; null/empty means "no filter on this axis".
     * When ANY filter is non-null, un-enriched titles are excluded automatically
     * (they have no row in title_javdb_enrichment to match against).
     */
    public record TitleFilter(
            List<String> requireTags,   // tags AND (conjunction)
            Double minRatingAvg,
            Integer minRatingCount
    ) {
        public boolean isEmpty() {
            return (requireTags == null || requireTags.isEmpty())
                && minRatingAvg == null && minRatingCount == null;
        }
        public static TitleFilter none() { return new TitleFilter(List.of(), null, null); }
    }

    /** A single facet entry returned alongside filtered title results. */
    public record TagFacet(String name, int count) {}

    public record ProfileRow(
            String javdbSlug,
            String status,
            String rawFetchedAt,
            String nameVariantsJson,
            String avatarUrl,
            String localAvatarUrl,
            String twitterHandle,
            String instagramHandle,
            Integer titleCount
    ) {}

    public record QueueItem(
            long id, String jobType, String status, int attempts,
            long actressId, String actressName,
            Long titleId, String titleCode,
            String updatedAt,
            Integer queuePosition   // 1-based position among pending items; null for in_flight/failed
    ) {}

    public record QueueStatus(int pending, int inFlight, int failed, int pausedItems, boolean paused,
                              String rateLimitPausedUntil, String rateLimitPauseReason,
                              int consecutiveRateLimitHits, String pauseType) {}

    public record ConflictRow(
            long titleId,
            String code,
            String ourActressName,
            String ourJavdbSlug,   // null if no staging profile yet
            String castJson,
            String coverUrl        // null if no cover found locally
    ) {}

    public record TitleEnrichmentDetail(
            long titleId,
            String code,
            String javdbSlug,
            String titleOriginal,
            String releaseDate,
            Integer durationMinutes,
            String maker,
            String publisher,
            String series,
            Double ratingAvg,
            Integer ratingCount,
            String castJson,
            List<String> tags,
            String fetchedAt
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
                  a.favorite,
                  a.bookmark,
                  COUNT(DISTINCT ta.title_id)                                                       AS total_titles,
                  COUNT(DISTINCT CASE WHEN tje.title_id IS NOT NULL THEN ta.title_id END)           AS enriched_titles,
                  jas.status                                                                         AS actress_status,
                  COALESCE(MAX(jeq.active_jobs), 0)                                                 AS active_jobs
                FROM actresses a
                JOIN title_actresses ta ON ta.actress_id = a.id
                LEFT JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id
                LEFT JOIN javdb_actress_staging jas ON jas.actress_id = a.id
                LEFT JOIN (
                    SELECT actress_id, COUNT(*) AS active_jobs
                    FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_title'
                      AND status IN ('pending', 'in_flight')
                    GROUP BY actress_id
                ) jeq ON jeq.actress_id = a.id
                WHERE a.is_sentinel = 0
                GROUP BY a.id, a.canonical_name, a.stage_name, a.favorite, a.bookmark, jas.status
                ORDER BY a.canonical_name
                """)
                .map((rs, ctx) -> new ActressRow(
                        rs.getLong("id"),
                        rs.getString("canonical_name"),
                        rs.getString("stage_name"),
                        rs.getInt("total_titles"),
                        rs.getInt("enriched_titles"),
                        rs.getString("actress_status"),
                        rs.getInt("favorite") != 0,
                        rs.getInt("bookmark") != 0,
                        rs.getInt("active_jobs")
                ))
                .list());
    }

    /**
     * Returns title rows for the actress's titles. When {@code filter} is non-empty,
     * un-enriched titles are excluded automatically (they cannot match any enrichment
     * predicate). Tag conjunction uses GROUP BY ... HAVING COUNT to require all listed tags.
     */
    public List<TitleRow> getActressTitles(long actressId, TitleFilter filter) {
        boolean filtering = filter != null && !filter.isEmpty();
        boolean tagFiltering = filtering && filter.requireTags() != null && !filter.requireTags().isEmpty();

        StringBuilder sql = new StringBuilder("""
                SELECT
                  t.id   AS title_id,
                  t.code,
                  CASE WHEN tje.title_id IS NOT NULL THEN 'fetched' ELSE NULL END AS status,
                  tje.javdb_slug,
                  tje.title_original,
                  tje.release_date,
                  tje.maker,
                  tje.publisher,
                  tje.rating_avg,
                  tje.rating_count,
                  jeq.effective_queue_status AS queue_status
                FROM title_actresses ta
                JOIN titles t ON t.id = ta.title_id
                """);
        // When filtering on enrichment predicates, only enriched titles can possibly match,
        // so an INNER JOIN is more efficient than LEFT + WHERE. Otherwise LEFT to preserve
        // the un-enriched rows in the listing.
        sql.append(filtering
                ? "JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id\n"
                : "LEFT JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id\n");
        sql.append("""
                LEFT JOIN (
                    SELECT target_id,
                           CASE
                             WHEN SUM(CASE WHEN status = 'in_flight' THEN 1 ELSE 0 END) > 0 THEN 'in_flight'
                             WHEN SUM(CASE WHEN status = 'pending'   THEN 1 ELSE 0 END) > 0 THEN 'pending'
                             WHEN SUM(CASE WHEN status = 'failed'    THEN 1 ELSE 0 END) > 0 THEN 'failed'
                             WHEN SUM(CASE WHEN status = 'done'      THEN 1 ELSE 0 END) > 0 THEN 'done'
                             ELSE NULL
                           END AS effective_queue_status
                    FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_title'
                    GROUP BY target_id
                ) jeq ON jeq.target_id = t.id
                WHERE ta.actress_id = :actressId
                """);
        if (filtering && filter.minRatingAvg() != null)   sql.append("  AND tje.rating_avg   >= :minRatingAvg\n");
        if (filtering && filter.minRatingCount() != null) sql.append("  AND tje.rating_count >= :minRatingCount\n");
        if (tagFiltering) {
            sql.append("""
                      AND t.id IN (
                        SELECT tet.title_id
                        FROM title_enrichment_tags tet
                        JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                        WHERE etd.name IN (<tags>)
                        GROUP BY tet.title_id
                        HAVING COUNT(DISTINCT etd.id) = :tagCount
                      )
                    """);
        }
        sql.append("ORDER BY t.code");

        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString())
                    .bind("actressId", actressId);
            if (filtering && filter.minRatingAvg() != null)   query.bind("minRatingAvg",   filter.minRatingAvg());
            if (filtering && filter.minRatingCount() != null) query.bind("minRatingCount", filter.minRatingCount());
            if (tagFiltering) {
                query.bindList("tags", filter.requireTags())
                     .bind("tagCount", filter.requireTags().size());
            }
            return query.map((rs, ctx) -> new TitleRow(
                    rs.getLong("title_id"),
                    rs.getString("code"),
                    rs.getString("status"),
                    rs.getString("javdb_slug"),
                    rs.getString("title_original"),
                    rs.getString("release_date"),
                    rs.getString("maker"),
                    rs.getString("publisher"),
                    rs.getObject("rating_avg")   != null ? rs.getDouble("rating_avg")   : null,
                    rs.getObject("rating_count") != null ? rs.getInt("rating_count")    : null,
                    rs.getString("queue_status")
            )).list();
        });
    }

    /** Backwards-compatible no-filter overload. */
    public List<TitleRow> getActressTitles(long actressId) {
        return getActressTitles(actressId, TitleFilter.none());
    }

    /**
     * Returns enrichment-tag facets for the actress: for each tag that appears on at least
     * one of her enriched titles after applying {@code filter}, the count of currently-matching
     * titles that carry that tag. Drives the faceted picker.
     *
     * <p>The tag the user has already selected is included in the result with its current count,
     * so the UI can show "(N)" next to selected chips.
     */
    public List<TagFacet> getActressTagFacets(long actressId, TitleFilter filter) {
        // Reuse getActressTitles to determine the matching set, then count tags across just those titles.
        // The MVP keeps it simple: a single follow-up query joining the matching title ids to tag rows.
        List<TitleRow> matching = getActressTitles(actressId, filter);
        List<Long> ids = matching.stream().map(TitleRow::titleId).toList();
        if (ids.isEmpty()) return List.of();
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT etd.name AS name, COUNT(*) AS cnt
                FROM title_enrichment_tags tet
                JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                WHERE tet.title_id IN (<ids>)
                  AND etd.surface = 1
                GROUP BY etd.id, etd.name
                ORDER BY cnt DESC, etd.name
                """)
                .bindList("ids", ids)
                .map((rs, ctx) -> new TagFacet(rs.getString("name"), rs.getInt("cnt")))
                .list());
    }

    /**
     * Returns the full enrichment detail for a single title, or null if no enrichment row exists.
     */
    public TitleEnrichmentDetail getTitleEnrichmentDetail(long titleId) {
        return jdbi.withHandle(h -> {
            var row = h.createQuery("""
                    SELECT t.code, tje.javdb_slug, tje.title_original, tje.release_date,
                           tje.duration_minutes, tje.maker, tje.publisher, tje.series,
                           tje.rating_avg, tje.rating_count, tje.cast_json, tje.fetched_at
                    FROM title_javdb_enrichment tje
                    JOIN titles t ON t.id = tje.title_id
                    WHERE tje.title_id = :titleId
                    """)
                    .bind("titleId", titleId)
                    .map((rs, ctx) -> new TitleEnrichmentDetail(
                            titleId,
                            rs.getString("code"),
                            rs.getString("javdb_slug"),
                            rs.getString("title_original"),
                            rs.getString("release_date"),
                            rs.getObject("duration_minutes") != null ? rs.getInt("duration_minutes") : null,
                            rs.getString("maker"),
                            rs.getString("publisher"),
                            rs.getString("series"),
                            rs.getObject("rating_avg")    != null ? rs.getDouble("rating_avg")   : null,
                            rs.getObject("rating_count")  != null ? rs.getInt("rating_count")    : null,
                            rs.getString("cast_json"),
                            List.of(),   // tags filled below
                            rs.getString("fetched_at")
                    ))
                    .findOne().orElse(null);

            if (row == null) return null;

            List<String> tags = h.createQuery("""
                    SELECT etd.name
                    FROM title_enrichment_tags tet
                    JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                    WHERE tet.title_id = :titleId
                    ORDER BY etd.name
                    """)
                    .bind("titleId", titleId)
                    .mapTo(String.class)
                    .list();

            return new TitleEnrichmentDetail(
                    row.titleId(), row.code(), row.javdbSlug(), row.titleOriginal(),
                    row.releaseDate(), row.durationMinutes(), row.maker(), row.publisher(),
                    row.series(), row.ratingAvg(), row.ratingCount(), row.castJson(),
                    tags, row.fetchedAt()
            );
        });
    }

    // ── Enrichment tag-health (Phase 3 maintenance dashboard) ─────────────────

    public record TagHealthSummary(
            int totalEnrichmentRows,
            int totalDefinitions,
            int mappedDefinitions,        // curated_alias IS NOT NULL
            int unmappedDefinitions,      // curated_alias IS NULL
            int suppressedDefinitions     // surface = 0
    ) {}

    public record TagHealthRow(
            long id,
            String name,
            String curatedAlias,    // nullable
            int titleCount,
            double libraryPct,      // 0.0–1.0; titleCount / totalEnrichmentRows
            boolean surface
    ) {}

    public record TagHealthReport(TagHealthSummary summary, List<TagHealthRow> definitions) {}

    /** Returns full snapshot for the Tag Health view. */
    public TagHealthReport getTagHealthReport() {
        return jdbi.withHandle(h -> {
            int totalRows = h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment").mapTo(Integer.class).one();
            int total     = h.createQuery("SELECT COUNT(*) FROM enrichment_tag_definitions").mapTo(Integer.class).one();
            int mapped    = h.createQuery("SELECT COUNT(*) FROM enrichment_tag_definitions WHERE curated_alias IS NOT NULL").mapTo(Integer.class).one();
            int suppressed = h.createQuery("SELECT COUNT(*) FROM enrichment_tag_definitions WHERE surface = 0").mapTo(Integer.class).one();
            double safeTotal = totalRows > 0 ? (double) totalRows : 1.0;
            List<TagHealthRow> rows = h.createQuery("""
                    SELECT id, name, curated_alias, title_count, surface
                    FROM enrichment_tag_definitions
                    ORDER BY title_count DESC, name
                    """)
                    .map((rs, ctx) -> new TagHealthRow(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("curated_alias"),
                            rs.getInt("title_count"),
                            rs.getInt("title_count") / safeTotal,
                            rs.getInt("surface") != 0
                    ))
                    .list();
            return new TagHealthReport(
                    new TagHealthSummary(totalRows, total, mapped, total - mapped, suppressed),
                    rows
            );
        });
    }

    /** Toggles the surface flag on a single definition. */
    public void setEnrichmentTagSurface(long tagId, boolean surface) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE enrichment_tag_definitions SET surface = :s WHERE id = :id")
                .bind("s", surface ? 1 : 0)
                .bind("id", tagId)
                .execute());
    }

    /**
     * Returns the actress staging profile row, or null if none exists.
     */
    public ProfileRow getActressProfile(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT javdb_slug, status, raw_fetched_at, name_variants_json,
                       avatar_url, local_avatar_path, twitter_handle, instagram_handle, title_count
                FROM javdb_actress_staging
                WHERE actress_id = :actressId
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> {
                    String localPath = rs.getString("local_avatar_path");
                    return new ProfileRow(
                        rs.getString("javdb_slug"),
                        rs.getString("status"),
                        rs.getString("raw_fetched_at"),
                        rs.getString("name_variants_json"),
                        rs.getString("avatar_url"),
                        localPath != null ? "/" + localPath : null,
                        rs.getString("twitter_handle"),
                        rs.getString("instagram_handle"),
                        rs.getObject("title_count") != null ? rs.getInt("title_count") : null
                );})
                .findOne()
                .orElse(null));
    }

    /**
     * Returns titles in conflict for the actress: enriched titles where no javdb cast entry's
     * slug maps back to this actress's javdb_actress_staging row.
     */
    public List<ConflictRow> getActressConflicts(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT t.id AS title_id, t.code, t.label, t.base_code,
                       a.canonical_name AS our_actress_name,
                       jas.javdb_slug AS our_javdb_slug, tje.cast_json
                FROM title_javdb_enrichment tje
                JOIN titles t ON t.id = tje.title_id
                JOIN title_actresses ta ON ta.title_id = t.id AND ta.actress_id = :actressId
                JOIN actresses a ON a.id = :actressId
                LEFT JOIN javdb_actress_staging jas ON jas.actress_id = :actressId
                WHERE NOT EXISTS (
                    SELECT 1 FROM json_each(tje.cast_json) je
                    JOIN javdb_actress_staging jas2
                        ON jas2.javdb_slug = json_extract(je.value, '$.slug')
                    WHERE jas2.actress_id = :actressId
                  )
                ORDER BY t.code
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> {
                    String label    = rs.getString("label");
                    String baseCode = rs.getString("base_code");
                    String coverUrl = null;
                    if (coverPath != null && label != null && baseCode != null) {
                        Title synth = Title.builder().label(label).baseCode(baseCode).build();
                        coverUrl = coverPath.find(synth)
                                .map(p -> "/covers/" + label.toUpperCase() + "/" + p.getFileName())
                                .orElse(null);
                    }
                    return new ConflictRow(
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            rs.getString("our_actress_name"),
                            rs.getString("our_javdb_slug"),
                            rs.getString("cast_json"),
                            coverUrl
                    );
                })
                .list());
    }

    /**
     * Returns active queue items (pending, in_flight, failed) for the Queue tab.
     */
    public List<QueueItem> getActiveQueueItems() {
        List<QueueItem> raw = jdbi.withHandle(h -> h.createQuery("""
                SELECT
                  q.id, q.job_type, q.status, q.attempts,
                  q.actress_id, a.canonical_name AS actress_name,
                  CASE WHEN q.job_type = 'fetch_title' THEN q.target_id ELSE NULL END AS title_id,
                  CASE WHEN q.job_type = 'fetch_title' THEN t.code ELSE NULL END AS title_code,
                  q.updated_at
                FROM javdb_enrichment_queue q
                JOIN actresses a ON a.id = q.actress_id
                LEFT JOIN titles t ON t.id = q.target_id AND q.job_type = 'fetch_title'
                WHERE q.status IN ('pending', 'in_flight', 'failed', 'paused')
                ORDER BY
                  CASE q.status WHEN 'in_flight' THEN 0 WHEN 'pending' THEN 1 WHEN 'paused' THEN 2 ELSE 3 END,
                  COALESCE(q.sort_order, 9223372036854775807) ASC,
                  q.id ASC
                """)
                .map((rs, ctx) -> new QueueItem(
                        rs.getLong("id"),
                        rs.getString("job_type"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getLong("actress_id"),
                        rs.getString("actress_name"),
                        rs.getObject("title_id") != null ? rs.getLong("title_id") : null,
                        rs.getString("title_code"),
                        rs.getString("updated_at"),
                        null
                ))
                .list());

        // Assign 1-based positions to pending items in their rendered order.
        int pendingPos = 0;
        List<QueueItem> result = new java.util.ArrayList<>(raw.size());
        for (QueueItem item : raw) {
            if ("pending".equals(item.status())) {
                result.add(new QueueItem(item.id(), item.jobType(), item.status(), item.attempts(),
                        item.actressId(), item.actressName(), item.titleId(), item.titleCode(),
                        item.updatedAt(), ++pendingPos));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Returns current queue counts broken down by status.
     */
    public QueueStatus getQueueStatus() {
        boolean isPaused = runner.isPaused();
        java.time.Instant pauseUntil = runner.getPauseUntil();
        String rateLimitPausedUntil = java.time.Instant.now().isBefore(pauseUntil)
                ? pauseUntil.toString() : null;
        String rateLimitPauseReason = runner.getPauseReason();
        int consecutiveHits = runner.getConsecutiveRateLimitHits();
        String pauseType = runner.getPauseType();
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT
                  SUM(CASE WHEN status = 'pending'   THEN 1 ELSE 0 END) AS pending,
                  SUM(CASE WHEN status = 'in_flight' THEN 1 ELSE 0 END) AS in_flight,
                  SUM(CASE WHEN status = 'failed'    THEN 1 ELSE 0 END) AS failed,
                  SUM(CASE WHEN status = 'paused'    THEN 1 ELSE 0 END) AS paused_items
                FROM javdb_enrichment_queue
                """)
                .map((rs, ctx) -> new QueueStatus(
                        rs.getInt("pending"),
                        rs.getInt("in_flight"),
                        rs.getInt("failed"),
                        rs.getInt("paused_items"),
                        isPaused,
                        rateLimitPausedUntil,
                        rateLimitPauseReason,
                        consecutiveHits,
                        pauseType
                ))
                .one());
    }
}
