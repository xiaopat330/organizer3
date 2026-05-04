package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.translation.repository.jdbi.JdbiTranslationCacheRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationQueueRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranslationWorker}.
 *
 * <p>Uses mocked {@link OllamaAdapter} + real in-memory SQLite.
 * Tests call {@link TranslationWorker#processOne()} directly — no background threads.
 */
@ExtendWith(MockitoExtension.class)
class TranslationWorkerTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Mock
    private OllamaAdapter ollamaAdapter;

    private TranslationWorker worker;
    private JdbiTranslationStrategyRepository strategyRepo;
    private JdbiTranslationCacheRepository cacheRepo;
    private JdbiTranslationQueueRepository queueRepo;
    private CallbackDispatcher callbackDispatcher;
    private long strategyId;
    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        cacheRepo = new JdbiTranslationCacheRepository(jdbi);
        queueRepo = new JdbiTranslationQueueRepository(jdbi);

        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();
        strategyId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();

        callbackDispatcher = new CallbackDispatcher(jdbi);

        worker = new TranslationWorker(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                callbackDispatcher, TranslationConfig.DEFAULTS, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // -------------------------------------------------------------------------
    // processOne — no work
    // -------------------------------------------------------------------------

    @Test
    void processOne_returnsFalseWhenNoPendingRows() {
        boolean result = worker.processOne();
        assertFalse(result);
        verifyNoInteractions(ollamaAdapter);
    }

    // -------------------------------------------------------------------------
    // Success path
    // -------------------------------------------------------------------------

    @Test
    void processOne_successPath_writesCacheAndMarksDone() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Crystal Pictures"));

        String now = ISO_UTC.format(Instant.now());
        long queueId = queueRepo.enqueue("クリスタル映像", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);

        boolean processed = worker.processOne();
        assertTrue(processed);

        // Cache should have the translation
        assertEquals(1, cacheRepo.countSuccessful());

        // Queue row should be done
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT));
    }

    @Test
    void processOne_successPath_dispatchesCallback() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Crystal Pictures"));

        // Create a title_javdb_enrichment row to receive the callback
        jdbi.useHandle(h -> {
            // Need a title first
            h.execute("INSERT INTO titles (code, label) VALUES ('ABC-001','ABC')");
            long titleId = h.createQuery("SELECT id FROM titles WHERE code='ABC-001'")
                    .mapTo(Long.class).one();
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) " +
                    "VALUES (?, 'ABC-001', '2026-01-01T00:00:00.000Z')", titleId);
        });

        long titleId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM titles WHERE code='ABC-001'")
                        .mapTo(Long.class).one());

        String now = ISO_UTC.format(Instant.now());
        long queueId = queueRepo.enqueue("クリスタル映像", strategyId, now,
                TranslationQueueRow.STATUS_PENDING,
                "title_javdb_enrichment.maker_en", titleId);

        worker.processOne();

        // Verify the callback wrote the EN translation
        String makerEn = jdbi.withHandle(h ->
                h.createQuery("SELECT maker_en FROM title_javdb_enrichment WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(String.class)
                        .findFirst()
                        .orElse(null));
        assertEquals("Crystal Pictures", makerEn);
    }

    // -------------------------------------------------------------------------
    // Failure path — attempts and re-queue
    // -------------------------------------------------------------------------

    @Test
    void processOne_failurePath_incrementsAttemptAndRequeues() {
        when(ollamaAdapter.generate(any())).thenThrow(new OllamaException("HTTP 500"));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("テスト", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);

        // maxAttempts=3; attempt 1 should re-queue
        worker.processOne();

        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED));
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void processOne_failurePath_marksFailedAfterMaxAttempts() {
        when(ollamaAdapter.generate(any())).thenThrow(new OllamaException("HTTP 500"));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("テスト", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);

        // maxAttempts=3; process 3 times → permanently failed
        worker.processOne(); // attempt 0 → pending (count becomes 1)
        worker.processOne(); // attempt 1 → pending (count becomes 2)
        worker.processOne(); // attempt 2 → failed (count becomes 3 >= maxAttempts=3)

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void processOne_cachePreflightHit_skipsOllamaAndDispatchesCallback() {
        // First: manually write to cache (simulates another worker or direct cache write)
        String hash = TranslationNormalization.hashOf("テスト");
        long stratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        TranslationCacheRow cached = new TranslationCacheRow(
                0, hash, "テスト", stratId, "Test Result",
                null, null, null, null, 100, 10, 20, 4_000_000_000L,
                ISO_UTC.format(Instant.now()));
        cacheRepo.insert(cached);

        // Now enqueue the same text
        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("テスト", stratId, now, TranslationQueueRow.STATUS_PENDING, null, null);

        // Worker processes — should skip Ollama due to pre-flight cache hit
        boolean processed = worker.processOne();
        assertTrue(processed);
        verifyNoInteractions(ollamaAdapter);

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
    }

    @Test
    void processOne_callbackTargetDeleted_continuesWithoutError() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Test"));

        String now = ISO_UTC.format(Instant.now());
        // Enqueue with a callback_id that doesn't exist (row deleted)
        queueRepo.enqueue("テスト", strategyId, now, TranslationQueueRow.STATUS_PENDING,
                "title_javdb_enrichment.maker_en", 99999L);

        // Should not throw — just log and continue
        assertDoesNotThrow(() -> worker.processOne());
        // Cache is still written
        assertEquals(1, cacheRepo.countSuccessful());
        // Queue row is done
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
    }

    // -------------------------------------------------------------------------
    // Tier-2 escalation — refusal and sanitization (Phase 3)
    // -------------------------------------------------------------------------

    @Test
    void processOne_emptyResponse_marksTier2Pending() {
        // Empty response is a hard refusal
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse(""));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("中出し花野真衣", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);

        worker.processOne();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));

        // Cache row written with failure_reason='refused'
        assertEquals(1, cacheRepo.countFailed());
    }

    @Test
    void processOne_refusalKeyword_marksTier2Pending() {
        // Short output with refusal keyword
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Sorry, I cannot translate this content."));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("中出し花野真衣", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);

        worker.processOne();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
    }

    @Test
    void processOne_sanitizedOutput_marksTier2Pending() {
        // Explicit JP input (中出し), but output has no explicit EN token → sanitized
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Special Series Feature"));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("中出し花野真衣", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);

        worker.processOne();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));

        // Cache row written with failure_reason='sanitized'
        assertEquals(1, cacheRepo.countFailed());
    }

    @Test
    void processOne_cleanExplicitOutput_marksDone() {
        // Explicit JP input (中出し), output HAS explicit EN token → clean, not escalated
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Creampie Special with Hanano Mai"));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("中出し花野真衣", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);

        worker.processOne();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(1, cacheRepo.countSuccessful());
    }

    @Test
    void processOne_shortNonRefusalOutput_marksDone() {
        // Short output (≤80 chars) that does NOT match refusal pattern — valid short translation
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Crystal Pictures"));

        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("クリスタル映像", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);

        worker.processOne();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
    }

    // -------------------------------------------------------------------------
    // Worker loop lifecycle
    // -------------------------------------------------------------------------

    @Test
    void workerLoop_stopsOnInterrupt() throws Exception {
        // Start worker loop in a thread, interrupt it, verify it stops cleanly
        Thread workerThread = new Thread(worker, "test-translation-worker");
        workerThread.setDaemon(true);
        workerThread.start();

        // Give the worker time to start polling
        Thread.sleep(50);
        workerThread.interrupt();
        workerThread.join(2000);

        assertFalse(workerThread.isAlive(), "Worker thread should stop after interrupt");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OllamaResponse fakeResponse(String text) {
        return new OllamaResponse(text, 5_000_000_000L, 10, 20, 4_000_000_000L);
    }
}
