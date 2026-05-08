package com.organizer3.utilities.task.javdb;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.AutoPromoter;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link AutoPromoteRule3SweepTask} using real in-memory SQLite.
 */
class AutoPromoteRule3SweepTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private AutoPromoter autoPromoter;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private AutoPromoteRule3SweepTask task;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        autoPromoter = new AutoPromoter(jdbi, reviewQueueRepo);
        task = new AutoPromoteRule3SweepTask(jdbi, autoPromoter, reviewQueueRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertActress(String canonicalName, String stageName) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at)
                        VALUES (:cn, :sn, 'LIBRARY', '2024-01-01')
                        """)
                        .bind("cn", canonicalName)
                        .bind("sn", stageName)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO titles (code, base_code, label, seq_num)
                        VALUES (:c, :c, 'TST', 1)
                        """)
                        .bind("c", code)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void linkActressTitle(long actressId, long titleId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)",
                actressId, titleId));
    }

    /**
     * Insert a fetched staging row with name_variants_json (the ones the sweep targets).
     */
    private void insertFetchedStagingWithVariants(long actressId, String nameVariantsJson) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status, name_variants_json) " +
                "VALUES (?, 'SLUG-' || ?, 'fetched', ?)",
                actressId, actressId, nameVariantsJson));
    }

    /**
     * Insert a staging row whose status is NOT 'fetched' — should be ignored by the sweep.
     */
    private void insertSlugOnlyStaging(long actressId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status) " +
                "VALUES (?, 'SLUG-' || ?, 'slug_only')",
                actressId, actressId));
    }

    private String getStageName(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT stage_name FROM actresses WHERE id = ?")
                        .bind(0, actressId).mapTo(String.class).findOne().orElse(null));
    }

    private int countOpenConflicts(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM enrichment_review_queue
                        WHERE title_id = :t AND reason = 'stage_name_conflict' AND resolved_at IS NULL
                        """)
                        .bind("t", titleId).mapTo(Integer.class).one());
    }

    private int countAllOpenConflicts() {
        return reviewQueueRepo.countOpen("stage_name_conflict");
    }

    private static CapturingIO capture() { return new CapturingIO(); }

    private void runTask() throws Exception {
        task.run(new TaskInputs(Map.of()), capture());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * 1. Empty case: no staging rows → scanned=0, no errors, no enqueues.
     */
    @Test
    void emptyCase_noStagingRows_isNoop() throws Exception {
        CapturingIO io = capture();
        task.run(new TaskInputs(Map.of()), io);

        assertEquals(0, countAllOpenConflicts());
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("0")));
    }

    /**
     * 2. Pure Rule 3 conflict: staging row exists with name_variants_json=["黒木麻衣"],
     *    actress has stage_name="花野真衣" (CJK, not in variants).
     *    → exactly one stage_name_conflict review row enqueued.
     */
    @Test
    void rule3Conflict_cjkStageNameNotInVariants_enqueuedOnce() throws Exception {
        long a = insertActress("Hanano Mai", "花野真衣");
        long t = insertTitle("TST-R3-001");
        linkActressTitle(a, t);
        insertFetchedStagingWithVariants(a, "[\"黒木麻衣\"]");

        runTask();

        assertEquals(1, countOpenConflicts(t),
                "should enqueue exactly one stage_name_conflict row");
        // Stage name must NOT be overwritten by Rule 3
        assertEquals("花野真衣", getStageName(a));
    }

    /**
     * 3. Rule 1/2 promotion: actress has stage_name=NULL; staging row has variants;
     *    sweep fills stage_name from variants[0].
     */
    @Test
    void rule1_nullStageName_filledFromVariants() throws Exception {
        long a = insertActress("Sora Aoi", null);
        long t = insertTitle("TST-R1-001");
        linkActressTitle(a, t);
        insertFetchedStagingWithVariants(a, "[\"蒼井そら\",\"Sora Aoi\"]");

        runTask();

        assertEquals("蒼井そら", getStageName(a));
        assertEquals(0, countOpenConflicts(t));
    }

    /**
     * 4. Already-resolved (no conflict, no NULL): staging variants contains the existing
     *    stage_name → sweep is a no-op for that actress.
     */
    @Test
    void rule3HappyPath_stageNameMatchesVariant_isNoop() throws Exception {
        long a = insertActress("Aoi Sora", "蒼井そら");
        long t = insertTitle("TST-NOP-001");
        linkActressTitle(a, t);
        insertFetchedStagingWithVariants(a, "[\"蒼井そら\",\"Sora Aoi\"]");

        runTask();

        assertEquals("蒼井そら", getStageName(a), "stage_name should remain unchanged");
        assertEquals(0, countOpenConflicts(t), "no conflict when name matches a variant");
    }

    /**
     * 5. Idempotency: run sweep twice; second pass adds zero new review rows.
     */
    @Test
    void idempotency_secondRunAddsNoNewConflicts() throws Exception {
        long a = insertActress("Conflict Actor", "天海麗");
        long t = insertTitle("TST-IDEM-001");
        linkActressTitle(a, t);
        insertFetchedStagingWithVariants(a, "[\"天海れい\",\"Rei Amami\"]");

        runTask();
        int afterFirst = countAllOpenConflicts();

        runTask();
        int afterSecond = countAllOpenConflicts();

        assertEquals(afterFirst, afterSecond,
                "second run must not add duplicate conflict rows");
        assertEquals(1, afterFirst, "exactly one conflict from the first run");
    }

    /**
     * Staging rows with status != 'fetched' should be ignored by the sweep.
     */
    @Test
    void stagingRowsWithNonFetchedStatus_areIgnored() throws Exception {
        long a = insertActress("Ignored Actress", "テスト");
        insertSlugOnlyStaging(a);  // status = 'slug_only', no name_variants_json

        runTask();

        // Stage name unchanged, no queue rows
        assertEquals("テスト", getStageName(a));
        assertEquals(0, countAllOpenConflicts());
    }

    /**
     * Multiple actresses with mixed outcomes are all processed correctly.
     */
    @Test
    void multipleActresses_mixedOutcomes() throws Exception {
        // Actress A: Rule 3 conflict
        long a1 = insertActress("Actress One", "花野真衣");
        long t1 = insertTitle("TST-MULTI-001");
        linkActressTitle(a1, t1);
        insertFetchedStagingWithVariants(a1, "[\"黒木麻衣\"]");

        // Actress B: Rule 1 fill
        long a2 = insertActress("Actress Two", null);
        long t2 = insertTitle("TST-MULTI-002");
        linkActressTitle(a2, t2);
        insertFetchedStagingWithVariants(a2, "[\"蒼井そら\"]");

        // Actress C: no-op (matches variant)
        long a3 = insertActress("Actress Three", "古川いおり");
        long t3 = insertTitle("TST-MULTI-003");
        linkActressTitle(a3, t3);
        insertFetchedStagingWithVariants(a3, "[\"古川いおり\",\"Iori Kogawa\"]");

        runTask();

        // A1: conflict enqueued, stage_name not touched
        assertEquals(1, countOpenConflicts(t1));
        assertEquals("花野真衣", getStageName(a1));

        // A2: stage_name filled
        assertEquals("蒼井そら", getStageName(a2));
        assertEquals(0, countOpenConflicts(t2));

        // A3: no-op
        assertEquals("古川いおり", getStageName(a3));
        assertEquals(0, countOpenConflicts(t3));
    }

    // ── CapturingIO ───────────────────────────────────────────────────────────

    static class CapturingIO implements TaskIO {
        final List<String> logs         = new ArrayList<>();
        final List<String> endSummaries = new ArrayList<>();
        final List<String> endStatuses  = new ArrayList<>();

        @Override public void phaseStart(String id, String label) {}
        @Override public void phaseProgress(String id, int current, int total, String detail) {}
        @Override public void phaseLog(String id, String line) { logs.add(line); }
        @Override public void phaseEnd(String id, String status, String summary) {
            endSummaries.add(summary);
            endStatuses.add(status);
        }
        @Override public boolean isCancellationRequested() { return false; }
    }
}
