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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * End-to-end test: queue row goes through tier-1 (refused) → tier_2_pending →
 * tier-2 batch drain (success or failure) → done/failed.
 *
 * <p>Uses mocked {@link OllamaAdapter} and real in-memory SQLite.
 */
@ExtendWith(MockitoExtension.class)
class TranslationTier2EndToEndTest {

    @Mock
    private OllamaAdapter ollamaAdapter;

    private TranslationWorker worker;
    private Tier2BatchSweeper sweeper;
    private TranslationServiceImpl service;
    private JdbiTranslationStrategyRepository strategyRepo;
    private JdbiTranslationCacheRepository cacheRepo;
    private JdbiTranslationQueueRepository queueRepo;
    private CallbackDispatcher callbackDispatcher;
    private OllamaModelState modelState;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        cacheRepo = new JdbiTranslationCacheRepository(jdbi);
        queueRepo = new JdbiTranslationQueueRepository(jdbi);

        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();

        callbackDispatcher = new CallbackDispatcher(jdbi);
        modelState = new OllamaModelState();

        TranslationConfig config = TranslationConfig.DEFAULTS;

        worker = new TranslationWorker(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                callbackDispatcher, config, new ObjectMapper(), modelState);

        sweeper = new Tier2BatchSweeper(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                callbackDispatcher, config, new ObjectMapper(), modelState);

        service = new TranslationServiceImpl(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                config, callbackDispatcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    /**
     * Full happy path: tier-1 refuses → tier_2_pending → tier-2 succeeds → done.
     * Verifies the second Ollama call uses qwen2.5:14b.
     */
    @Test
    void fullFlow_tier1Refused_tier2Succeeds_endStateDone() {
        // tier-1 returns empty (refusal), tier-2 succeeds
        when(ollamaAdapter.generate(any()))
                .thenReturn(fakeResponse(""))                        // tier-1: refused
                .thenReturn(fakeResponse("Creampie Series with Mai")); // tier-2: success

        service.requestTranslation(new TranslationRequest("中出し花野真衣", null, null, null));

        // Step 1: worker processes → tier-1 refuses → tier_2_pending
        worker.processOne();
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));

        // Step 2: sweeper drains → tier-2 succeeds → done
        sweeper.drainAll();
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));

        // Second Ollama call must be to qwen2.5:14b (tier-2 model)
        verify(ollamaAdapter, times(2)).generate(any());
        verify(ollamaAdapter).generate(argThat(req -> "qwen2.5:14b".equals(req.modelId())));

        // Cache: tier-1 row with failure_reason='refused', tier-2 row with english_text
        String hash = TranslationNormalization.hashOf("中出し花野真衣");
        long tier1Id = strategyRepo.findByName(StrategySelector.LABEL_EXPLICIT).orElseThrow().id();
        long tier2Id = strategyRepo.findByName(TranslationStrategySeeder.LABEL_EXPLICIT_QWEN).orElseThrow().id();

        var tier1Cache = cacheRepo.findByHashAndStrategy(hash, tier1Id);
        assertTrue(tier1Cache.isPresent());
        assertEquals("refused", tier1Cache.get().failureReason());

        var tier2Cache = cacheRepo.findByHashAndStrategy(hash, tier2Id);
        assertTrue(tier2Cache.isPresent());
        assertEquals("Creampie Series with Mai", tier2Cache.get().englishText());

        // getCached should now return the tier-2 translation (tier-1 row has no english_text)
        Optional<String> cached = service.getCached("中出し花野真衣");
        assertTrue(cached.isPresent());
        assertEquals("Creampie Series with Mai", cached.get());
    }

    /**
     * Full failure path: tier-1 refuses → tier_2_pending → tier-2 also refuses →
     * sanitized_both_tiers cache row + failed queue row.
     */
    @Test
    void fullFlow_tier1Refused_tier2AlsoRefused_endStateFailed() {
        // Both tiers refuse
        when(ollamaAdapter.generate(any()))
                .thenReturn(fakeResponse(""))   // tier-1: refused
                .thenReturn(fakeResponse(""));  // tier-2: also refused

        service.requestTranslation(new TranslationRequest("中出し花野真衣", null, null, null));

        worker.processOne();
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));

        sweeper.drainAll();
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED));

        // getCached should return empty (permanent failure)
        Optional<String> cached = service.getCached("中出し花野真衣");
        assertTrue(cached.isEmpty(), "Permanent failure should return empty from getCached");
    }

    /**
     * Sanitized at tier-1 → tier_2_pending → tier-2 succeeds with explicit EN → done.
     */
    @Test
    void fullFlow_tier1Sanitized_tier2Succeeds() {
        // tier-1 returns sanitized (explicit JP, no explicit EN)
        // tier-2 returns proper explicit translation
        when(ollamaAdapter.generate(any()))
                .thenReturn(fakeResponse("Special Series Feature"))     // tier-1: sanitized
                .thenReturn(fakeResponse("Creampie Feature Series"));   // tier-2: success

        service.requestTranslation(new TranslationRequest("中出し花野真衣", null, null, null));

        worker.processOne();
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_TIER_2_PENDING));

        sweeper.drainAll();
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE));

        Optional<String> cached = service.getCached("中出し花野真衣");
        assertTrue(cached.isPresent());
        assertEquals("Creampie Feature Series", cached.get());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OllamaResponse fakeResponse(String text) {
        return new OllamaResponse(text, 5_000_000_000L, 10, 20, 4_000_000_000L);
    }
}
