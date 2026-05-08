package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class BackfillActressSlugsFromCastToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private BackfillActressSlugsFromCastTool tool;
    private EnrichmentReviewQueueRepository reviewQueueRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        tool = new BackfillActressSlugsFromCastTool(jdbi, reviewQueueRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void writesSlugWhenStageNameMatchesCastEntry() {
        long iori = seedActress("Iori Kogawa", "古川いおり", false);
        long titleId = seedEnrichedTitle("MIDV-100",
                "[{\"slug\":\"Y8Mx\",\"name\":\"古川いおり\",\"gender\":\"F\"}]");
        linkActress(titleId, iori);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(1, r.candidates());
        assertEquals(1, r.written());
        assertEquals(0, r.conflicts());
        assertEquals("Y8Mx", lookupSlug(iori));
    }

    @Test
    void whitespaceStrippedComparisonHandlesStageNameSpaces() {
        // stage_name has a space, javdb cast doesn't.
        long sora = seedActress("Sora Shiina", "椎名 そら", false);
        long titleId = seedEnrichedTitle("ABC-100",
                "[{\"slug\":\"sX\",\"name\":\"椎名そら\",\"gender\":\"F\"}]");
        linkActress(titleId, sora);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(1, r.written());
        assertEquals("sX", lookupSlug(sora));
    }

    @Test
    void doesNotOverwriteExistingSlug() {
        long mana = seedActress("Mana Sakura", "紗倉まな", false);
        // Pre-existing staging row with a slug.
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'preset', 'PRESET-001', 'slug_only')
                """).bind(0, mana).execute());

        long titleId = seedEnrichedTitle("MIDV-100",
                "[{\"slug\":\"newSlug\",\"name\":\"紗倉まな\",\"gender\":\"F\"}]");
        linkActress(titleId, mana);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        // Candidate query already filters on null slug, so she won't appear at all.
        assertEquals(0, r.candidates());
        assertEquals("preset", lookupSlug(mana), "existing slug must be preserved");
    }

    @Test
    void sentinelActressesAreExcluded() {
        long various = seedActress("Various", "Various", true);
        long titleId = seedEnrichedTitle("VAR-001",
                "[{\"slug\":\"sentSlug\",\"name\":\"Various\",\"gender\":\"F\"}]");
        linkActress(titleId, various);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(0, r.candidates(), "sentinels never get backfilled");
        assertNull(lookupSlug(various));
    }

    @Test
    void dryRunReportsCandidatesWithoutWriting() {
        long iori = seedActress("Iori Kogawa", "古川いおり", false);
        long titleId = seedEnrichedTitle("MIDV-100",
                "[{\"slug\":\"Y8Mx\",\"name\":\"古川いおり\"}]");
        linkActress(titleId, iori);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(true));

        assertEquals(1, r.candidates());
        assertEquals(0, r.written(), "dryRun must not write");
        assertNull(lookupSlug(iori), "dryRun must not write");
    }

    @Test
    void noMatchWhenStageNameDoesNotAppearInAnyCast() {
        long someone = seedActress("Someone Else", "他人", false);
        long titleId = seedEnrichedTitle("ABC-001",
                "[{\"slug\":\"x\",\"name\":\"全然違う名前\"}]");
        linkActress(titleId, someone);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));
        assertEquals(0, r.candidates());
    }

    @Test
    void multipleTitlesYieldOneCandidatePerActress() {
        long iori = seedActress("Iori Kogawa", "古川いおり", false);
        // Three different titles, all with her in cast.
        for (int i = 0; i < 3; i++) {
            long t = seedEnrichedTitle("MIDV-10" + i,
                    "[{\"slug\":\"Y8Mx\",\"name\":\"古川いおり\"}]");
            linkActress(t, iori);
        }

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));
        assertEquals(1, r.candidates(), "DISTINCT must collapse to one candidate per actress");
        assertEquals(1, r.written());
        assertEquals("Y8Mx", lookupSlug(iori));
    }

    // ── Slug conflict tests ──────────────────────────────────────────────────

    @Test
    void cleanCaseNoConflict_insertsSlugAndNoReviewRow() {
        long actress = seedActress("Rima Arai", "新井リマ", false);
        long titleId = seedEnrichedTitle("BLK-566",
                "[{\"slug\":\"E2vOx\",\"name\":\"新井リマ\"}]");
        linkActress(titleId, actress);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(1, r.written());
        assertEquals(0, r.conflicts());
        assertTrue(r.conflictRows().isEmpty());
        assertEquals("E2vOx", lookupSlug(actress));
        assertEquals(0, reviewQueueRepo.countOpen("slug_conflict"),
                "no review row should exist when there is no conflict");
    }

    @Test
    void conflictWithSlugBoundToDifferentActress_writesReviewRowAndDoesNotInsert() {
        // Actress 999 already owns slug "X"
        long incumbent = seedActress("Himari Kinoshita", "木下ひまり", false);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'X', 'OLD-001', 'slug_only')
                """).bind(0, incumbent).execute());

        // Actress 1000 (claimant) resolves to the same slug X via cast_json
        long claimant = seedActress("Rima Arai", "新井リマ", false);
        long titleId = seedEnrichedTitle("BLK-566",
                "[{\"slug\":\"X\",\"name\":\"新井リマ\"}]");
        linkActress(titleId, claimant);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        // claimant's staging row must NOT have been inserted
        assertNull(lookupSlug(claimant), "conflicting slug must not be written for claimant");
        // incumbent's row must be untouched
        assertEquals("X", lookupSlug(incumbent), "incumbent slug must be preserved");

        assertEquals(1, r.candidates());
        assertEquals(0, r.written());
        assertEquals(1, r.conflicts());
        assertEquals(1, r.conflictRows().size());
        assertEquals(claimant, r.conflictRows().get(0).actressId());
        assertEquals("X", r.conflictRows().get(0).slug());
        assertEquals(incumbent, r.conflictRows().get(0).incumbentActressId());

        // Exactly one slug_conflict review row
        assertEquals(1, reviewQueueRepo.countOpen("slug_conflict"));

        // Detail JSON contains both actress IDs
        var rows = reviewQueueRepo.listOpen("slug_conflict", 10, 0);
        assertEquals(1, rows.size());
        var detailStr = rows.get(0).detail();
        assertNotNull(detailStr, "review row must have detail JSON");
        assertTrue(detailStr.contains("claimant_actress_id"), "detail must contain claimant_actress_id");
        assertTrue(detailStr.contains("incumbent_actress_id"), "detail must contain incumbent_actress_id");
        assertTrue(detailStr.contains("\"slug\":\"X\""), "detail must contain slug");
        assertTrue(detailStr.contains("BLK-566"), "detail must contain source_title_code");
    }

    @Test
    void multipleConflictsInOneBatch_bothSurfaceNeitherAbortsOther() {
        // Two incumbents each owning a slug
        long incumbent1 = seedActress("Incumbent One", "先住１", false);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'SLUG_A', 'INC1-001', 'slug_only')
                """).bind(0, incumbent1).execute());

        long incumbent2 = seedActress("Incumbent Two", "先住２", false);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'SLUG_B', 'INC2-001', 'slug_only')
                """).bind(0, incumbent2).execute());

        // Two claimants resolving to the two taken slugs
        long claimant1 = seedActress("Claimant One", "主張者１", false);
        long t1 = seedEnrichedTitle("TITLE-101",
                "[{\"slug\":\"SLUG_A\",\"name\":\"主張者１\"}]");
        linkActress(t1, claimant1);

        long claimant2 = seedActress("Claimant Two", "主張者２", false);
        long t2 = seedEnrichedTitle("TITLE-102",
                "[{\"slug\":\"SLUG_B\",\"name\":\"主張者２\"}]");
        linkActress(t2, claimant2);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(2, r.candidates());
        assertEquals(0, r.written());
        assertEquals(2, r.conflicts(), "both conflicts must surface");
        assertEquals(2, r.conflictRows().size());
        assertNull(lookupSlug(claimant1));
        assertNull(lookupSlug(claimant2));
        assertEquals(2, reviewQueueRepo.countOpen("slug_conflict"),
                "two review rows expected, one per conflict");
    }

    @Test
    void idempotency_rerunningBackfillDoesNotDuplicateReviewRow() {
        long incumbent = seedActress("Himari Kinoshita", "木下ひまり", false);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'DUP_SLUG', 'OLD-001', 'slug_only')
                """).bind(0, incumbent).execute());

        long claimant = seedActress("Rima Arai", "新井リマ", false);
        long titleId = seedEnrichedTitle("BLK-566",
                "[{\"slug\":\"DUP_SLUG\",\"name\":\"新井リマ\"}]");
        linkActress(titleId, claimant);

        // Run twice
        tool.call(args(false));
        tool.call(args(false));

        assertEquals(1, reviewQueueRepo.countOpen("slug_conflict"),
                "re-running backfill must not produce duplicate review rows");
    }

    @Test
    void mixedBatch_cleanInsertsProceedConflictSurfaces() {
        // Incumbent owns slug "TAKEN"
        long incumbent = seedActress("Incumbent Actress", "先住女優", false);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'TAKEN', 'INC-001', 'slug_only')
                """).bind(0, incumbent).execute());

        // Claimant (conflicts)
        long claimant = seedActress("Rima Arai", "新井リマ", false);
        long t1 = seedEnrichedTitle("BLK-566",
                "[{\"slug\":\"TAKEN\",\"name\":\"新井リマ\"}]");
        linkActress(t1, claimant);

        // Two clean candidates
        long actress2 = seedActress("Actress Two", "女優２", false);
        long t2 = seedEnrichedTitle("CLEAN-200",
                "[{\"slug\":\"CLEAN_A\",\"name\":\"女優２\"}]");
        linkActress(t2, actress2);

        long actress3 = seedActress("Actress Three", "女優３", false);
        long t3 = seedEnrichedTitle("CLEAN-300",
                "[{\"slug\":\"CLEAN_B\",\"name\":\"女優３\"}]");
        linkActress(t3, actress3);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(3, r.candidates());
        assertEquals(2, r.written(), "two clean candidates must be inserted");
        assertEquals(1, r.conflicts(), "one conflict must surface");
        assertNull(lookupSlug(claimant), "conflicting candidate must not be inserted");
        assertEquals("CLEAN_A", lookupSlug(actress2));
        assertEquals("CLEAN_B", lookupSlug(actress3));
        assertEquals(1, reviewQueueRepo.countOpen("slug_conflict"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seedActress(String canonical, String stageName, boolean sentinel) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel)
                VALUES (?, ?, 'LIBRARY', '2024-01-01', ?)
                """)
                .bind(0, canonical).bind(1, stageName).bind(2, sentinel ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long seedEnrichedTitle(String code, String castJson) {
        long titleId = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO titles (code, base_code, label, seq_num) VALUES (?, ?, ?, 1)
                """)
                .bind(0, code).bind(1, code).bind(2, code.split("-")[0])
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json)
                VALUES (?, 'titleSlug', '2026-04-29T00:00:00Z', ?)
                """).bind(0, titleId).bind(1, castJson).execute());
        return titleId;
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                .bind(0, titleId).bind(1, actressId).execute());
    }

    private String lookupSlug(long actressId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT javdb_slug FROM javdb_actress_staging WHERE actress_id = ?")
                .bind(0, actressId).mapTo(String.class).findOne().orElse(null));
    }

    private static ObjectNode args(boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("dryRun", dryRun);
        return n;
    }
}
