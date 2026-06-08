package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects duplicate actress records that resolve to the SAME javdb actress — the
 * deterministic, zero-false-positive signal for auto-merge candidates.
 *
 * <p>A "resolved javdb slug" is derived per actress from two sources, in priority order:
 * <ol>
 *   <li>{@code javdb_actress_staging.javdb_slug} — explicit slug already recorded for
 *       this actress.</li>
 *   <li>Cast-derived slug — a female cast entry in {@code title_javdb_enrichment.cast_json}
 *       whose {@code name} (whitespace-stripped) matches the actress's {@code stage_name}
 *       or any alias. Requires that every such matching entry across all her enriched
 *       titles agrees on the same slug (consistency guard;
 *       {@code HAVING COUNT(DISTINCT slug) = 1}) to avoid noise.</li>
 * </ol>
 *
 * <p>Actresses are then grouped by their resolved slug; any group with >= 2 distinct
 * actress_ids is a duplicate cluster. Note: because {@code javdb_actress_staging.javdb_slug}
 * has a UNIQUE index, two staging rows can never share a slug. Real clusters are always
 * staging-A vs cast-derived-B (or cast-B vs cast-C) collisions.
 *
 * <p>Sentinel actresses (Various / Unknown / Amateur) are excluded by default.
 * Actresses with no resolved slug (not enriched, no staging row, no matching cast entry)
 * are invisible to this tool — a known limitation.
 */
public class FindSlugDuplicateActressesTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT     = 1000;

    private final Jdbi jdbi;

    public FindSlugDuplicateActressesTool(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override public String name() { return "find_slug_duplicate_actresses"; }

    @Override public String description() {
        return "Find actress records that map to the same javdb slug — the deterministic signal for "
             + "merge candidates. Resolves each actress's javdb slug from (1) javdb_actress_staging, "
             + "or (2) a female cast_json entry whose name matches stage_name or any alias consistently "
             + "across her enriched titles. Groups actresses by resolved slug; returns clusters of ≥2 "
             + "distinct actress_ids with a suggested survivor. Read-only, no mutation.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer",
                        "Maximum number of duplicate clusters to return. Default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ".",
                        DEFAULT_LIMIT)
                .prop("exclude_sentinel", "boolean",
                        "Exclude sentinel actresses (Various / Unknown / Amateur). Default true.",
                        true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit          = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        boolean excludeSentinel = Schemas.optBoolean(args, "exclude_sentinel", true);

        List<SlugCluster> clusters = findSlugDuplicateClusters(jdbi, excludeSentinel, limit);
        return new Result(clusters.size(), clusters);
    }

    // ── Core resolution logic (also used by tests directly) ─────────────────

    /**
     * Resolves each actress's javdb slug from staging or cast_json, then groups by slug.
     * Returns clusters where >= 2 distinct actress_ids share the same resolved slug.
     *
     * <p>Resolution steps:
     * <ol>
     *   <li>Staging slugs: read from {@code javdb_actress_staging}.</li>
     *   <li>Cast-derived slugs: for each actress, find all female cast entries (in any of
     *       her credited titles' {@code title_javdb_enrichment.cast_json}) whose name
     *       (whitespace-stripped) matches her {@code stage_name} or any alias. If all
     *       such matches agree on the same slug, that slug is her cast-derived slug.</li>
     *   <li>Merge: prefer staging slug; else use cast-derived slug if available.</li>
     * </ol>
     *
     * <p>Within each cluster, the suggested survivor is the actress with the most titles
     * (desc), then has a non-null stage_name (has-kanji preferred), then lowest id.
     */
    public static List<SlugCluster> findSlugDuplicateClusters(Jdbi jdbi, boolean excludeSentinel, int limit) {
        // Step 1: load all staging slugs (actress_id → slug).
        Map<Long, String> stagingSlugs = new HashMap<>();
        jdbi.useHandle(h ->
                h.createQuery("""
                        SELECT actress_id, javdb_slug
                        FROM javdb_actress_staging
                        WHERE javdb_slug IS NOT NULL
                        """)
                 .mapToMap()
                 .forEach(row -> {
                     Number id   = (Number) row.get("actress_id");
                     String slug = (String) row.get("javdb_slug");
                     if (id != null && slug != null) {
                         stagingSlugs.put(id.longValue(), slug);
                     }
                 })
        );

        // Step 2: load per-actress metadata (id, canonical_name, stage_name, tier, title_count,
        // is_sentinel). We also need is_sentinel for filtering.
        record ActressRow(long id, String canonicalName, String stageName, String tier,
                          int titleCount, boolean isSentinel) {}

        List<ActressRow> actresses = new ArrayList<>();
        String actressesSql = excludeSentinel
                ? """
                        SELECT a.id,
                               a.canonical_name,
                               a.stage_name,
                               a.tier,
                               COALESCE(ta_cnt.title_count, 0) AS title_count,
                               COALESCE(a.is_sentinel, 0)       AS is_sentinel
                        FROM actresses a
                        LEFT JOIN (
                            SELECT actress_id, COUNT(DISTINCT title_id) AS title_count
                            FROM title_actresses
                            GROUP BY actress_id
                        ) ta_cnt ON ta_cnt.actress_id = a.id
                        WHERE COALESCE(a.is_sentinel, 0) = 0"""
                : """
                        SELECT a.id,
                               a.canonical_name,
                               a.stage_name,
                               a.tier,
                               COALESCE(ta_cnt.title_count, 0) AS title_count,
                               COALESCE(a.is_sentinel, 0)       AS is_sentinel
                        FROM actresses a
                        LEFT JOIN (
                            SELECT actress_id, COUNT(DISTINCT title_id) AS title_count
                            FROM title_actresses
                            GROUP BY actress_id
                        ) ta_cnt ON ta_cnt.actress_id = a.id""";
        jdbi.useHandle(h ->
                h.createQuery(actressesSql)
                 .mapToMap()
                 .forEach(row -> {
                     Number id          = (Number) row.get("id");
                     String cname       = (String) row.get("canonical_name");
                     String sname       = (String) row.get("stage_name");
                     String tier        = (String) row.get("tier");
                     Number cnt         = (Number) row.get("title_count");
                     Number sentinel    = (Number) row.get("is_sentinel");
                     if (id == null) return;
                     actresses.add(new ActressRow(
                             id.longValue(),
                             cname,
                             sname,
                             tier != null ? tier : "LIBRARY",
                             cnt  != null ? cnt.intValue() : 0,
                             sentinel != null && sentinel.intValue() != 0));
                 })
        );

        // Step 3: compute cast-derived slugs for actresses that don't have a staging slug.
        // Match: female cast entry whose name (whitespace-stripped) equals the actress's
        // stage_name (whitespace-stripped) OR any alias (whitespace-stripped).
        // Consistency guard: HAVING COUNT(DISTINCT slug) = 1 across all her enriched titles.
        Map<Long, String> castDerivedSlugs = new HashMap<>();
        // Build sentinel clause inline to avoid SQL tokenization bugs from string concat.
        String castSql = "SELECT ta.actress_id, json_extract(je.value, '$.slug') AS cast_slug\n"
                + "FROM title_actresses ta\n"
                + "JOIN actresses a ON a.id = ta.actress_id\n"
                + "JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id\n"
                + "JOIN json_each(tje.cast_json) je\n"
                + "WHERE json_extract(je.value, '$.gender') = 'F'\n"
                + "  AND json_extract(je.value, '$.slug') IS NOT NULL\n"
                + "  AND (\n"
                + "      (a.stage_name IS NOT NULL\n"
                + "       AND REPLACE(json_extract(je.value, '$.name'), ' ', '') = REPLACE(a.stage_name, ' ', ''))\n"
                + "      OR\n"
                + "      EXISTS (\n"
                + "          SELECT 1 FROM actress_aliases aa\n"
                + "          WHERE aa.actress_id = a.id\n"
                + "            AND REPLACE(json_extract(je.value, '$.name'), ' ', '') = REPLACE(aa.alias_name, ' ', '')\n"
                + "      )\n"
                + "  )\n"
                + (excludeSentinel ? "  AND COALESCE(a.is_sentinel, 0) = 0\n" : "")
                + "GROUP BY ta.actress_id\n"
                + "HAVING COUNT(DISTINCT json_extract(je.value, '$.slug')) = 1";
        jdbi.useHandle(h ->
                h.createQuery(castSql)
                 .mapToMap()
                 .forEach(row -> {
                     Number id   = (Number) row.get("actress_id");
                     String slug = (String) row.get("cast_slug");
                     if (id != null && slug != null) {
                         castDerivedSlugs.put(id.longValue(), slug);
                     }
                 })
        );

        // Step 4: resolve each actress's effective slug (staging preferred, then cast-derived).
        Map<String, List<ActressRow>> bySlug = new HashMap<>();
        for (ActressRow a : actresses) {
            String slug = stagingSlugs.containsKey(a.id())
                    ? stagingSlugs.get(a.id())
                    : castDerivedSlugs.get(a.id());
            if (slug == null) continue; // unresolvable — skip
            bySlug.computeIfAbsent(slug, k -> new ArrayList<>()).add(a);
        }

        // Step 5: collect clusters with >= 2 members.
        List<SlugCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<ActressRow>> entry : bySlug.entrySet()) {
            List<ActressRow> members = entry.getValue();
            if (members.size() < 2) continue;

            String slug = entry.getKey();

            // Build cluster members, sorted: most titles DESC, has stage_name DESC, id ASC.
            List<ActressRow> sorted = new ArrayList<>(members);
            sorted.sort(Comparator
                    .comparingInt(ActressRow::titleCount).reversed()
                    .thenComparing(r -> r.stageName() == null ? 1 : 0)
                    .thenComparingLong(ActressRow::id));

            long survivorId = sorted.get(0).id();

            List<ClusterMember> memberDtos = new ArrayList<>();
            for (ActressRow m : members) {
                boolean hasStagingSlug = stagingSlugs.containsKey(m.id());
                String source = hasStagingSlug ? "staging" : "cast_derived";
                memberDtos.add(new ClusterMember(
                        m.id(),
                        m.canonicalName(),
                        m.stageName(),
                        m.tier(),
                        m.titleCount(),
                        source,
                        m.id() == survivorId));
            }
            // Sort output members: survivor first, then by id.
            memberDtos.sort(Comparator
                    .comparingInt(c -> c.suggestedSurvivor() ? 0 : 1));

            clusters.add(new SlugCluster(slug, memberDtos.size(), memberDtos));
        }

        // Sort clusters: largest cluster first, then slug for determinism.
        clusters.sort(Comparator
                .comparingInt(SlugCluster::memberCount).reversed()
                .thenComparing(SlugCluster::javdbSlug));

        if (clusters.size() > limit) {
            clusters = new ArrayList<>(clusters.subList(0, limit));
        }
        return clusters;
    }

    // ── Result types ─────────────────────────────────────────────────────────

    public record ClusterMember(
            long actressId,
            String canonicalName,
            String stageName,
            String tier,
            int titleCount,
            /** "staging" or "cast_derived" — how the slug was resolved for this member. */
            String slugSource,
            boolean suggestedSurvivor
    ) {}

    public record SlugCluster(
            String javdbSlug,
            int memberCount,
            List<ClusterMember> members
    ) {}

    public record Result(int clusterCount, List<SlugCluster> clusters) {}
}
