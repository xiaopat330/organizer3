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

        assertEquals(40, currentVersion());
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
        assertEquals(40, currentVersion(), "fresh install should stamp current version (40)");
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

        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());
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
        assertEquals(40, currentVersion());

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

        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());

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
        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());

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
        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());
        assertTrue(columnExists("javdb_actress_filmography_entry", "stale"),
                "stale column must exist after v34 migration");
        assertTrue(columnExists("javdb_actress_filmography", "last_drift_count"),
                "last_drift_count column must exist after v34 migration");
        assertTrue(columnExists("javdb_actress_filmography", "last_fetch_status"),
                "last_fetch_status column must exist after v34 migration");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());
        assertTrue(columnExists("title_javdb_enrichment", "last_revalidated_at"),
                "last_revalidated_at column must exist after v38 migration");

        boolean tableExists = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='revalidation_pending'")
                .mapTo(Integer.class).one() > 0);
        assertTrue(tableExists, "revalidation_pending table must exist after v38 migration");

        // Idempotent.
        new SchemaUpgrader(jdbi).upgrade();
        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());
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
        assertEquals(40, currentVersion());
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

        assertEquals(40, currentVersion());
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
        assertEquals(40, currentVersion());
    }

    @Test
    void freshInstallHasPriorityColumnOnEnrichmentQueue() {
        new SchemaInitializer(jdbi).initialize();
        assertTrue(columnExists("javdb_enrichment_queue", "priority"),
                "fresh install must include the priority column");
        assertEquals(40, currentVersion());
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
}
