package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationCacheRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationQueueRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TitleTranslationSweeper} using a real in-memory SQLite for
 * the enrichment repository and a mocked {@link TranslationService}. Phase 6a.
 */
class TitleTranslationSweeperTest {

    private Connection connection;
    private Jdbi jdbi;
    private JavdbEnrichmentRepository enrichmentRepo;
    private TranslationService service;
    private TranslationQueueRepository queueRepo;
    private TranslationCacheRepository cacheRepo;
    private TranslationStrategyRepository strategyRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.createStatement().execute("PRAGMA foreign_keys = ON");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        ObjectMapper json = new ObjectMapper();
        TitleEffectiveTagsService effectiveTags = new TitleEffectiveTagsService(jdbi);
        EnrichmentHistoryRepository historyRepo = new EnrichmentHistoryRepository(jdbi, json);
        enrichmentRepo = new JavdbEnrichmentRepository(jdbi, json, effectiveTags, historyRepo);
        queueRepo = new JdbiTranslationQueueRepository(jdbi);
        cacheRepo = new JdbiTranslationCacheRepository(jdbi);
        strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();
        service = mock(TranslationService.class);
        when(service.requestTranslation(any(TranslationRequest.class))).thenReturn(1L);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    /** Insert a title row + its enrichment row. */
    private long seedTitle(String code, String titleOriginal, String titleOriginalEn) {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code) VALUES (:code)")
                        .bind("code", code)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at,
                            title_original, title_original_en)
                        VALUES (:id, :slug, :now, :orig, :origEn)""")
                .bind("id", titleId)
                .bind("slug", "slug-" + code)
                .bind("now", "2026-05-04T00:00:00.000Z")
                .bind("orig", titleOriginal)
                .bind("origEn", titleOriginalEn)
                .execute());
        return titleId;
    }

    /** Insert a translation_queue row referring to (title_original_en, titleId). */
    private void seedQueueRow(long titleId, String status) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO translation_queue (source_text, strategy_id, submitted_at,
                            status, callback_kind, callback_id)
                        VALUES (:src, 1, :now, :status, :kind, :id)""")
                .bind("src", "anything")
                .bind("now", "2026-05-04T00:00:00.000Z")
                .bind("status", status)
                .bind("kind", JavdbEnrichmentRepository.TITLE_ORIGINAL_EN_CALLBACK_KIND)
                .bind("id", titleId)
                .execute());
    }

    private TitleTranslationSweeper sweeper(boolean enabled, int batchSize) {
        return new TitleTranslationSweeper(enrichmentRepo, service,
                queueRepo, cacheRepo, strategyRepo, enabled, batchSize);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void run_submitsEligibleTitleForTranslation() {
        long titleId = seedTitle("ABC-001", "中出しソープランドに堕ちた女子大生", null);

        sweeper(true, 50).run();

        ArgumentCaptor<TranslationRequest> captor = ArgumentCaptor.forClass(TranslationRequest.class);
        verify(service, times(1)).requestTranslation(captor.capture());
        TranslationRequest req = captor.getValue();
        assertEquals("中出しソープランドに堕ちた女子大生", req.sourceText());
        assertEquals(StrategySelector.LABEL_BASIC, req.contextHint(),
                "Sweeper must pin label_basic — Phase 6 spot-check decision");
        assertEquals(JavdbEnrichmentRepository.TITLE_ORIGINAL_EN_CALLBACK_KIND, req.callbackKind());
        assertEquals(titleId, req.callbackId());
    }

    // -------------------------------------------------------------------------
    // Skip cases — defend the SQL predicate
    // -------------------------------------------------------------------------

    @Test
    void run_skipsTitleWithExistingEnglishTranslation() {
        seedTitle("ABC-002", "ある日本語タイトル", "Already translated");

        sweeper(true, 50).run();

        verifyNoInteractions(service);
    }

    @Test
    void run_skipsTitleWithEmptyEnglishTranslation() {
        seedTitle("ABC-002b", "ある日本語タイトル", "");
        // Empty-string title_original_en should also be eligible — predicate must NOT skip.
        sweeper(true, 50).run();
        verify(service, times(1)).requestTranslation(any());
    }

    @Test
    void run_skipsTitleWithNullOriginal() {
        seedTitle("ABC-003", null, null);

        sweeper(true, 50).run();

        verifyNoInteractions(service);
    }

    @Test
    void run_skipsTitleWithEmptyOriginal() {
        seedTitle("ABC-004", "", null);

        sweeper(true, 50).run();

        verifyNoInteractions(service);
    }

    @Test
    void run_skipsTitleAlreadyEnqueued_pendingStatus() {
        long titleId = seedTitle("ABC-005", "日本語タイトル", null);
        seedQueueRow(titleId, "pending");

        sweeper(true, 50).run();

        verifyNoInteractions(service);
    }

    @Test
    void run_skipsTitleAlreadyEnqueued_doneStatus() {
        // Even done queue rows block re-submission (dedup contract: at most one per lifetime).
        long titleId = seedTitle("ABC-006", "日本語タイトル", null);
        seedQueueRow(titleId, "done");

        sweeper(true, 50).run();

        verifyNoInteractions(service);
    }

    // -------------------------------------------------------------------------
    // Dedup contract — running twice does not double-submit
    // -------------------------------------------------------------------------

    @Test
    void run_twice_doesNotDuplicateEnqueue_whenServiceWritesQueueRow() {
        // First call submits. We then simulate the service writing its queue row
        // (which is what requestTranslation does in production) and run again.
        long titleId = seedTitle("ABC-007", "日本語タイトル", null);

        sweeper(true, 50).run();
        verify(service, times(1)).requestTranslation(any());

        // Mimic what the real TranslationService.requestTranslation would have done.
        seedQueueRow(titleId, "pending");

        sweeper(true, 50).run();
        // Still 1 invocation total — sweeper saw the queue row and skipped.
        verify(service, times(1)).requestTranslation(any());
    }

    // -------------------------------------------------------------------------
    // Batch limit
    // -------------------------------------------------------------------------

    @Test
    void run_respectsBatchSize() {
        seedTitle("ABC-100", "タイトル100", null);
        seedTitle("ABC-101", "タイトル101", null);
        seedTitle("ABC-102", "タイトル102", null);

        sweeper(true, 2).run();

        verify(service, times(2)).requestTranslation(any());
    }

    // -------------------------------------------------------------------------
    // Disabled flag — no DB query, no service calls
    // -------------------------------------------------------------------------

    @Test
    void run_whenDisabled_doesNothing() {
        seedTitle("ABC-200", "日本語タイトル", null);

        sweeper(false, 50).run();

        verifyNoInteractions(service);
    }

    // -------------------------------------------------------------------------
    // Service exception is contained — sweeper continues with remaining rows
    // -------------------------------------------------------------------------

    @Test
    void run_continuesAfterServiceException() {
        seedTitle("ABC-300", "タイトル300", null);
        seedTitle("ABC-301", "タイトル301", null);

        when(service.requestTranslation(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(2L);

        sweeper(true, 50).run();

        // Both attempted despite first throwing.
        verify(service, times(2)).requestTranslation(any());
    }

    // -------------------------------------------------------------------------
    // Repository count helper — Tools UI stat
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // forceTranslateOne
    // -------------------------------------------------------------------------

    @Test
    void forceTranslateOne_clearsCacheAndQueueThenSubmits() {
        long titleId = seedTitle("FT-001", "強制翻訳テスト", null);
        // Existing queue rows for this title (e.g., a stuck `done` row + a `pending` row)
        seedQueueRow(titleId, "done");
        seedQueueRow(titleId, "pending");
        // Existing cache row that would otherwise short-circuit the new request
        long stratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        String hash = TranslationNormalization.sha256Hex(
                TranslationNormalization.normalize("強制翻訳テスト"));
        cacheRepo.insert(new TranslationCacheRow(
                0, hash, "強制翻訳テスト", stratId,
                "stale english", null, null, null, null,
                100, null, null, null, "2026-05-04T00:00:00.000Z"));

        TitleTranslationSweeper.ForceTranslateResult r = sweeper(true, 50).forceTranslateOne(titleId);

        assertTrue(r.submitted());
        assertEquals(1, r.cacheRowsDeleted());
        assertEquals(2, r.queueRowsDeleted());
        verify(service, times(1)).requestTranslation(any());
        // After deletion, no queue row remains for this title via the test fixture path;
        // the new row went through the mocked service so it isn't actually written.
        assertTrue(cacheRepo.findByHashAndStrategy(hash, stratId).isEmpty(),
                "Stale cache row must be cleared so the new request can hit Ollama");
    }

    @Test
    void forceTranslateOne_returnsNoTitleOriginalReason_whenEnrichmentMissing() {
        // Insert just the title row, no enrichment
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code) VALUES ('FT-002')")
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());

        TitleTranslationSweeper.ForceTranslateResult r = sweeper(true, 50).forceTranslateOne(titleId);

        assertFalse(r.submitted());
        assertEquals("no_title_original", r.reason());
        verify(service, org.mockito.Mockito.never()).requestTranslation(any());
    }

    @Test
    void forceTranslateOne_returnsNoTitleOriginalReason_whenTitleOriginalIsBlank() {
        long titleId = seedTitle("FT-003", "", null);
        TitleTranslationSweeper.ForceTranslateResult r = sweeper(true, 50).forceTranslateOne(titleId);
        assertFalse(r.submitted());
        assertEquals("no_title_original", r.reason());
    }

    @Test
    void countTitlesAwaitingTranslation_countsEligibleRows() {
        seedTitle("ABC-400", "日本語1", null);          // eligible
        seedTitle("ABC-401", "日本語2", null);          // eligible
        seedTitle("ABC-402", "日本語3", "english");    // not eligible (already translated)
        seedTitle("ABC-403", null, null);             // not eligible (no original)
        long enqueuedId = seedTitle("ABC-404", "日本語5", null);
        seedQueueRow(enqueuedId, "pending");          // not eligible (already enqueued)

        assertEquals(2L, enrichmentRepo.countTitlesAwaitingTranslation());
    }

    // -------------------------------------------------------------------------
    // Auto-pause: TitleTranslationSweeper skips tick when sync task is running
    // -------------------------------------------------------------------------

    @Test
    void run_skipsTickWhenVolumeSyncIsActive() {
        seedTitle("PAUSE-001", "日本語タイトル", null);

        TaskRunner taskRunner = mock(TaskRunner.class);
        TaskRun syncRun = mock(TaskRun.class);
        when(syncRun.taskId()).thenReturn("volume.sync");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(syncRun));

        TitleTranslationSweeper s = sweeper(true, 50);
        s.setTaskRunner(taskRunner);
        s.run();

        verifyNoInteractions(service);
    }

    @Test
    void run_skipsTickWhenCoherentSyncIsActive() {
        seedTitle("PAUSE-002", "日本語タイトル", null);

        TaskRunner taskRunner = mock(TaskRunner.class);
        TaskRun syncRun = mock(TaskRun.class);
        when(syncRun.taskId()).thenReturn("volume.sync_coherent");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(syncRun));

        TitleTranslationSweeper s = sweeper(true, 50);
        s.setTaskRunner(taskRunner);
        s.run();

        verifyNoInteractions(service);
    }

    @Test
    void run_skipsTickWhenCleanStaleLocationsIsActive() {
        seedTitle("PAUSE-003", "日本語タイトル", null);

        TaskRunner taskRunner = mock(TaskRunner.class);
        TaskRun syncRun = mock(TaskRun.class);
        when(syncRun.taskId()).thenReturn("volume.clean_stale_locations");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(syncRun));

        TitleTranslationSweeper s = sweeper(true, 50);
        s.setTaskRunner(taskRunner);
        s.run();

        verifyNoInteractions(service);
    }

    @Test
    void run_proceedsWhenUnrelatedTaskIsActive() {
        seedTitle("PAUSE-004", "日本語タイトル", null);

        TaskRunner taskRunner = mock(TaskRunner.class);
        TaskRun unrelated = mock(TaskRun.class);
        when(unrelated.taskId()).thenReturn("utility.organize_something");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(unrelated));

        TitleTranslationSweeper s = sweeper(true, 50);
        s.setTaskRunner(taskRunner);
        s.run();

        verify(service, times(1)).requestTranslation(any());
    }

    @Test
    void run_proceedsWhenNoTaskRunnerInjected() {
        seedTitle("PAUSE-005", "日本語タイトル", null);

        // No setTaskRunner called — null-safe, should not pause.
        sweeper(true, 50).run();

        verify(service, times(1)).requestTranslation(any());
    }
}
