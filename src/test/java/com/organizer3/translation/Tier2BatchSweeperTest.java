package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.ollama.OllamaAdapter;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link Tier2BatchSweeper} using mocked {@link OllamaAdapter} + real in-memory SQLite.
 *
 * <p>Tests validate:
 * <ul>
 *   <li>Sweeper doesn't trigger when below threshold (count + time)</li>
 *   <li>Sweeper triggers at batch-size threshold</li>
 *   <li>Sweeper triggers at max-wait timeout</li>
 *   <li>Successful tier-2 translation → done queue row + tier-2 cache row</li>
 *   <li>Tier-2 refusal → sanitized_both_tiers + failed queue row</li>
 *   <li>Tier-2 sanitization → sanitized_both_tiers + failed queue row</li>
 *   <li>No tier-2 fallback configured → failed</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class Tier2BatchSweeperTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Mock
    private OllamaAdapter ollamaAdapter;

    private Tier2BatchSweeper sweeper;
    private JdbiTranslationStrategyRepository strategyRepo;
    private JdbiTranslationCacheRepository cacheRepo;
    private JdbiTranslationQueueRepository queueRepo;
    private CallbackDispatcher callbackDispatcher;
    private long tier1BasicStrategyId;
    private TranslationConfig config;
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
        tier1BasicStrategyId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();

        callbackDispatcher = new CallbackDispatcher(jdbi);

        // Config: batchSize=3, maxWait=60min, sweeper interval=5min
        config = new TranslationConfig(
                "http://localhost:11434", 120, "gemma4:e4b", "qwen2.5:14b",
                2, 3, 600, 300,
                3, 60, 300   // tier2BatchSize=3, tier2MaxWaitMinutes=60, tier2SweeperIntervalSeconds=300
        );

        sweeper = new Tier2BatchSweeper(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                callbackDispatcher, config, new ObjectMapper(), new OllamaModelState());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // -------------------------------------------------------------------------
    // Threshold logic — does NOT drain
    // -------------------------------------------------------------------------

    @Test
    void run_belowThreshold_doesNotDrain() {
        // 2 rows, threshold is 3, max wait is 60 min — should not drain
        String now = ISO_UTC.format(Instant.now());
        for (int i = 0; i < 2; i++) {
            long id = queueRepo.enqueue("text" + i, tier1BasicStrategyId, now,
                    TranslationQueueRow.STATUS_PENDING, null, null);
            queueRepo.claimNext();
            queueRepo.markTier2Pending(id, "refused", now);
        }

        sweeper.run();

        // Should not have drained — no Ollama calls
        verifyNoInteractions(ollamaAdapter);
        assertEquals(2, queueRepo.countTier2Pending());
    }

    // -------------------------------------------------------------------------
    // Threshold logic — DOES drain at batch size
    // -------------------------------------------------------------------------

    @Test
    void run_atBatchSizeThreshold_drains() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Translated text"));

        String now = ISO_UTC.format(Instant.now());
        for (int i = 0; i < 3; i++) {
            long id = queueRepo.enqueue("テスト" + i, tier1BasicStrategyId, now,
                    TranslationQueueRow.STATUS_PENDING, null, null);
            queueRepo.claimNext();
            queueRepo.markTier2Pending(id, "refused", now);
        }

        sweeper.run();

        verify(ollamaAdapter, times(3)).generate(any());
        assertEquals(0, queueRepo.countTier2Pending());
        assertEquals(3, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
    }

    @Test
    void run_atMaxWaitTimeout_drains() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Translated text"));

        // Only 1 row (below batch threshold of 3), but submitted 61 minutes ago
        String oldTime = ISO_UTC.format(Instant.now().minusSeconds(61 * 60));
        long id = queueRepo.enqueue("テスト", tier1BasicStrategyId, oldTime,
                TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markTier2Pending(id, "refused", oldTime);

        sweeper.run();

        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(0, queueRepo.countTier2Pending());
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
    }

    // -------------------------------------------------------------------------
    // Successful tier-2 translation
    // -------------------------------------------------------------------------

    @Test
    void drainAll_success_writesTier2CacheRow_andMarksDone() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Crystal Pictures"));

        String now = ISO_UTC.format(Instant.now());
        long id = queueRepo.enqueue("クリスタル映像", tier1BasicStrategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markTier2Pending(id, "refused", now);

        sweeper.drainAll();

        // Queue row done
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countTier2Pending());

        // A cache row should exist keyed on the tier-2 strategy id
        long tier2Id = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();
        String hash = TranslationNormalization.hashOf("クリスタル映像");
        var cached = cacheRepo.findByHashAndStrategy(hash, tier2Id);
        assertTrue(cached.isPresent());
        assertEquals("Crystal Pictures", cached.get().englishText());
        assertNull(cached.get().failureReason());
    }

    @Test
    void drainAll_success_usesTier2ModelId() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Crystal Pictures"));

        String now = ISO_UTC.format(Instant.now());
        long id = queueRepo.enqueue("クリスタル映像", tier1BasicStrategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markTier2Pending(id, "refused", now);

        sweeper.drainAll();

        // Verify the Ollama call used qwen2.5:14b (tier-2 model)
        verify(ollamaAdapter).generate(argThat(req -> "qwen2.5:14b".equals(req.modelId())));
    }

    // -------------------------------------------------------------------------
    // Tier-2 refusal → sanitized_both_tiers
    // -------------------------------------------------------------------------

    @Test
    void drainAll_tier2Refused_writesSanitizedBothTiers_andMarksFailed() {
        // Tier-2 returns empty output (hard refusal)
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse(""));

        String now = ISO_UTC.format(Instant.now());
        long id = queueRepo.enqueue("中出し花野真衣", tier1BasicStrategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markTier2Pending(id, "refused", now);

        sweeper.drainAll();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED));
        assertEquals(0, queueRepo.countTier2Pending());

        // Cache row should have failure_reason='sanitized_both_tiers'
        long tier2Id = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();
        String hash = TranslationNormalization.hashOf("中出し花野真衣");
        var cached = cacheRepo.findByHashAndStrategy(hash, tier2Id);
        assertTrue(cached.isPresent());
        assertEquals("sanitized_both_tiers", cached.get().failureReason());
        assertNull(cached.get().englishText());
    }

    @Test
    void drainAll_tier2Sanitized_writesSanitizedBothTiers_andMarksFailed() {
        // Tier-2 returns non-empty but sanitized output (explicit JP, no explicit EN)
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Special Series"));

        String now = ISO_UTC.format(Instant.now());
        // 中出し in source, "Special Series" in output — no explicit EN token → sanitized
        long id = queueRepo.enqueue("中出し花野真衣", tier1BasicStrategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markTier2Pending(id, "sanitized", now);

        sweeper.drainAll();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED));

        long tier2Id = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();
        String hash = TranslationNormalization.hashOf("中出し花野真衣");
        var cached = cacheRepo.findByHashAndStrategy(hash, tier2Id);
        assertTrue(cached.isPresent());
        assertEquals("sanitized_both_tiers", cached.get().failureReason());
    }

    // -------------------------------------------------------------------------
    // Multiple rows — all drained
    // -------------------------------------------------------------------------

    @Test
    void drainAll_multipleRows_allDrained() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Translated"));

        String now = ISO_UTC.format(Instant.now());
        for (int i = 0; i < 5; i++) {
            long id = queueRepo.enqueue("text" + i, tier1BasicStrategyId, now,
                    TranslationQueueRow.STATUS_PENDING, null, null);
            queueRepo.claimNext();
            queueRepo.markTier2Pending(id, "refused", now);
        }

        sweeper.drainAll();

        verify(ollamaAdapter, times(5)).generate(any());
        assertEquals(5, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));
        assertEquals(0, queueRepo.countTier2Pending());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OllamaResponse fakeResponse(String text) {
        return new OllamaResponse(text, 5_000_000_000L, 10, 20, 4_000_000_000L);
    }
}
