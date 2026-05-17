package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link SchemaUpgrader} entry-point guard behavior.
 *
 * <p>Individual migration functions rely on specific prior-version table
 * shapes; exercising them in isolation would require hand-crafting each
 * historical schema. The entry-point guard and tail migrations are what
 * run in practice on already-initialized databases.
 */
class SchemaUpgraderTest {

    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void upgradeIsNoOpWhenSchemaAlreadyAtCurrentVersion() {
        new SchemaInitializer(jdbi).initialize();
        int versionBefore = currentVersion();

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(versionBefore, currentVersion(), "no-op path must not bump the version");
    }

    @Test
    void upgradeFromV19StampsV20AndAddsNeedsProfilingColumn() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE actresses DROP COLUMN needs_profiling"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 19");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        boolean present = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='needs_profiling'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(present, "needs_profiling column should exist after v20 migration");
    }

    @Test
    void freshInstallHasNeedsProfilingColumn() {
        new SchemaInitializer(jdbi).initialize();
        boolean present = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='needs_profiling'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(present, "fresh install should include needs_profiling");
        assertEquals(62, currentVersion(), "fresh install should stamp current version (62)");
    }

    @Test
    void upgradeFromV18StampsCurrentAndAddsSizeBytesColumn() {
        // Start from the current schema, drop the size_bytes column, mark the DB as v18,
        // and run upgrade() — the v19 migration should re-add the column; the upgrader
        // then continues to CURRENT_VERSION.
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE videos DROP COLUMN size_bytes"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 18");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        boolean sizeBytesPresent = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('videos') WHERE name='size_bytes'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(sizeBytesPresent, "size_bytes column should exist after v19 migration");
    }

    @Test
    void upgradeFromV20AddsFavoriteClearedAtAndTriggers() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE titles    DROP COLUMN favorite_cleared_at"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE actresses DROP COLUMN favorite_cleared_at"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_titles_favorite_cleared_on_unfav"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_titles_favorite_cleared_on_refav"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_actresses_favorite_cleared_on_unfav"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_actresses_favorite_cleared_on_refav"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 20");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("titles",    "favorite_cleared_at"));
        assertTrue(columnExists("actresses", "favorite_cleared_at"));

        // Trigger behavior smoke check: inserting a favorited title, then un-favoriting,
        // should stamp favorite_cleared_at automatically.
        long id = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite) VALUES ('V21-1','V21-1','V21',1,1)")
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute("UPDATE titles SET favorite = 0 WHERE id = ?", id));
        String stamp = jdbi.withHandle(h -> h.createQuery(
                "SELECT favorite_cleared_at FROM titles WHERE id = ?")
                .bind(0, id).mapTo(String.class).one());
        assertNotNull(stamp, "v21 trigger should stamp favorite_cleared_at on un-favorite");
    }

    @Test
    void upgradeFromV22CreatesMergeCandidatesTable() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS merge_candidates");
            h.execute("PRAGMA user_version = 22");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        boolean tableExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='merge_candidates'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "merge_candidates table should exist after v23 migration");
    }

    @Test
    void upgradeFromV24BackfillsEnrichmentFromStaging() throws Exception {
        // Start with the current schema, drop the v25 tables, re-stamp at v24, seed staging
        // with real javdb fixture payloads, and verify the v25 backfill produces correct
        // enrichment rows + normalized tag rows + accurate title_count values.
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("DROP TABLE IF EXISTS title_enrichment_tags"); } catch (Exception ignore) {}
            try { h.execute("DROP TABLE IF EXISTS enrichment_tag_definitions"); } catch (Exception ignore) {}
            try { h.execute("DROP TABLE IF EXISTS title_javdb_enrichment"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 24");
        });

        // Seed titles + staging rows from the on-disk fixtures. The fixture filename is the
        // javdb slug; we synthesize a code/label/seq from the slug for the titles row.
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        String[] slugs = {"J270dd", "2JyWq", "XeYQzG", "MbABk", "ZNp6J"};
        for (int i = 0; i < slugs.length; i++) {
            String slug = slugs[i];
            String resourcePath = "/javdb/migration-fixtures/" + slug + ".json";
            try (var stream = getClass().getResourceAsStream(resourcePath)) {
                assertNotNull(stream, "missing fixture: " + resourcePath);
                com.fasterxml.jackson.databind.JsonNode node = json.readTree(stream);
                long titleId = i + 1L;
                String code = "FIX-" + (i + 1);
                jdbi.useHandle(h -> {
                    h.createUpdate("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (?,?,?,?,?)")
                            .bind(0, titleId).bind(1, code).bind(2, code).bind(3, "FIX")
                            .bind(4, (int)(titleId)).execute();
                    h.createUpdate("""
                            INSERT INTO javdb_title_staging (
                                title_id, status, javdb_slug, raw_path, raw_fetched_at,
                                title_original, release_date, duration_minutes,
                                maker, publisher, series, rating_avg, rating_count,
                                tags_json, cast_json, cover_url, thumbnail_urls_json
                            ) VALUES (
                                :tid, 'fetched', :slug, :rp, :fa,
                                :to, :rd, :dm,
                                :mk, :pb, :sr, :ra, :rc,
                                :tj, :cj, :cu, :tu
                            )""")
                            .bind("tid", titleId)
                            .bind("slug", slug)
                            .bind("rp", "javdb_raw/title/" + slug + ".json")
                            .bind("fa", textOrNull(node, "fetchedAt"))
                            .bind("to", textOrNull(node, "titleOriginal"))
                            .bind("rd", textOrNull(node, "releaseDate"))
                            .bind("dm", intOrNull(node, "durationMinutes"))
                            .bind("mk", textOrNull(node, "maker"))
                            .bind("pb", textOrNull(node, "publisher"))
                            .bind("sr", textOrNull(node, "series"))
                            .bind("ra", doubleOrNull(node, "ratingAvg"))
                            .bind("rc", intOrNull(node, "ratingCount"))
                            .bind("tj", node.has("tags") ? node.get("tags").toString() : null)
                            .bind("cj", node.has("cast") ? node.get("cast").toString() : null)
                            .bind("cu", textOrNull(node, "coverUrl"))
                            .bind("tu", node.has("thumbnailUrls") ? node.get("thumbnailUrls").toString() : null)
                            .execute();
                });
            }
        }

        // Also seed one not_found and one with malformed tags_json — both should be tolerated.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (99, 'NOT-FOUND','NOT-FOUND','NOT',1)");
            h.execute("""
                    INSERT INTO javdb_title_staging (title_id, status, javdb_slug, raw_path, raw_fetched_at)
                    VALUES (99, 'not_found', NULL, NULL, '2026-04-01T00:00:00Z')
                    """);
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (100, 'BAD-TAGS','BAD-TAGS','BAD',1)");
            h.execute("""
                    INSERT INTO javdb_title_staging (title_id, status, javdb_slug, raw_fetched_at, tags_json)
                    VALUES (100, 'fetched', 'badslug', '2026-04-01T00:00:00Z', 'this-is-not-json')
                    """);
        });

        // Run the migration.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());

        // Enrichment rows: 5 fixture titles + the malformed-tags title (it still has slug + status='fetched');
        // not_found row should be excluded.
        int enrichCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment").mapTo(Integer.class).one());
        assertEquals(6, enrichCount, "5 fixtures + 1 malformed-tags row should produce enrichment rows; not_found is skipped");

        // Tag definitions exist for the unique tag set across the 5 fixtures (malformed row contributes none).
        int tagDefCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_tag_definitions").mapTo(Integer.class).one());
        assertTrue(tagDefCount > 0, "should have produced enrichment tag definitions from fixtures");

        // Spot-check a known tag from the fixtures: 'Solowork' appears in multiple of the chosen samples.
        Integer soloworkCount = jdbi.withHandle(h ->
                h.createQuery("SELECT title_count FROM enrichment_tag_definitions WHERE name = 'Solowork'")
                        .mapTo(Integer.class).findOne().orElse(null));
        assertNotNull(soloworkCount, "'Solowork' tag should be present");
        assertTrue(soloworkCount >= 1, "'Solowork' title_count should match assignments");

        // title_count must equal actual assignment count for every definition.
        int mismatches = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM enrichment_tag_definitions etd
                        WHERE etd.title_count != (SELECT COUNT(*) FROM title_enrichment_tags WHERE tag_id = etd.id)
                        """).mapTo(Integer.class).one());
        assertEquals(0, mismatches, "title_count must match assignment count for every definition");

        // Defaults: every definition starts with curated_alias=NULL, surface=1.
        int badDefaults = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_tag_definitions WHERE curated_alias IS NOT NULL OR surface != 1")
                        .mapTo(Integer.class).one());
        assertEquals(0, badDefaults, "fresh definitions must have curated_alias NULL and surface=1");

        // Re-running upgrade is a no-op (version stays at 25, no duplicate rows).
        int enrichBefore = enrichCount;
        new SchemaUpgrader(jdbi).upgrade();
        int enrichAfter = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment").mapTo(Integer.class).one());
        assertEquals(enrichBefore, enrichAfter, "re-running upgrade should not duplicate rows");
    }

    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode n, String f) {
        return n.has(f) && !n.get(f).isNull() ? n.get(f).asText() : null;
    }
    private static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode n, String f) {
        return n.has(f) && !n.get(f).isNull() ? n.get(f).asInt() : null;
    }
    private static Double doubleOrNull(com.fasterxml.jackson.databind.JsonNode n, String f) {
        return n.has(f) && !n.get(f).isNull() ? n.get(f).asDouble() : null;
    }

    @Test
    void upgradeFromV27AddsGradeSourceAndRatingCurveTable() {
        new SchemaInitializer(jdbi).initialize();
        // Seed a title with a grade to verify backfill, and one without.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num, grade) VALUES (1,'T-001','T-001','T',1,'A+')");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2,'T-002','T-002','T',2)");
            try { h.execute("ALTER TABLE titles DROP COLUMN grade_source"); } catch (Exception ignore) {}
            try { h.execute("DROP TABLE IF EXISTS rating_curve"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 27");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("titles", "grade_source"), "grade_source column should exist");

        // Backfill: grade != null → grade_source = 'ai'; grade is null → grade_source stays null.
        String graded = jdbi.withHandle(h ->
                h.createQuery("SELECT grade_source FROM titles WHERE id = 1").mapTo(String.class).one());
        assertEquals("ai", graded, "existing grade row should be backfilled to grade_source='ai'");

        String ungraded = jdbi.withHandle(h ->
                h.createQuery("SELECT grade_source FROM titles WHERE id = 2").mapTo(String.class).findOne().orElse(null));
        assertNull(ungraded, "null-grade row should remain grade_source=null");

        boolean ratingCurveExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='rating_curve'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(ratingCurveExists, "rating_curve table should exist after v28 migration");

        // Idempotent: running upgrade again must not change row counts or source values.
        new SchemaUpgrader(jdbi).upgrade();
        String gradedAfter = jdbi.withHandle(h ->
                h.createQuery("SELECT grade_source FROM titles WHERE id = 1").mapTo(String.class).one());
        assertEquals("ai", gradedAfter, "re-running upgrade must not corrupt grade_source");
    }

    @Test
    void upgradeFromV30AppliesV31_nullableActressIdSourceAndSentinel() {
        new SchemaInitializer(jdbi).initialize();

        // Seed an actress-driven queue row and sentinel candidates before forcing version=30.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (1,'Various','LIBRARY','2024-01-01')");
            h.execute("INSERT INTO actresses(id, canonical_name, stage_name, tier, first_seen_at) VALUES (2,'Unknown','unknown','LIBRARY','2024-01-01')");
            h.execute("INSERT INTO actresses(id, canonical_name, stage_name, tier, first_seen_at) VALUES (3,'Real Actress','Real Actress','LIBRARY','2024-01-01')");
            // Seed a queue row (actress-driven) — will be preserved and get source='actress'.
            h.execute("INSERT INTO javdb_enrichment_queue(job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at) " +
                      "VALUES ('fetch_title', 42, 3, 'pending', 0, '2026-01-01', '2026-01-01', '2026-01-01')");
            h.execute("PRAGMA user_version = 30");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());

        // actress_id is now nullable and source column exists.
        assertTrue(columnExists("javdb_enrichment_queue", "source"));

        // Existing row preserved with source='actress'.
        String source = jdbi.withHandle(h -> h.createQuery(
                "SELECT source FROM javdb_enrichment_queue WHERE target_id = 42")
                .mapTo(String.class).one());
        assertEquals("actress", source);

        Long actressId = jdbi.withHandle(h -> h.createQuery(
                "SELECT actress_id FROM javdb_enrichment_queue WHERE target_id = 42")
                .mapTo(Long.class).one());
        assertEquals(3L, actressId);

        // is_sentinel column exists.
        assertTrue(columnExists("actresses", "is_sentinel"));

        // Sentinel backfill matches canonical_name (case-insensitive). 'Unknown' is flagged.
        int unknownSentinel = jdbi.withHandle(h -> h.createQuery(
                "SELECT is_sentinel FROM actresses WHERE id = 2").mapTo(Integer.class).one());
        assertEquals(1, unknownSentinel);

        // Real actress → not a sentinel.
        int realSentinel = jdbi.withHandle(h -> h.createQuery(
                "SELECT is_sentinel FROM actresses WHERE id = 3").mapTo(Integer.class).one());
        assertEquals(0, realSentinel);

        // 'Various' with stage_name=NULL is still flagged because we match canonical_name.
        int variousSentinel = jdbi.withHandle(h -> h.createQuery(
                "SELECT is_sentinel FROM actresses WHERE id = 1").mapTo(Integer.class).one());
        assertEquals(1, variousSentinel,
                "canonical_name 'Various' must be flagged sentinel even when stage_name is NULL");

        // Idempotent: running again must not change state.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void upgradeFromV31AppliesV32_fixesSentinelBackfillByCanonicalName() {
        // Simulate a DB that already migrated to v31 with the buggy stage_name-only backfill:
        // canonical_name 'Various' / 'Unknown' / 'Amateur' rows exist with stage_name=NULL
        // and is_sentinel=0 (because the v31 UPDATE didn't match them).
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            // v31 normally recreates javdb_enrichment_queue without priority; simulate that state.
            h.execute("DROP INDEX IF EXISTS idx_jeq_claim_priority");
            try { h.execute("ALTER TABLE javdb_enrichment_queue DROP COLUMN priority"); } catch (Exception ignore) {}
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) " +
                      "VALUES (10,'Various','LIBRARY','2024-01-01',0)");
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) " +
                      "VALUES (11,'Unknown','LIBRARY','2024-01-01',0)");
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) " +
                      "VALUES (12,'Amateur','LIBRARY','2024-01-01',0)");
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) " +
                      "VALUES (13,'Real Actress','LIBRARY','2024-01-01',0)");
            h.execute("PRAGMA user_version = 31");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());

        // All three sentinels are now flagged.
        for (long id : new long[] {10L, 11L, 12L}) {
            int flag = jdbi.withHandle(h -> h.createQuery(
                    "SELECT is_sentinel FROM actresses WHERE id = " + id)
                    .mapTo(Integer.class).one());
            assertEquals(1, flag, "actress id=" + id + " should be flagged sentinel");
        }
        // Real actress untouched.
        int real = jdbi.withHandle(h -> h.createQuery(
                "SELECT is_sentinel FROM actresses WHERE id = 13").mapTo(Integer.class).one());
        assertEquals(0, real);

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void upgradeFromV33AddsDriftDetectionColumns() {
        new SchemaInitializer(jdbi).initialize();
        // Simulate a v33 DB by dropping the v34 columns; then rewind version.
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE javdb_actress_filmography_entry DROP COLUMN stale"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE javdb_actress_filmography DROP COLUMN last_drift_count"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE javdb_actress_filmography DROP COLUMN last_fetch_status"); } catch (Exception ignore) {}
            // v31 normally recreates javdb_enrichment_queue without priority; simulate that state.
            h.execute("DROP INDEX IF EXISTS idx_jeq_claim_priority");
            try { h.execute("ALTER TABLE javdb_enrichment_queue DROP COLUMN priority"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 33");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("javdb_actress_filmography_entry", "stale"),
                "stale column must exist after v34 migration");
        assertTrue(columnExists("javdb_actress_filmography", "last_drift_count"),
                "last_drift_count column must exist after v34 migration");
        assertTrue(columnExists("javdb_actress_filmography", "last_fetch_status"),
                "last_fetch_status column must exist after v34 migration");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void upgradeFromV37AddsRevalidationPendingTable() {
        new SchemaInitializer(jdbi).initialize();
        // Simulate v37 DB by dropping v38 additions and rewinding version.
        jdbi.useHandle(h -> {
            try { h.execute("DROP TABLE IF EXISTS revalidation_pending"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE title_javdb_enrichment DROP COLUMN last_revalidated_at"); } catch (Exception ignore) {}
            // v31 normally recreates javdb_enrichment_queue without priority; simulate that state.
            h.execute("DROP INDEX IF EXISTS idx_jeq_claim_priority");
            try { h.execute("ALTER TABLE javdb_enrichment_queue DROP COLUMN priority"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 37");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("title_javdb_enrichment", "last_revalidated_at"),
                "last_revalidated_at column must exist after v38 migration");

        boolean tableExists = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='revalidation_pending'")
                .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "revalidation_pending table must exist after v38 migration");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void upgradeFromV38AddsLastSeenAtAndDetailColumns() {
        new SchemaInitializer(jdbi).initialize();
        // Simulate a v38 DB by dropping the v39 columns and rewinding version.
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE enrichment_review_queue DROP COLUMN last_seen_at"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE enrichment_review_queue DROP COLUMN detail"); } catch (Exception ignore) {}
            // v31 normally recreates javdb_enrichment_queue without priority; simulate that state.
            h.execute("DROP INDEX IF EXISTS idx_jeq_claim_priority");
            try { h.execute("ALTER TABLE javdb_enrichment_queue DROP COLUMN priority"); } catch (Exception ignore) {}
            // Seed two rows so we can verify the last_seen_at backfill.
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO enrichment_review_queue(title_id, slug, reason, resolver_source, created_at) " +
                      "VALUES (1, 'slug1', 'ambiguous', 'sentinel_short_circuit', '2026-04-01T00:00:00Z')");
            h.execute("INSERT INTO enrichment_review_queue(title_id, slug, reason, resolver_source, created_at) " +
                      "VALUES (2, 'slug2', 'cast_anomaly', 'actress_filmography', '2026-04-02T00:00:00Z')");
            h.execute("PRAGMA user_version = 38");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("enrichment_review_queue", "last_seen_at"),
                "last_seen_at column must exist after v39 migration");
        assertTrue(columnExists("enrichment_review_queue", "detail"),
                "detail column must exist after v39 migration");

        // Every existing row must have last_seen_at backfilled from created_at.
        int nullLastSeen = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_review_queue WHERE last_seen_at IS NULL")
                        .mapTo(Integer.class).one());
        assertEquals(0, nullLastSeen, "all existing rows must have last_seen_at backfilled");

        String lastSeen = jdbi.withHandle(h ->
                h.createQuery("SELECT last_seen_at FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertEquals("2026-04-01T00:00:00Z", lastSeen,
                "last_seen_at must equal created_at after backfill");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void upgradeFromV39AddsPriorityColumnToEnrichmentQueue() {
        new SchemaInitializer(jdbi).initialize();
        // Simulate a v39 DB: drop the priority column and rewind.
        jdbi.useHandle(h -> {
            h.execute("DROP INDEX IF EXISTS idx_jeq_claim_priority");
            try { h.execute("ALTER TABLE javdb_enrichment_queue DROP COLUMN priority"); } catch (Exception ignore) {}
            // Seed one queue row so we can verify the DEFAULT applies on upgrade.
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (1,'Test','LIBRARY','2024-01-01')");
            h.execute("""
                    INSERT INTO javdb_enrichment_queue
                        (job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at)
                    VALUES ('fetch_title', 1, 1, 'pending', 0, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')
                    """);
            h.execute("PRAGMA user_version = 39");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("javdb_enrichment_queue", "priority"),
                "priority column must exist after v40 migration");

        // Existing row must have been defaulted to NORMAL.
        String priority = jdbi.withHandle(h ->
                h.createQuery("SELECT priority FROM javdb_enrichment_queue LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("NORMAL", priority, "existing rows must default to NORMAL");

        // CHECK constraint must reject invalid values.
        try {
            jdbi.useHandle(h -> h.execute(
                    "INSERT INTO javdb_enrichment_queue " +
                    "(job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at, priority) " +
                    "VALUES ('fetch_title', 2, 1, 'pending', 0, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', 'INVALID')"));
            fail("CHECK constraint should reject 'INVALID' priority value");
        } catch (Exception expected) {
            // expected
        }

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void freshInstallHasPriorityColumnOnEnrichmentQueue() {
        new SchemaInitializer(jdbi).initialize();
        assertTrue(columnExists("javdb_enrichment_queue", "priority"),
                "fresh install must include the priority column");
        assertEquals(62, currentVersion());
    }

    // ── v41 migration tests ────────────────────────────────────────────────────

    @Test
    void upgradeFromV40_dischargesStaleFailedRows_withClosedReview() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (1,'Test','LIBRARY','2024-01-01')");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'T-1','T','T',1)");
            h.execute("""
                    INSERT INTO enrichment_review_queue (title_id, slug, reason, resolver_source, resolved_at, resolution)
                    VALUES (1, 'slug1', 'ambiguous', 'code_search_fallback', '2024-01-01T00:00:00Z', 'accepted_gap')
                    """);
            h.execute("""
                    INSERT INTO javdb_enrichment_queue
                        (job_type, target_id, actress_id, source, priority, status, attempts, next_attempt_at, created_at, updated_at, last_error)
                    VALUES ('fetch_title', 1, 1, 'actress', 'NORMAL', 'failed', 1,
                            '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', 'ambiguous')
                    """);
            h.execute("PRAGMA user_version = 40");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        var row = jdbi.withHandle(h ->
                h.createQuery("SELECT status, last_error FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapToMap().one());
        assertEquals("done", row.get("status"), "stale failed row must be discharged to done by v41 migration");
        assertTrue(row.get("last_error").toString().contains("[resolved: backfill_cleanup]"),
                "last_error must be annotated with backfill_cleanup tag");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void upgradeFromV40_leavesFailedRow_withNoClosedReview() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (1,'Test','LIBRARY','2024-01-01')");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'T-1','T','T',1)");
            h.execute("""
                    INSERT INTO javdb_enrichment_queue
                        (job_type, target_id, actress_id, source, priority, status, attempts, next_attempt_at, created_at, updated_at, last_error)
                    VALUES ('fetch_title', 1, 1, 'actress', 'NORMAL', 'failed', 1,
                            '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', 'not_found')
                    """);
            h.execute("PRAGMA user_version = 40");
        });

        new SchemaUpgrader(jdbi).upgrade();

        String status = jdbi.withHandle(h ->
                h.createQuery("SELECT status FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapTo(String.class).one());
        assertEquals("failed", status, "failed row with no closed review must not be touched by v41");
    }

    @Test
    void upgradeFromV40_leavesPendingRow_withClosedReview() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (1,'Test','LIBRARY','2024-01-01')");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'T-1','T','T',1)");
            h.execute("""
                    INSERT INTO enrichment_review_queue (title_id, slug, reason, resolver_source, resolved_at, resolution)
                    VALUES (1, 'slug1', 'ambiguous', 'code_search_fallback', '2024-01-01T00:00:00Z', 'marked_resolved')
                    """);
            h.execute("""
                    INSERT INTO javdb_enrichment_queue
                        (job_type, target_id, actress_id, source, priority, status, attempts, next_attempt_at, created_at, updated_at)
                    VALUES ('fetch_title', 1, 1, 'actress', 'NORMAL', 'pending', 0,
                            '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')
                    """);
            h.execute("PRAGMA user_version = 40");
        });

        new SchemaUpgrader(jdbi).upgrade();

        String status = jdbi.withHandle(h ->
                h.createQuery("SELECT status FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapTo(String.class).one());
        assertEquals("pending", status, "pending row must not be touched by v41 even with closed review");
    }

    // ── v46 migration tests ────────────────────────────────────────────────────

    @Test
    void upgradeFromV45_createsTitlePathHistoryTable() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS title_path_history");
            h.execute("DROP INDEX IF EXISTS idx_title_path_history_lookup");
            h.execute("DROP INDEX IF EXISTS idx_title_path_history_title_id");
            h.execute("PRAGMA user_version = 45");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        boolean tableExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='title_path_history'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "title_path_history table must be created by v46 migration");
        boolean lookupIndex = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_title_path_history_lookup'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(lookupIndex, "idx_title_path_history_lookup must exist after v46");
        boolean titleIndex = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_title_path_history_title_id'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(titleIndex, "idx_title_path_history_title_id must exist after v46");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
    }

    @Test
    void freshInstallHasTitlePathHistoryTable() {
        new SchemaInitializer(jdbi).initialize();
        boolean tableExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='title_path_history'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "fresh install must include title_path_history table");
        assertEquals(62, currentVersion());
    }

    // ── v51 migration tests ────────────────────────────────────────────────────

    @Test
    void upgradeFromV50NfkcNormalizesDraftActressStageName() {
        new SchemaInitializer(jdbi).initialize();
        // Seed a draft_titles row (required FK) and two draft_actresses rows:
        // one with a full-width digit (needs normalization), one already normalized.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'TST-1','TST','TST',1)");
            h.execute("INSERT INTO draft_titles(id, title_id, code, created_at, updated_at) VALUES (1,1,'TST-1','2024-01-01T00:00:00Z','2024-01-01T00:00:00Z')");
            // Full-width '１' (U+FF11) in stage name — should be normalized to '1'.
            h.execute("INSERT INTO draft_actresses(javdb_slug, stage_name, created_at, updated_at) VALUES ('slug-fw','テスト１号','2024-01-01T00:00:00Z','2024-01-01T00:00:00Z')");
            // Already normalized — should not be touched.
            h.execute("INSERT INTO draft_actresses(javdb_slug, stage_name, created_at, updated_at) VALUES ('slug-ok','Yuma Asami','2024-01-01T00:00:00Z','2024-01-01T00:00:00Z')");
            h.execute("PRAGMA user_version = 50");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());

        String normalized = jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM draft_actresses WHERE javdb_slug = 'slug-fw'")
                        .mapTo(String.class).one());
        assertEquals("テスト1号", normalized,
                "full-width digit should be NFKC-normalized to half-width");

        String unchanged = jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM draft_actresses WHERE javdb_slug = 'slug-ok'")
                        .mapTo(String.class).one());
        assertEquals("Yuma Asami", unchanged, "already-normalized row should be unchanged");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
        assertEquals("テスト1号", jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM draft_actresses WHERE javdb_slug = 'slug-fw'")
                        .mapTo(String.class).one()));
    }

    @Test
    void freshInstallAtV51HasNormalizedDraftActressSchema() {
        new SchemaInitializer(jdbi).initialize();
        assertEquals(62, currentVersion(), "fresh install should stamp version 62");
    }

    // ── v56 migration tests ────────────────────────────────────────────────────

    @Test
    void upgradeFromV55NormalizesActressStageName() {
        new SchemaInitializer(jdbi).initialize();
        // Seed two actresses at v55: one with a whitespace-drift stage_name, one already clean.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(canonical_name, stage_name, tier, favorite, bookmark, rejected, first_seen_at)"
                    + " VALUES ('Shiina Sora','  椎名 そら  ','LIBRARY',0,0,0,'2026-01-01')");
            h.execute("INSERT INTO actresses(canonical_name, stage_name, tier, favorite, bookmark, rejected, first_seen_at)"
                    + " VALUES ('Yuma Asami','麻美ゆま','LIBRARY',0,0,0,'2026-01-01')");
            h.execute("PRAGMA user_version = 55");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());

        String normalized = jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM actresses WHERE canonical_name = 'Shiina Sora'")
                        .mapTo(String.class).one());
        assertEquals("椎名そら", normalized,
                "whitespace-drift stage_name must be normalized by v56 migration");

        String unchanged = jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM actresses WHERE canonical_name = 'Yuma Asami'")
                        .mapTo(String.class).one());
        assertEquals("麻美ゆま", unchanged, "already-normal stage_name must not be touched");

        // Idempotent: running upgrade twice leaves both rows correct.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
        assertEquals("椎名そら", jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM actresses WHERE canonical_name = 'Shiina Sora'")
                        .mapTo(String.class).one()));
    }

    // ── v58 migration tests ────────────────────────────────────────────────────

    @Test
    void upgradeFromV57AddsCategoryColumnAndBackfillsFromCuratedTags() {
        new SchemaInitializer(jdbi).initialize();
        // Drop the category column so we can rerun the V58 migration from a v57 baseline.
        // SQLite supports DROP COLUMN in 3.35+.
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE enrichment_tag_definitions DROP COLUMN category"); } catch (Exception ignore) {}
            // Seed curated tags.
            h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES('breast-large', 'body')");
            h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES('pov',          'production_style')");
            // Seed enrichment_tag_definitions: two mapped, one mapped-but-orphan (no matching tag), one unmapped.
            h.execute("INSERT INTO enrichment_tag_definitions(name, curated_alias) VALUES('Big Tits', 'breast-large')");
            h.execute("INSERT INTO enrichment_tag_definitions(name, curated_alias) VALUES('POV',      'pov')");
            h.execute("INSERT INTO enrichment_tag_definitions(name, curated_alias) VALUES('Mystery',  'no-such-tag')");
            h.execute("INSERT INTO enrichment_tag_definitions(name)                VALUES('Unmapped')");
            h.execute("PRAGMA user_version = 57");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("enrichment_tag_definitions", "category"),
                "v58 must add category column on enrichment_tag_definitions");

        String big = jdbi.withHandle(h -> h.createQuery(
                "SELECT category FROM enrichment_tag_definitions WHERE name = 'Big Tits'")
                .mapTo(String.class).one());
        assertEquals("body", big, "mapped row should be backfilled from curated tags.category");

        String pov = jdbi.withHandle(h -> h.createQuery(
                "SELECT category FROM enrichment_tag_definitions WHERE name = 'POV'")
                .mapTo(String.class).one());
        assertEquals("production_style", pov, "second mapped row should also be backfilled");

        String orphan = jdbi.withHandle(h -> h.createQuery(
                "SELECT category FROM enrichment_tag_definitions WHERE name = 'Mystery'")
                .mapTo(String.class).findOne().orElse(null));
        assertNull(orphan, "orphan curated_alias (no matching tag) should leave category NULL");

        String unmapped = jdbi.withHandle(h -> h.createQuery(
                "SELECT category FROM enrichment_tag_definitions WHERE name = 'Unmapped'")
                .mapTo(String.class).findOne().orElse(null));
        assertNull(unmapped, "unmapped (curated_alias IS NULL) row should keep category NULL");

        // Idempotent: re-running upgrade is a no-op.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
        assertEquals("body", jdbi.withHandle(h -> h.createQuery(
                "SELECT category FROM enrichment_tag_definitions WHERE name = 'Big Tits'")
                .mapTo(String.class).one()));
    }

    @Test
    void freshInstallHasCategoryColumnOnEnrichmentTagDefinitions() {
        new SchemaInitializer(jdbi).initialize();
        assertTrue(columnExists("enrichment_tag_definitions", "category"),
                "fresh install should include category column");
        assertEquals(62, currentVersion(), "fresh install should stamp version 62");
    }

    @Test
    void upgradeFromV58_reparsesTitlesWithSeqPrefix() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes(id, structure_type) VALUES ('v1', 'actress-tiered')");

            // Title 1: was synced with the buggy parser — full folder name in code/base_code, NULL label/seq.
            h.execute("""
                    INSERT INTO titles(id, code, base_code, label, seq_num)
                    VALUES (1, 'Yua Ariga (MKBD-S119)', 'Yua Ariga (MKBD-S119)', NULL, NULL)
                    """);
            h.execute("""
                    INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (1, 'v1', 'stars', '/stars/minor/Yua Ariga/Yua Ariga (MKBD-S119)', '2026-01-01')
                    """);

            // Title 2: NULL label but leaf doesn't parse — should be left alone.
            h.execute("""
                    INSERT INTO titles(id, code, base_code, label, seq_num)
                    VALUES (2, '_trash', '_trash', NULL, NULL)
                    """);
            h.execute("""
                    INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (2, 'v1', 'stars', '/junk/_trash', '2026-01-01')
                    """);

            // Roll back to v58 and re-run upgrade.
            h.execute("PRAGMA user_version = 58");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());

        jdbi.useHandle(h -> {
            String code = h.createQuery("SELECT code FROM titles WHERE id = 1")
                    .mapTo(String.class).one();
            String baseCode = h.createQuery("SELECT base_code FROM titles WHERE id = 1")
                    .mapTo(String.class).one();
            String label = h.createQuery("SELECT label FROM titles WHERE id = 1")
                    .mapTo(String.class).one();
            Integer seqNum = h.createQuery("SELECT seq_num FROM titles WHERE id = 1")
                    .mapTo(Integer.class).one();
            assertEquals("MKBD-S119", code);
            assertEquals("MKBD-S00119", baseCode);
            assertEquals("MKBD", label);
            assertEquals(119, seqNum);

            // Title 2 should be unchanged.
            String code2 = h.createQuery("SELECT code FROM titles WHERE id = 2")
                    .mapTo(String.class).one();
            String label2 = h.createQuery("SELECT label FROM titles WHERE id = 2")
                    .mapTo(String.class).findOne().orElse(null);
            assertEquals("_trash", code2);
            assertNull(label2);
        });
    }

    @Test
    void upgradeFromV59CreatesNotesTableAndIndex() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS notes");
            h.execute("DROP INDEX IF EXISTS idx_notes_entity_type");
            h.execute("PRAGMA user_version = 59");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        boolean tableExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='notes'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "notes table should exist after v60 migration");
        boolean indexExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_notes_entity_type'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(indexExists, "idx_notes_entity_type index should exist after v60 migration");
    }

    @Test
    void freshInstallIncludesNotesTable() {
        new SchemaInitializer(jdbi).initialize();
        boolean tableExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='notes'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "fresh install should include notes table");
        assertEquals(62, currentVersion(), "fresh install should stamp current version (62)");
    }

    // ---- v61: orphan-row cleanup ---------------------------------------------------

    @Test
    void applyV61_cleansAllSevenOrphanTables() {
        // Fresh install brings the DB to CURRENT_VERSION (61). To exercise the v61
        // migration in isolation we seed valid + orphan rows in each child table,
        // roll the version back to 60, and re-run upgrade().
        new SchemaInitializer(jdbi).initialize();
        seedV61Fixtures();
        jdbi.useHandle(h -> h.execute("PRAGMA user_version = 60"));

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        // Orphans gone, valid rows retained — assert by table.
        assertOrphanCount("actress_companies", "actress_id NOT IN (SELECT id FROM actresses)", 0);
        assertOrphanCount("title_tags", "title_id NOT IN (SELECT id FROM titles)", 0);
        assertOrphanCount("title_effective_tags", "title_id NOT IN (SELECT id FROM titles)", 0);
        assertOrphanCount("title_enrichment_tags",
                "title_id NOT IN (SELECT title_id FROM title_javdb_enrichment)", 0);
        assertOrphanCount("draft_title_actresses",
                "draft_title_id NOT IN (SELECT id FROM draft_titles)", 0);
        assertOrphanCount("draft_title_javdb_enrichment",
                "draft_title_id NOT IN (SELECT id FROM draft_titles)", 0);
        assertOrphanCount("revalidation_pending", "title_id NOT IN (SELECT id FROM titles)", 0);

        // Each of the 7 child tables still has its 1 valid row.
        assertEquals(1, countRows("actress_companies"));
        assertEquals(1, countRows("title_tags"));
        assertEquals(1, countRows("title_effective_tags"));
        assertEquals(1, countRows("title_enrichment_tags"));
        assertEquals(1, countRows("draft_title_actresses"));
        assertEquals(1, countRows("draft_title_javdb_enrichment"));
        assertEquals(1, countRows("revalidation_pending"));
    }

    @Test
    void applyV61_idempotentOnCleanDb() {
        // Fresh install is already at v61. Roll back to v60 with no orphan rows
        // and verify the migration runs without error, stamps v61, and removes nothing.
        new SchemaInitializer(jdbi).initialize();
        seedV61ValidRowsOnly();
        jdbi.useHandle(h -> h.execute("PRAGMA user_version = 60"));

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        // All valid rows still present.
        assertEquals(1, countRows("actress_companies"));
        assertEquals(1, countRows("title_tags"));
        assertEquals(1, countRows("title_effective_tags"));
        assertEquals(1, countRows("title_enrichment_tags"));
        assertEquals(1, countRows("draft_title_actresses"));
        assertEquals(1, countRows("draft_title_javdb_enrichment"));
        assertEquals(1, countRows("revalidation_pending"));

        // Second pass over an already-clean DB at v61 — pure no-op via the guard.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
        assertEquals(1, countRows("actress_companies"));
        assertEquals(1, countRows("title_tags"));
    }

    /**
     * Seeds the four parent tables (actresses, titles, title_javdb_enrichment,
     * draft_titles) each with one row, then inserts one valid + one orphan row
     * in each of the seven v61-target child tables. Orphan rows reference
     * non-existent parent ids (9999).
     */
    private void seedV61Fixtures() {
        jdbi.useHandle(h -> {
            // Parents.
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                    + "VALUES (1, 'Test Actress', 'A', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO titles(id, code) VALUES (1, 'TEST-001')");
            h.execute("INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at) "
                    + "VALUES (1, 'test-slug', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_titles(id, title_id, code, created_at, updated_at) "
                    + "VALUES (1, 1, 'TEST-001', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_actresses(javdb_slug, created_at, updated_at) "
                    + "VALUES ('drft-slug', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO enrichment_tag_definitions(id, name) VALUES (1, 'test-tag')");

            // Children: valid row first, orphan row second.
            h.execute("INSERT INTO actress_companies(actress_id, company) VALUES (1, 'co-a')");
            h.execute("INSERT INTO actress_companies(actress_id, company) VALUES (9999, 'orphan-co')");

            h.execute("INSERT INTO title_tags(title_id, tag) VALUES (1, 'tag-a')");
            h.execute("INSERT INTO title_tags(title_id, tag) VALUES (9999, 'orphan-tag')");

            h.execute("INSERT INTO title_effective_tags(title_id, tag, source) VALUES (1, 'tag-a', 'direct')");
            h.execute("INSERT INTO title_effective_tags(title_id, tag, source) VALUES (9999, 'orphan-tag', 'direct')");

            h.execute("INSERT INTO title_enrichment_tags(title_id, tag_id) VALUES (1, 1)");
            h.execute("INSERT INTO title_enrichment_tags(title_id, tag_id) VALUES (9999, 1)");

            h.execute("INSERT INTO draft_title_actresses(draft_title_id, javdb_slug, resolution) "
                    + "VALUES (1, 'drft-slug', 'pending')");
            h.execute("INSERT INTO draft_title_actresses(draft_title_id, javdb_slug, resolution) "
                    + "VALUES (9999, 'drft-slug', 'pending')");

            h.execute("INSERT INTO draft_title_javdb_enrichment(draft_title_id, updated_at) "
                    + "VALUES (1, '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_title_javdb_enrichment(draft_title_id, updated_at) "
                    + "VALUES (9999, '2026-01-01T00:00:00Z')");

            h.execute("INSERT INTO revalidation_pending(title_id, reason) VALUES (1, 'test')");
            h.execute("INSERT INTO revalidation_pending(title_id, reason) VALUES (9999, 'orphan')");
        });
    }

    /** Seeds parents and only valid (non-orphan) child rows. */
    private void seedV61ValidRowsOnly() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                    + "VALUES (1, 'Test Actress', 'A', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO titles(id, code) VALUES (1, 'TEST-001')");
            h.execute("INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at) "
                    + "VALUES (1, 'test-slug', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_titles(id, title_id, code, created_at, updated_at) "
                    + "VALUES (1, 1, 'TEST-001', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_actresses(javdb_slug, created_at, updated_at) "
                    + "VALUES ('drft-slug', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO enrichment_tag_definitions(id, name) VALUES (1, 'test-tag')");

            h.execute("INSERT INTO actress_companies(actress_id, company) VALUES (1, 'co-a')");
            h.execute("INSERT INTO title_tags(title_id, tag) VALUES (1, 'tag-a')");
            h.execute("INSERT INTO title_effective_tags(title_id, tag, source) VALUES (1, 'tag-a', 'direct')");
            h.execute("INSERT INTO title_enrichment_tags(title_id, tag_id) VALUES (1, 1)");
            h.execute("INSERT INTO draft_title_actresses(draft_title_id, javdb_slug, resolution) "
                    + "VALUES (1, 'drft-slug', 'pending')");
            h.execute("INSERT INTO draft_title_javdb_enrichment(draft_title_id, updated_at) "
                    + "VALUES (1, '2026-01-01T00:00:00Z')");
            h.execute("INSERT INTO revalidation_pending(title_id, reason) VALUES (1, 'test')");
        });
    }

    private void assertOrphanCount(String table, String whereClause, int expected) {
        int actual = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + table + " WHERE " + whereClause)
                        .mapTo(Integer.class).one());
        assertEquals(expected, actual,
                "expected " + expected + " orphan rows in " + table + " but found " + actual);
    }

    private int countRows(String table) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + table).mapTo(Integer.class).one());
    }

    private boolean columnExists(String table, String column) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'")
                .mapTo(Integer.class).one() > 0);
    }

    private int currentVersion() {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
    }

    // ---- v62: AI picker assist columns on enrichment_review_queue ------------------

    @Test
    void applyV62_addsAiSuggestionColumnsAndIsIdempotent() {
        // Fresh install is already at CURRENT_VERSION; roll back to v61 to exercise applyV62.
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            for (String col : new String[]{
                    "ai_suggestion_slug",
                    "ai_suggestion_confidence",
                    "ai_suggestion_reason",
                    "ai_suggestion_at",
                    "ai_auto_applied"}) {
                try { h.execute("ALTER TABLE enrichment_review_queue DROP COLUMN " + col); } catch (Exception ignore) {}
            }
            h.execute("PRAGMA user_version = 61");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(62, currentVersion());
        assertTrue(columnExists("enrichment_review_queue", "ai_suggestion_slug"));
        assertTrue(columnExists("enrichment_review_queue", "ai_suggestion_confidence"));
        assertTrue(columnExists("enrichment_review_queue", "ai_suggestion_reason"));
        assertTrue(columnExists("enrichment_review_queue", "ai_suggestion_at"));
        assertTrue(columnExists("enrichment_review_queue", "ai_auto_applied"));

        // Re-run: idempotent no-op via addColumnIfMissing + version guard.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(62, currentVersion());
        assertTrue(columnExists("enrichment_review_queue", "ai_suggestion_slug"));
        assertTrue(columnExists("enrichment_review_queue", "ai_auto_applied"));
    }
}
