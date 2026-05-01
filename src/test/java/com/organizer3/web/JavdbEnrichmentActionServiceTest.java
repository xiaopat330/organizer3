package com.organizer3.web;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.enrichment.EnrichmentJob;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import com.organizer3.javdb.enrichment.JavdbActressStagingRow;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavdbEnrichmentActionServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentQueue queue;
    private EnrichmentRunner mockRunner;
    private TitleRepository mockTitleRepo;
    private JavdbEnrichmentActionService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        queue         = new EnrichmentQueue(jdbi, JavdbConfig.DEFAULTS);
        mockRunner    = Mockito.mock(EnrichmentRunner.class);
        mockTitleRepo = Mockito.mock(TitleRepository.class);
        service       = new JavdbEnrichmentActionService(mockTitleRepo, queue, mockRunner, null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c, :c, 'TST', 1)")
                        .bind("c", code)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void insertQueueRow(long titleId, long actressId, String status) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO javdb_enrichment_queue " +
                          "(job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at) " +
                          "VALUES ('fetch_title', ?, ?, ?, 0, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')",
                        titleId, actressId, status));
    }

    private Title makeTitle(long id, String code) {
        return Title.builder().id(id).code(code).baseCode(code).label("TST").seqNum(1).build();
    }

    // ── enqueueActress ─────────────────────────────────────────────────────

    @Test
    void enqueueActress_enqueuesAllTitles() {
        long t1 = insertTitle("AAA-001");
        long t2 = insertTitle("AAA-002");
        long actressId = 1L;
        Mockito.when(mockTitleRepo.findByActress(actressId))
               .thenReturn(List.of(makeTitle(t1, "AAA-001"), makeTitle(t2, "AAA-002")));

        int count = service.enqueueActress(actressId);

        assertEquals(2, count);
        assertEquals(2, queue.countPendingForActress(actressId));
    }

    @Test
    void enqueueActress_skipsDoneTitle() {
        long t1 = insertTitle("BBB-001");
        long t2 = insertTitle("BBB-002");
        long actressId = 2L;
        insertQueueRow(t1, actressId, "done");
        Mockito.when(mockTitleRepo.findByActress(actressId))
               .thenReturn(List.of(makeTitle(t1, "BBB-001"), makeTitle(t2, "BBB-002")));

        service.enqueueActress(actressId);

        // t1 is done — enqueueTitle skips it; t2 is new pending
        assertEquals(1, queue.countPendingForActress(actressId));
    }

    // ── cancelForActress ───────────────────────────────────────────────────

    @Test
    void cancelForActress_cancelsPendingOnly_leavesInFlightAlone() {
        long t1 = insertTitle("CCC-001");
        long t2 = insertTitle("CCC-002");
        long actressId = 3L;
        insertQueueRow(t1, actressId, "pending");
        insertQueueRow(t2, actressId, "in_flight");

        service.cancelForActress(actressId);

        // pending → cancelled, in_flight unchanged
        assertEquals(1, queue.countPendingForActress(actressId)); // still 1 in_flight counted
        List<EnrichmentJob> failed = queue.listFailedForActress(actressId);
        assertTrue(failed.isEmpty()); // no failed rows
        // verify in_flight row still exists and pending count = 1 (the in_flight job)
        int pending = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE actress_id=? AND status='pending'")
                .bind(0, actressId).mapTo(Integer.class).one());
        assertEquals(0, pending);
        int inFlight = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE actress_id=? AND status='in_flight'")
                .bind(0, actressId).mapTo(Integer.class).one());
        assertEquals(1, inFlight);
    }

    // ── cancelAll ─────────────────────────────────────────────────────────

    @Test
    void cancelAll_cancelsPendingJobsAcrossActresses() {
        long t1 = insertTitle("DDD-001");
        long t2 = insertTitle("DDD-002");
        insertQueueRow(t1, 10L, "pending");
        insertQueueRow(t2, 11L, "pending");

        service.cancelAll();

        assertEquals(0, queue.countPendingForActress(10L));
        assertEquals(0, queue.countPendingForActress(11L));
    }

    // ── setPaused / isPaused / forceResume ────────────────────────────────

    @Test
    void setPaused_delegatesToRunner() {
        service.setPaused(true);
        Mockito.verify(mockRunner).setPaused(true);
    }

    @Test
    void isPaused_delegatesToRunner() {
        Mockito.when(mockRunner.isPaused()).thenReturn(true);
        assertTrue(service.isPaused());
    }

    @Test
    void forceResume_delegatesToRunner() {
        service.forceResume();
        Mockito.verify(mockRunner).forceResume();
    }

    // ── retryFailedForActress ──────────────────────────────────────────────

    @Test
    void retryFailedForActress_resetsFailedToPending() {
        long t1 = insertTitle("EEE-001");
        long actressId = 5L;
        insertQueueRow(t1, actressId, "failed");

        service.retryFailedForActress(actressId);

        int pending = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE actress_id=? AND status='pending'")
                .bind(0, actressId).mapTo(Integer.class).one());
        assertEquals(1, pending);
        assertTrue(queue.listFailedForActress(actressId).isEmpty());
    }

    // ── getErrorsForActress ────────────────────────────────────────────────

    @Test
    void getErrorsForActress_returnsFailedJobs() {
        long t1 = insertTitle("FFF-001");
        long t2 = insertTitle("FFF-002");
        long actressId = 6L;
        insertQueueRow(t1, actressId, "failed");
        insertQueueRow(t2, actressId, "pending");

        List<EnrichmentQueue.FailedJobSummary> errors = service.getErrorsForActress(actressId);

        assertEquals(1, errors.size());
        assertEquals("FFF-001", errors.get(0).titleCode());
    }

    @Test
    void getErrorsForActress_doesNotReturnOtherActressErrors() {
        long t1 = insertTitle("GGG-001");
        long t2 = insertTitle("GGG-002");
        insertQueueRow(t1, 7L, "failed");
        insertQueueRow(t2, 8L, "failed");

        List<EnrichmentQueue.FailedJobSummary> errors = service.getErrorsForActress(7L);

        assertEquals(1, errors.size());
        assertEquals("GGG-001", errors.get(0).titleCode());
    }

    // ── user-initiated calls are HIGH priority ─────────────────────────────

    private String queuedPriority(String jobType, long targetId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT priority FROM javdb_enrichment_queue WHERE job_type=? AND target_id=? ORDER BY id DESC LIMIT 1")
                .bind(0, jobType).bind(1, targetId).mapTo(String.class).findOne().orElse(null));
    }

    @Test
    void enqueueActress_usesHighPriority() {
        long t1 = insertTitle("HHH-001");
        long actressId = 20L;
        Mockito.when(mockTitleRepo.findByActress(actressId)).thenReturn(List.of(makeTitle(t1, "HHH-001")));

        service.enqueueActress(actressId);

        assertEquals("HIGH", queuedPriority("fetch_title", t1));
    }

    @Test
    void reEnqueueTitle_usesHighPriority() {
        long t1 = insertTitle("III-001");
        long actressId = 21L;

        service.reEnqueueTitle(t1, actressId);

        assertEquals("HIGH", queuedPriority("fetch_title", t1));
    }

    @Test
    void reEnqueueActressProfile_usesHighPriority() {
        long actressId = 22L;
        insertActressRow(actressId);

        service.reEnqueueActressProfile(actressId);

        assertEquals("HIGH", queuedPriority("fetch_actress_profile", actressId));
    }

    @Test
    void deriveSlugAndEnqueue_usesHighPriority_whenSlugAlreadyKnown() {
        long actressId = 23L;
        insertActressRow(actressId);
        insertActressStagingRow(actressId, "test-slug");

        JavdbStagingRepository mockStaging = Mockito.mock(JavdbStagingRepository.class);
        Mockito.when(mockStaging.findActressStaging(actressId))
               .thenReturn(java.util.Optional.of(new JavdbActressStagingRow(
                       actressId, "test-slug", null, "fetched", null, null, null, null, null, null, null, null)));
        JavdbEnrichmentActionService svc2 = new JavdbEnrichmentActionService(
                mockTitleRepo, queue, mockRunner, mockStaging, null, null);

        svc2.deriveSlugAndEnqueueProfile(actressId);

        assertEquals("HIGH", queuedPriority("fetch_actress_profile", actressId));
    }

    private void insertActressRow(long id) {
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO actresses (id, canonical_name, tier, favorite, bookmark, rejected, first_seen_at) " +
                "VALUES (?, ?, 'C', 0, 0, 0, '2024-01-01')", id, "TestActress" + id));
    }

    private void insertActressStagingRow(long actressId, String slug) {
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO javdb_actress_staging (actress_id, javdb_slug) VALUES (?, ?)",
                actressId, slug));
    }
}
