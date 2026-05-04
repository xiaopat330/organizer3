package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.translation.repository.jdbi.JdbiTranslationCacheRepository;
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
import static org.mockito.Mockito.*;

/**
 * Service tests for {@link TranslationServiceImpl}.
 *
 * <p>Uses mocked {@link OllamaAdapter} (Mockito) + real in-memory SQLite repositories.
 * This allows testing service logic, strategy selection, normalization, and caching
 * without Ollama running.
 */
@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    private OllamaAdapter ollamaAdapter;

    private TranslationServiceImpl service;
    private JdbiTranslationStrategyRepository strategyRepo;
    private JdbiTranslationCacheRepository cacheRepo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        cacheRepo = new JdbiTranslationCacheRepository(jdbi);

        // Seed all three strategies
        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();

        service = new TranslationServiceImpl(
                ollamaAdapter, strategyRepo, cacheRepo,
                TranslationConfig.DEFAULTS,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // -------------------------------------------------------------------------
    // getCached
    // -------------------------------------------------------------------------

    @Test
    void getCached_missWhenNothingCached() {
        Optional<String> result = service.getCached("中出し");
        assertTrue(result.isEmpty());
        verifyNoInteractions(ollamaAdapter);
    }

    @Test
    void getCached_hitAfterSuccessfulTranslation() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Creampie"));

        service.requestTranslation(new TranslationRequest("中出し", null, null, null));

        Optional<String> cached = service.getCached("中出し");
        assertTrue(cached.isPresent());
        assertEquals("Creampie", cached.get());

        // Ollama called exactly once — second getCached is DB-only
        verify(ollamaAdapter, times(1)).generate(any());
    }

    @Test
    void getCached_neverCallsOllama() {
        service.getCached("some text");
        verifyNoInteractions(ollamaAdapter);
    }

    // -------------------------------------------------------------------------
    // requestTranslation — cache-miss → adapter call → cache write
    // -------------------------------------------------------------------------

    @Test
    void requestTranslation_cacheMissCallsAdapterAndWritesToCache() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Translation result"));

        long rowId = service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        assertTrue(rowId > 0);

        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(1, cacheRepo.countSuccessful());
    }

    @Test
    void requestTranslation_cacheHitSkipsAdapter() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("First result"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        verify(ollamaAdapter, times(1)).generate(any());

        // Second call — should hit cache
        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        verify(ollamaAdapter, times(1)).generate(any()); // still only 1
    }

    // -------------------------------------------------------------------------
    // Normalization — whitespace/NFKC variants hit the same cache row
    // -------------------------------------------------------------------------

    @Test
    void normalization_trailingSpaceSameAsTrimmed() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        // Second request with trailing space — should hit cache, not call Ollama again
        service.requestTranslation(new TranslationRequest("テスト ", null, null, null));

        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(1, cacheRepo.countTotal());
    }

    @Test
    void normalization_internalWhitespaceSameAsCollapsed() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("テスト  text", null, null, null));
        // Double space collapses to single space → same key
        service.requestTranslation(new TranslationRequest("テスト text", null, null, null));

        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(1, cacheRepo.countTotal());
    }

    @Test
    void normalization_nfkcHalfwidthKatakanaMatchesFull() {
        // ｱｲｳ (half-width) NFKC-normalises to アイウ (full-width)
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("AIU"));

        service.requestTranslation(new TranslationRequest("ｱｲｳ", null, null, null));
        // Full-width version should hit the same cache row
        service.requestTranslation(new TranslationRequest("アイウ", null, null, null));

        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(1, cacheRepo.countTotal());
    }

    // -------------------------------------------------------------------------
    // Strategy selection
    // -------------------------------------------------------------------------

    @Test
    void strategySelection_explicitJpToken_usesLabelExplicit() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("中出し花野真衣", null, null, null));

        // Capture the OllamaRequest sent to the adapter and verify strategy model/prompt
        verify(ollamaAdapter).generate(argThat(req ->
                req.modelId().equals("gemma4:e4b") &&
                req.prompt().contains("creampie") // few-shot examples in label_explicit
        ));
    }

    @Test
    void strategySelection_longInput_usesLabelExplicit() {
        String longJp = "あ".repeat(51); // 51 chars > 50 threshold
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest(longJp, null, null, null));

        // label_explicit strategy uses the hardened prompt
        verify(ollamaAdapter).generate(argThat(req ->
                req.modelId().equals("gemma4:e4b")
        ));

        // Verify it used label_explicit by checking cache row's strategy
        String hash = TranslationNormalization.hashOf(longJp);
        long explicitStratId = strategyRepo.findByName(StrategySelector.LABEL_EXPLICIT).orElseThrow().id();
        assertTrue(cacheRepo.findByHashAndStrategy(hash, explicitStratId).isPresent());
    }

    @Test
    void strategySelection_shortNonExplicit_usesLabelBasic() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));

        String hash = TranslationNormalization.hashOf("テスト");
        long basicStratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        assertTrue(cacheRepo.findByHashAndStrategy(hash, basicStratId).isPresent());
    }

    @Test
    void strategySelection_contextHintProse_usesProse() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Prose result"));

        service.requestTranslation(new TranslationRequest("短い", "prose", null, null));

        String hash = TranslationNormalization.hashOf("短い");
        long proseStratId = strategyRepo.findByName(StrategySelector.PROSE).orElseThrow().id();
        assertTrue(cacheRepo.findByHashAndStrategy(hash, proseStratId).isPresent());
    }

    @Test
    void strategySelection_contextHintWinsOverHeuristic() {
        // Would be label_explicit by heuristic (contains 中出し), but hint overrides
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("中出し", "label_basic", null, null));

        String hash = TranslationNormalization.hashOf("中出し");
        long basicStratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        assertTrue(cacheRepo.findByHashAndStrategy(hash, basicStratId).isPresent());
    }

    // -------------------------------------------------------------------------
    // Failure handling
    // -------------------------------------------------------------------------

    @Test
    void requestTranslation_ollamaErrorWritesFailureRow() {
        when(ollamaAdapter.generate(any())).thenThrow(new OllamaException("HTTP 500"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));

        assertEquals(0, cacheRepo.countSuccessful());
        assertEquals(1, cacheRepo.countFailed());
    }

    @Test
    void requestTranslation_transientErrorSetsRetryAfter() {
        when(ollamaAdapter.generate(any())).thenThrow(
                new OllamaException("Connection refused to Ollama"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));

        String hash = TranslationNormalization.hashOf("テスト");
        // Pick any strategy — transient failure is on the first attempt
        long stratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        TranslationCacheRow row = cacheRepo.findByHashAndStrategy(hash, stratId).orElseThrow();
        assertEquals("unreachable", row.failureReason());
        assertNotNull(row.retryAfter());
    }

    // -------------------------------------------------------------------------
    // stats()
    // -------------------------------------------------------------------------

    @Test
    void stats_initiallyZero() {
        TranslationServiceStats s = service.stats();
        assertEquals(0, s.cacheTotal());
        assertEquals(0, s.cacheSuccessful());
        assertEquals(0, s.cacheFailed());
    }

    @Test
    void stats_reflectsSuccessAndFailure() {
        when(ollamaAdapter.generate(any()))
                .thenReturn(fakeResponse("OK"))
                .thenThrow(new OllamaException("HTTP 500"));

        service.requestTranslation(new TranslationRequest("テスト1", null, null, null));
        service.requestTranslation(new TranslationRequest("テスト2", null, null, null));

        TranslationServiceStats s = service.stats();
        assertEquals(2, s.cacheTotal());
        assertEquals(1, s.cacheSuccessful());
        assertEquals(1, s.cacheFailed());
    }

    // -------------------------------------------------------------------------
    // resolveStageName — Phase 5 stub
    // -------------------------------------------------------------------------

    @Test
    void resolveStageName_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.resolveStageName("浜崎真緒"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OllamaResponse fakeResponse(String text) {
        return new OllamaResponse(text, 5_000_000_000L, 10, 20, 4_000_000_000L);
    }
}
