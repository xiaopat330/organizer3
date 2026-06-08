package com.organizer3.mcp.tools;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.mcp.tools.FindSlugDuplicateActressesTool.SlugCluster;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FindSlugDuplicateActressesTool#findSlugDuplicateClusters}.
 *
 * <p>Uses real in-memory SQLite + SchemaInitializer following the repository test pattern.
 *
 * <p>Key constraint: {@code javdb_actress_staging.javdb_slug} has a UNIQUE index, so
 * clusters always involve at least one cast-derived member. The tests reflect this:
 * staging-A vs cast-derived-B is the canonical cluster shape.
 */
class FindSlugDuplicateActressesToolTest {

    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Insert a minimal actress row and return the given id. */
    private void insertActress(long id, String canonicalName, String stageName, String tier) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO actresses(id, canonical_name, stage_name, tier, first_seen_at)
                VALUES (:id, :cn, :sn, :tier, '2024-01-01')
                """)
                .bind("id",   id)
                .bind("cn",   canonicalName)
                .bind("sn",   stageName)
                .bind("tier", tier)
                .execute());
    }

    /** Insert a sentinel actress (is_sentinel=1). */
    private void insertSentinel(long id, String canonicalName) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO actresses(id, canonical_name, stage_name, tier, first_seen_at, is_sentinel)
                VALUES (:id, :cn, NULL, 'LIBRARY', '2024-01-01', 1)
                """)
                .bind("id", id)
                .bind("cn", canonicalName)
                .execute());
    }

    /** Insert a staging row binding actress_id → javdb_slug. */
    private void insertStaging(long actressId, String javdbSlug) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging(actress_id, javdb_slug, status)
                VALUES (:aid, :slug, 'slug_only')
                """)
                .bind("aid",  actressId)
                .bind("slug", javdbSlug)
                .execute());
    }

    /** Insert an alias for an actress. */
    private void insertAlias(long actressId, String aliasName) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO actress_aliases(actress_id, alias_name)
                VALUES (:aid, :alias)
                """)
                .bind("aid",   actressId)
                .bind("alias", aliasName)
                .execute());
    }

    /** Insert a minimal title and return the id (title_code is the 'code' column). */
    private long insertTitle(String code) {
        return jdbi.withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO titles(code)
                    VALUES (:code)
                    """)
                    .bind("code", code)
                    .execute();
            return h.createQuery("SELECT id FROM titles WHERE code = :code")
                    .bind("code", code)
                    .mapTo(Long.class)
                    .one();
        });
    }

    /** Link an actress to a title (title_actresses row). */
    private void linkActressToTitle(long actressId, long titleId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT OR IGNORE INTO title_actresses(actress_id, title_id)
                VALUES (:aid, :tid)
                """)
                .bind("aid", actressId)
                .bind("tid", titleId)
                .execute());
    }

    /**
     * Insert a {@code title_javdb_enrichment} row with a cast_json array.
     * {@code castJson} format: {@code [{"slug":"…","name":"…","gender":"F"}, …]}.
     */
    private void insertEnrichment(long titleId, String javdbSlug, String castJson) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at, cast_json)
                VALUES (:tid, :slug, '2024-01-01T00:00:00Z', :cast)
                """)
                .bind("tid",  titleId)
                .bind("slug", javdbSlug)
                .bind("cast", castJson)
                .execute());
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * Canonical cluster: actress A has a staging slug "xv3Gg"; actress B has no staging
     * slug but is credited on a title whose cast_json has {slug:"xv3Gg", name:"月雲よる", gender:"F"},
     * matching B's stage_name. Both resolve to "xv3Gg" → one cluster of 2.
     */
    @Test
    void stagingVsCastDerived_formsSingleCluster() {
        insertActress(1L, "Yoru Tsukikumo", "月雲よる", "LIBRARY");
        insertActress(2L, "Yoru Tsukumo",   "月雲よる", "LIBRARY");

        // Actress 1 has a staging slug.
        insertStaging(1L, "xv3Gg");

        // Actress 2 is credited on a title whose cast_json matches her stage_name.
        long titleId = insertTitle("ABC-001");
        linkActressToTitle(2L, titleId);
        insertEnrichment(titleId, "xv3Gg",
                """
                [{"slug":"xv3Gg","name":"月雲よる","gender":"F"}]""");

        List<SlugCluster> clusters = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);

        assertEquals(1, clusters.size(), "Expected exactly one cluster");
        SlugCluster c = clusters.get(0);
        assertEquals("xv3Gg", c.javdbSlug());
        assertEquals(2, c.memberCount());

        // Actress 2 has more titles (1 vs 0) — wait, both have 0 title_actresses... Let's check.
        // Both have 0 titles from title_actresses perspective (linkActressToTitle for 2, not 1).
        // Actually: actress 2 was linked to titleId → 1 title; actress 1 → 0. So actress 2 is survivor.
        var survivor = c.members().stream().filter(m -> m.suggestedSurvivor()).findFirst().orElseThrow();
        assertEquals(2L, survivor.actressId(), "Actress with most titles should be suggested survivor");
        assertEquals("cast_derived", survivor.slugSource());

        var staging = c.members().stream().filter(m -> !m.suggestedSurvivor()).findFirst().orElseThrow();
        assertEquals(1L, staging.actressId());
        assertEquals("staging", staging.slugSource());
    }

    /**
     * Two actresses with different resolved slugs must NOT form a cluster.
     */
    @Test
    void differentSlugs_noCluster() {
        insertActress(10L, "Actress Alpha", "美優", "LIBRARY");
        insertActress(11L, "Actress Beta",  "美月", "LIBRARY");

        insertStaging(10L, "aaaaa");
        insertStaging(11L, "bbbbb");

        List<SlugCluster> clusters = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);

        assertTrue(clusters.isEmpty(), "No cluster expected for different slugs");
    }

    /**
     * Actress with no staging slug and inconsistent cast entries (two different slugs
     * across her titles) must NOT be resolved and must not generate a false cluster.
     */
    @Test
    void inconsistentCastSlugs_notResolved() {
        insertActress(20L, "Ambiguous Actress", "曖昧", "LIBRARY");
        insertActress(21L, "Clear Actress",     "曖昧", "LIBRARY");

        // Actress 20 has two enriched titles that disagree on the slug.
        long t1 = insertTitle("T-001");
        long t2 = insertTitle("T-002");
        linkActressToTitle(20L, t1);
        linkActressToTitle(20L, t2);
        insertEnrichment(t1, "slug1", """
                [{"slug":"slug1","name":"曖昧","gender":"F"}]""");
        insertEnrichment(t2, "slug2", """
                [{"slug":"slug2","name":"曖昧","gender":"F"}]""");

        // Actress 21 has a staging slug "slug1".
        insertStaging(21L, "slug1");

        List<SlugCluster> clusters = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);

        // Actress 20 is unresolvable (inconsistent); actress 21 resolves to "slug1" alone.
        assertTrue(clusters.isEmpty(), "Inconsistent actress should not form a false cluster");
    }

    /**
     * Alias match: actress B has no stage_name matching the cast entry, but has an
     * alias that matches. Must still be resolved and cluster with the staging actress.
     */
    @Test
    void aliasMatch_resolvesCorrectly() {
        insertActress(30L, "Star A", "鈴木花",  "LIBRARY");
        insertActress(31L, "Star B", "別名花子", "LIBRARY");

        // Actress 30 has staging slug.
        insertStaging(30L, "zzZzZ");

        // Actress 31 has an alias that matches the cast entry's name.
        insertAlias(31L, "鈴木花");

        long titleId = insertTitle("ALI-001");
        linkActressToTitle(31L, titleId);
        insertEnrichment(titleId, "zzZzZ", """
                [{"slug":"zzZzZ","name":"鈴木花","gender":"F"}]""");

        List<SlugCluster> clusters = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);

        assertEquals(1, clusters.size(), "Alias match should create a cluster");
        assertEquals("zzZzZ", clusters.get(0).javdbSlug());
        assertEquals(2, clusters.get(0).memberCount());
    }

    /**
     * Male cast entries must not be used for slug resolution even if the name matches.
     */
    @Test
    void maleCastEntry_ignored() {
        insertActress(40L, "Female A",    "花子", "LIBRARY");
        insertActress(41L, "Other Person", "花子", "LIBRARY");

        insertStaging(40L, "femSlug");

        // Title has a male cast entry with the same name as actress 41's stage_name.
        long titleId = insertTitle("MALE-001");
        linkActressToTitle(41L, titleId);
        insertEnrichment(titleId, "maleSlug", """
                [{"slug":"maleSlug","name":"花子","gender":"M"}]""");

        List<SlugCluster> clusters = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);

        assertTrue(clusters.isEmpty(), "Male cast entry should not resolve actress slug");
    }

    /**
     * Sentinel actresses are excluded when exclude_sentinel=true.
     */
    @Test
    void sentinelExclusion_whenEnabled() {
        insertSentinel(50L, "Various");
        insertActress(51L,  "Normal Star", "星", "LIBRARY");

        insertStaging(50L, "sentSlug");

        // Normal actress also resolves to the same slug via cast.
        long titleId = insertTitle("SENT-001");
        linkActressToTitle(51L, titleId);
        insertEnrichment(titleId, "sentSlug", """
                [{"slug":"sentSlug","name":"星","gender":"F"}]""");

        // With exclusion on: sentinel excluded → no cluster.
        List<SlugCluster> withExclusion = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);
        assertTrue(withExclusion.isEmpty(), "Sentinel should be excluded → no cluster");

        // With exclusion off: sentinel included → cluster.
        List<SlugCluster> withoutExclusion = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, false, 100);
        assertEquals(1, withoutExclusion.size(), "Sentinel included → cluster visible");
    }

    /**
     * Limit param caps returned clusters.
     */
    @Test
    void limitCapsResults() {
        // Create 3 clusters (each with staging A + cast B sharing same slug).
        for (int i = 0; i < 3; i++) {
            long aId = 100L + i * 2;
            long bId = 101L + i * 2;
            String slug = "slug_" + i;
            String kanji = "女優" + i;

            insertActress(aId, "A_" + i, kanji, "LIBRARY");
            insertActress(bId, "B_" + i, kanji, "LIBRARY");
            insertStaging(aId, slug);

            String code = "LIM-" + String.format("%03d", i);
            long titleId = insertTitle(code);
            linkActressToTitle(bId, titleId);
            insertEnrichment(titleId, slug,
                    "[{\"slug\":\"" + slug + "\",\"name\":\"" + kanji + "\",\"gender\":\"F\"}]");
        }

        List<SlugCluster> all   = FindSlugDuplicateActressesTool.findSlugDuplicateClusters(jdbi, true, 100);
        List<SlugCluster> capped = FindSlugDuplicateActressesTool.findSlugDuplicateClusters(jdbi, true, 2);

        assertEquals(3, all.size());
        assertEquals(2, capped.size());
    }

    /**
     * Survivor selection: most titles wins; tie-broken by has-stage_name, then lowest id.
     */
    @Test
    void survivorSelection_mostTitlesWins() {
        insertActress(60L, "Sparse A", "花",    "LIBRARY");
        insertActress(61L, "Rich B",   "花",    "LIBRARY");
        insertActress(62L, "Rich C",   "花",    "LIBRARY");

        insertStaging(60L, "richSlug");

        // Actress 61 and 62 both cast-derive "richSlug"; 61 has 2 titles, 62 has 1.
        long t1 = insertTitle("S-001");
        long t2 = insertTitle("S-002");
        long t3 = insertTitle("S-003");

        linkActressToTitle(61L, t1);
        linkActressToTitle(61L, t2);
        linkActressToTitle(62L, t3);

        insertEnrichment(t1, "richSlug", "[{\"slug\":\"richSlug\",\"name\":\"花\",\"gender\":\"F\"}]");
        insertEnrichment(t2, "richSlug", "[{\"slug\":\"richSlug\",\"name\":\"花\",\"gender\":\"F\"}]");
        insertEnrichment(t3, "richSlug", "[{\"slug\":\"richSlug\",\"name\":\"花\",\"gender\":\"F\"}]");

        List<SlugCluster> clusters = FindSlugDuplicateActressesTool
                .findSlugDuplicateClusters(jdbi, true, 100);

        assertEquals(1, clusters.size());
        SlugCluster c = clusters.get(0);
        assertEquals(3, c.memberCount());

        var survivor = c.members().stream().filter(m -> m.suggestedSurvivor()).findFirst().orElseThrow();
        assertEquals(61L, survivor.actressId(), "Actress with most titles (2) should be survivor");
    }
}
