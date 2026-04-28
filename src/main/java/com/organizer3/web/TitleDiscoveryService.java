package com.organizer3.web;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.javdb.enrichment.EnrichmentJob;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.ProfileChainGate;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Read + bulk-enqueue surface for the title-driven enrichment Titles tab.
 *
 * <p>Surfaces titles that are <em>unenriched</em> (no successful javdb staging row)
 * from two sources: recently-added titles across the library, and titles in
 * {@code sort_pool} volumes. Titles with two or more credited actresses are excluded
 * (those belong to the Collections tab — M3).
 *
 * <p>"Unenriched" here means there is no row in {@code title_javdb_enrichment} for the
 * title — that table is the authoritative post-promotion enrichment record. The raw
 * staging table ({@code javdb_title_staging}) is reported in the {@code stagingStatus}
 * field for UI badging (e.g. "slug only") but does not gate row visibility, since a
 * staging row may be absent or cleaned up after a successful promotion.
 */
public class TitleDiscoveryService {

    /** Hard cap on a single bulk enqueue request. */
    public static final int ENQUEUE_CAP = 100;
    private static final String STRUCTURE_SORT_POOL = "sort_pool";
    /**
     * Structure types whose titles are excluded from "All recent". {@code queue}-type volumes
     * (e.g. {@code unsorted}) hold titles that aren't yet ready for enrichment — they need
     * curation first. Pool views remain unaffected (the chip explicitly scopes to one volume).
     */
    private static final String STRUCTURE_QUEUE = "queue";

    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final ProfileChainGate gate;
    private final EnrichmentQueue queue;

    public TitleDiscoveryService(Jdbi jdbi, OrganizerConfig config,
                                 ProfileChainGate gate, EnrichmentQueue queue) {
        this.jdbi   = jdbi;
        this.config = config;
        this.gate   = gate;
        this.queue  = queue;
    }

    // ── Records ────────────────────────────────────────────────────────────

    /** A single credited actress on a title. {@code eligibility} is one of the
     *  {@link ProfileChainGate} codes: {@code eligible} | {@code below_threshold} | {@code sentinel}. */
    public record ActressCredit(long id, String name, String eligibility) {}

    public record TitleRow(
            long titleId,
            String code,
            String titleEnglish,
            ActressCredit actress,   // null when no credit
            String volumeId,
            String addedDate,
            String stagingStatus,    // null | 'slug_only' | (anything other than 'fetched')
            String queueStatus       // null | 'pending' | 'in_flight' | 'failed'
    ) {}

    /** Multi-actress row for the Collections tab. {@code cast} contains all credited
     *  actresses (size >= 2). */
    public record CollectionRow(
            long titleId,
            String code,
            String titleEnglish,
            List<ActressCredit> cast,
            String volumeId,
            String addedDate,
            String queueStatus
    ) {}

    public record TitlePage(List<TitleRow> rows, int page, int pageSize, boolean hasMore, int totalPages) {}
    public record CollectionPage(List<CollectionRow> rows, int page, int pageSize, boolean hasMore, int totalPages) {}

    public record PoolChip(String volumeId, int unenrichedCount) {}

    // ── Queries ────────────────────────────────────────────────────────────

    /** Recently-added unenriched titles, sorted by latest title_locations.added_date desc. */
    public TitlePage listRecent(int page, int pageSize) {
        return listInternal(/*volumeId*/ null, page, pageSize);
    }

    /** Unenriched titles in a single sort_pool volume, sorted by titles.code asc. */
    public TitlePage listPool(String volumeId, int page, int pageSize) {
        if (volumeId == null || volumeId.isBlank()) {
            throw new IllegalArgumentException("volumeId is required for listPool");
        }
        return listInternal(volumeId, page, pageSize);
    }

    /**
     * Unenriched-title counts for every {@code sort_pool} volume, in config order.
     * Volumes with zero unenriched titles are still included (greyed-out chips).
     */
    public List<PoolChip> listPools() {
        List<String> poolIds = config.volumes().stream()
                .filter(v -> STRUCTURE_SORT_POOL.equals(v.structureType()))
                .map(VolumeConfig::id)
                .toList();
        if (poolIds.isEmpty()) return List.of();

        return jdbi.withHandle(h -> {
            List<PoolChip> chips = new ArrayList<>(poolIds.size());
            for (String id : poolIds) {
                int count = h.createQuery("""
                        SELECT COUNT(DISTINCT t.id)
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id AND tl.volume_id = :volumeId
                        LEFT JOIN title_javdb_enrichment tje ON tje.title_id = t.id
                        WHERE tje.title_id IS NULL
                          AND (SELECT COUNT(*) FROM title_actresses ta WHERE ta.title_id = t.id) <= 1
                          AND t.code NOT LIKE '\\_%' ESCAPE '\\'
                        """)
                        .bind("volumeId", id)
                        .mapTo(Integer.class)
                        .one();
                chips.add(new PoolChip(id, count));
            }
            return chips;
        });
    }

    /**
     * Multi-actress unenriched titles, regardless of volume structure type. Sorted by
     * {@code titles.code} ascending for linear curation.
     *
     * <p>The intentional volume scope is "anywhere" — multi-actress titles on conventional /
     * sort_pool / exhibition volumes are real and surfacing them here keeps them enrichable
     * without forcing a volume-classification change first. The volume column on each row
     * exposes the source so the user can spot misplaced titles.
     */
    public CollectionPage listCollections(int page, int pageSize) {
        int p  = Math.max(page, 0);
        int ps = Math.max(pageSize, 1);
        int offset = p * ps;
        int limit = ps + 1;

        // One row per title (groups via title_id). cast is collected in a follow-up query
        // because grouping a chip-list on the SQL side is awkward.
        String sql = """
                SELECT
                  t.id            AS title_id,
                  t.code          AS code,
                  t.title_english AS title_english,
                  loc.added_date  AS added_date,
                  loc.volume_id   AS volume_id,
                  jeq.queue_status AS queue_status
                FROM titles t
                LEFT JOIN (
                    SELECT title_id, volume_id, added_date,
                           ROW_NUMBER() OVER (
                               PARTITION BY title_id
                               ORDER BY (added_date IS NULL), added_date DESC
                           ) AS rn
                    FROM title_locations
                ) loc ON loc.title_id = t.id AND loc.rn = 1
                LEFT JOIN title_javdb_enrichment tje ON tje.title_id = t.id
                LEFT JOIN (
                    SELECT target_id,
                           CASE
                             WHEN SUM(CASE WHEN status = 'in_flight' THEN 1 ELSE 0 END) > 0 THEN 'in_flight'
                             WHEN SUM(CASE WHEN status = 'pending'   THEN 1 ELSE 0 END) > 0 THEN 'pending'
                             WHEN SUM(CASE WHEN status = 'failed'    THEN 1 ELSE 0 END) > 0 THEN 'failed'
                             ELSE NULL
                           END AS queue_status
                    FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_title'
                    GROUP BY target_id
                ) jeq ON jeq.target_id = t.id
                WHERE tje.title_id IS NULL
                  AND (SELECT COUNT(*) FROM title_actresses WHERE title_id = t.id) >= 2
                  AND t.code NOT LIKE '\\_%' ESCAPE '\\'
                  AND loc.title_id IS NOT NULL
                ORDER BY t.code ASC
                LIMIT :limit OFFSET :offset
                """;

        // Skeleton rows (no cast yet).
        record Skeleton(long titleId, String code, String titleEnglish,
                        String addedDate, String volumeId, String queueStatus) {}
        List<Skeleton> skel = jdbi.withHandle(h -> h.createQuery(sql)
                .bind("limit", limit).bind("offset", offset)
                .map((rs, ctx) -> new Skeleton(
                        rs.getLong("title_id"),
                        rs.getString("code"),
                        rs.getString("title_english"),
                        rs.getString("added_date"),
                        rs.getString("volume_id"),
                        rs.getString("queue_status")))
                .list());

        // Count query for totalPages.
        int total = jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM (
                    SELECT t.id FROM titles t
                    LEFT JOIN (
                        SELECT title_id, volume_id, added_date,
                               ROW_NUMBER() OVER (
                                   PARTITION BY title_id
                                   ORDER BY (added_date IS NULL), added_date DESC
                               ) AS rn
                        FROM title_locations
                    ) loc ON loc.title_id = t.id AND loc.rn = 1
                    LEFT JOIN title_javdb_enrichment tje ON tje.title_id = t.id
                    WHERE tje.title_id IS NULL
                      AND (SELECT COUNT(*) FROM title_actresses WHERE title_id = t.id) >= 2
                      AND t.code NOT LIKE '\\_%' ESCAPE '\\'
                      AND loc.title_id IS NOT NULL
                )
                """).mapTo(Integer.class).one());
        int totalPages = total == 0 ? 0 : (total + ps - 1) / ps;

        if (skel.isEmpty()) {
            return new CollectionPage(List.of(), p, ps, false, totalPages);
        }

        boolean hasMore = skel.size() > ps;
        if (hasMore) skel = skel.subList(0, ps);

        // Batch-fetch cast for all visible titles, then attach per-actress eligibility.
        List<Long> ids = skel.stream().map(Skeleton::titleId).toList();
        java.util.Map<Long, List<ActressCredit>> cast = new java.util.LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery("""
                SELECT ta.title_id, a.id AS actress_id, a.canonical_name AS name
                FROM title_actresses ta
                JOIN actresses a ON a.id = ta.actress_id
                WHERE ta.title_id IN (<ids>)
                ORDER BY ta.title_id, a.canonical_name
                """)
                .bindList("ids", ids)
                .map((rs, ctx) -> {
                    long titleId = rs.getLong("title_id");
                    long actressId = rs.getLong("actress_id");
                    String name = rs.getString("name");
                    cast.computeIfAbsent(titleId, k -> new ArrayList<>())
                            .add(new ActressCredit(actressId, name, gate.eligibility(actressId)));
                    return null;
                })
                .list());

        List<CollectionRow> rows = new ArrayList<>(skel.size());
        for (Skeleton s : skel) {
            rows.add(new CollectionRow(
                    s.titleId(), s.code(), s.titleEnglish(),
                    cast.getOrDefault(s.titleId(), List.of()),
                    s.volumeId(), s.addedDate(), s.queueStatus()));
        }
        return new CollectionPage(List.copyOf(rows), p, ps, hasMore, totalPages);
    }

    /**
     * Bulk-enqueue title-driven fetch_title jobs.
     *
     * @param source one of {@code 'recent'}, {@code 'pool'}, or {@code 'collection'}
     * @param titleIds list of title ids; truncated to {@link #ENQUEUE_CAP}
     * @return number of titles processed (after cap)
     */
    public int enqueue(String source, List<Long> titleIds) {
        if (!EnrichmentJob.SOURCE_RECENT.equals(source)
                && !EnrichmentJob.SOURCE_POOL.equals(source)
                && !EnrichmentJob.SOURCE_COLLECTION.equals(source)) {
            throw new IllegalArgumentException("source must be 'recent', 'pool', or 'collection', got: " + source);
        }
        if (titleIds == null || titleIds.isEmpty()) return 0;
        List<Long> trimmed = titleIds.size() > ENQUEUE_CAP
                ? titleIds.subList(0, ENQUEUE_CAP)
                : titleIds;

        boolean isCollection = EnrichmentJob.SOURCE_COLLECTION.equals(source);
        for (Long titleId : trimmed) {
            // Collection jobs always pass actressId=null — the runner handles per-cast chaining.
            Long actressId = isCollection ? null : lookupSingleActressId(titleId);
            queue.enqueueTitle(source, titleId, actressId);
        }
        return trimmed.size();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private TitlePage listInternal(String volumeId, int page, int pageSize) {
        int p  = Math.max(page, 0);
        int ps = Math.max(pageSize, 1);
        int offset = p * ps;
        // Fetch one extra to detect hasMore without a separate count query.
        int limit = ps + 1;

        boolean isPool = volumeId != null;

        // Latest location per title — for the recent view we sort/display by this.
        // For the pool view we restrict to a single volume_id directly in WHERE.
        String sql = """
                SELECT
                  t.id                  AS title_id,
                  t.code                AS code,
                  t.title_english       AS title_english,
                  a.id                  AS actress_id,
                  a.canonical_name      AS actress_name,
                  loc.added_date        AS added_date,
                  loc.volume_id         AS volume_id,
                  jts.status            AS staging_status,
                  jeq.queue_status      AS queue_status
                FROM titles t
                LEFT JOIN title_actresses ta ON ta.title_id = t.id
                LEFT JOIN actresses a ON a.id = ta.actress_id
                LEFT JOIN (
                    SELECT title_id, volume_id, added_date,
                           ROW_NUMBER() OVER (
                               PARTITION BY title_id
                               ORDER BY (added_date IS NULL), added_date DESC
                           ) AS rn
                    FROM title_locations
                """ + (isPool ? "WHERE volume_id = :volumeId\n" : "")
                + """
                ) loc ON loc.title_id = t.id AND loc.rn = 1
                LEFT JOIN volumes v ON v.id = loc.volume_id
                LEFT JOIN javdb_title_staging jts ON jts.title_id = t.id
                LEFT JOIN title_javdb_enrichment tje ON tje.title_id = t.id
                LEFT JOIN (
                    SELECT target_id,
                           CASE
                             WHEN SUM(CASE WHEN status = 'in_flight' THEN 1 ELSE 0 END) > 0 THEN 'in_flight'
                             WHEN SUM(CASE WHEN status = 'pending'   THEN 1 ELSE 0 END) > 0 THEN 'pending'
                             WHEN SUM(CASE WHEN status = 'failed'    THEN 1 ELSE 0 END) > 0 THEN 'failed'
                             ELSE NULL
                           END AS queue_status
                    FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_title'
                    GROUP BY target_id
                ) jeq ON jeq.target_id = t.id
                WHERE tje.title_id IS NULL
                  AND (SELECT COUNT(*) FROM title_actresses WHERE title_id = t.id) <= 1
                  AND t.code NOT LIKE '\\_%' ESCAPE '\\'
                """
                + (isPool
                    ? "  AND loc.volume_id = :volumeId\n"
                    : "  AND loc.title_id IS NOT NULL\n  AND v.structure_type <> '" + STRUCTURE_QUEUE + "'\n")
                + (isPool ? "ORDER BY t.code ASC\n" : "ORDER BY loc.added_date DESC\n")
                + "LIMIT :limit OFFSET :offset";

        List<TitleRow> raw = jdbi.withHandle(h -> {
            var q = h.createQuery(sql)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (isPool) q.bind("volumeId", volumeId);
            return q.map((rs, ctx) -> {
                long actressId = rs.getLong("actress_id");
                boolean actressNull = rs.wasNull();
                ActressCredit credit = actressNull ? null : new ActressCredit(
                        actressId,
                        rs.getString("actress_name"),
                        gate.eligibility(actressId)
                );
                return new TitleRow(
                        rs.getLong("title_id"),
                        rs.getString("code"),
                        rs.getString("title_english"),
                        credit,
                        rs.getString("volume_id"),
                        rs.getString("added_date"),
                        rs.getString("staging_status"),
                        rs.getString("queue_status")
                );
            }).list();
        });

        boolean hasMore = raw.size() > ps;
        List<TitleRow> rows = hasMore ? raw.subList(0, ps) : raw;

        // Count query — mirrors the data query's filter clause but without ORDER/LIMIT and
        // without joining queue/staging/actress tables (those don't affect inclusion).
        String countSql = """
                SELECT COUNT(*) FROM (
                    SELECT t.id
                    FROM titles t
                    LEFT JOIN (
                        SELECT title_id, volume_id, added_date,
                               ROW_NUMBER() OVER (
                                   PARTITION BY title_id
                                   ORDER BY (added_date IS NULL), added_date DESC
                               ) AS rn
                        FROM title_locations
                """ + (isPool ? "WHERE volume_id = :volumeId\n" : "")
                + """
                    ) loc ON loc.title_id = t.id AND loc.rn = 1
                    LEFT JOIN volumes v ON v.id = loc.volume_id
                    LEFT JOIN title_javdb_enrichment tje ON tje.title_id = t.id
                    WHERE tje.title_id IS NULL
                      AND (SELECT COUNT(*) FROM title_actresses WHERE title_id = t.id) <= 1
                      AND t.code NOT LIKE '\\_%' ESCAPE '\\'
                """
                + (isPool
                    ? "      AND loc.volume_id = :volumeId\n"
                    : "      AND loc.title_id IS NOT NULL\n      AND v.structure_type <> '" + STRUCTURE_QUEUE + "'\n")
                + ")";
        int total = jdbi.withHandle(h -> {
            var q = h.createQuery(countSql);
            if (isPool) q.bind("volumeId", volumeId);
            return q.mapTo(Integer.class).one();
        });
        int totalPages = total == 0 ? 0 : (total + ps - 1) / ps;
        return new TitlePage(List.copyOf(rows), p, ps, hasMore, totalPages);
    }

    /** Returns the single credited actress's id, or null when 0 or >1 actresses are credited. */
    private Long lookupSingleActressId(long titleId) {
        return jdbi.withHandle(h -> {
            List<Long> ids = h.createQuery(
                    "SELECT actress_id FROM title_actresses WHERE title_id = :titleId LIMIT 2")
                    .bind("titleId", titleId)
                    .mapTo(Long.class)
                    .list();
            return ids.size() == 1 ? ids.get(0) : null;
        });
    }

}
