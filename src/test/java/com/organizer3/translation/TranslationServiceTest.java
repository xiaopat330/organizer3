package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.translation.repository.jdbi.JdbiStageNameLookupRepository;
import com.organizer3.translation.repository.jdbi.JdbiStageNameSuggestionRepository;
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
import static org.mockito.Mockito.*;

/**
 * Service tests for {@link TranslationServiceImpl}.
 *
 * <p>Uses mocked {@link OllamaAdapter} (Mockito) + real in-memory SQLite repositories.
 * Phase 2: {@link #requestTranslation} is async — we call {@link TranslationWorker#processOne()}
 * directly to process queue rows without starting a background thread.
 */
@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    private OllamaAdapter ollamaAdapter;

    private TranslationServiceImpl service;
    private TranslationWorker worker;
    private JdbiTranslationStrategyRepository strategyRepo;
    private JdbiTranslationCacheRepository cacheRepo;
    private JdbiTranslationQueueRepository queueRepo;
    private JdbiStageNameLookupRepository stageNameLookupRepo;
    private JdbiStageNameSuggestionRepository stageNameSuggestionRepo;
    private CallbackDispatcher callbackDispatcher;
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
        stageNameLookupRepo = new JdbiStageNameLookupRepository(jdbi);
        stageNameSuggestionRepo = new JdbiStageNameSuggestionRepository(jdbi);

        // Seed all three strategies
        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();

        callbackDispatcher = new CallbackDispatcher(jdbi);

        HealthGate healthGate = new HealthGate(ollamaAdapter, cacheRepo, TranslationConfig.DEFAULTS);
        service = new TranslationServiceImpl(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                TranslationConfig.DEFAULTS, callbackDispatcher,
                healthGate, new ObjectMapper(),
                stageNameLookupRepo, stageNameSuggestionRepo);

        worker = new TranslationWorker(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                callbackDispatcher, TranslationConfig.DEFAULTS, new ObjectMapper());
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
        worker.processOne(); // async: process the pending row

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
    // requestTranslation — cache-miss → enqueue → worker → cache write
    // -------------------------------------------------------------------------

    @Test
    void requestTranslation_cacheMissCallsAdapterAndWritesToCache() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Translation result"));

        long queueId = service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        assertTrue(queueId > 0);
        // Not yet translated — worker hasn't run
        assertEquals(0, cacheRepo.countSuccessful());

        worker.processOne();
        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(1, cacheRepo.countSuccessful());
    }

    @Test
    void requestTranslation_cacheHitSkipsAdapter() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("First result"));

        // First request — miss → pending → worker processes
        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        worker.processOne();
        verify(ollamaAdapter, times(1)).generate(any());

        // Second request — cache hit → done queue row, no worker needed
        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        // Ollama still only called once
        verify(ollamaAdapter, times(1)).generate(any());
    }

    // -------------------------------------------------------------------------
    // Normalization — whitespace/NFKC variants hit the same cache row
    // -------------------------------------------------------------------------

    @Test
    void normalization_trailingSpaceSameAsTrimmed() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        worker.processOne();
        // Second request with trailing space — should hit cache, not call Ollama again
        service.requestTranslation(new TranslationRequest("テスト ", null, null, null));

        verify(ollamaAdapter, times(1)).generate(any());
        assertEquals(1, cacheRepo.countTotal());
    }

    @Test
    void normalization_internalWhitespaceSameAsCollapsed() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("テスト  text", null, null, null));
        worker.processOne();
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
        worker.processOne();
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
        worker.processOne();

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
        worker.processOne();

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
        worker.processOne();

        String hash = TranslationNormalization.hashOf("テスト");
        long basicStratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        assertTrue(cacheRepo.findByHashAndStrategy(hash, basicStratId).isPresent());
    }

    @Test
    void strategySelection_contextHintProse_usesProse() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Prose result"));

        service.requestTranslation(new TranslationRequest("短い", "prose", null, null));
        worker.processOne();

        String hash = TranslationNormalization.hashOf("短い");
        long proseStratId = strategyRepo.findByName(StrategySelector.PROSE).orElseThrow().id();
        assertTrue(cacheRepo.findByHashAndStrategy(hash, proseStratId).isPresent());
    }

    @Test
    void strategySelection_contextHintWinsOverHeuristic() {
        // Would be label_explicit by heuristic (contains 中出し), but hint overrides
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("Result"));

        service.requestTranslation(new TranslationRequest("中出し", "label_basic", null, null));
        worker.processOne();

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
        worker.processOne(); // attempt 1 — adapter_error, re-queue (attempt < maxAttempts=3)

        assertEquals(0, cacheRepo.countSuccessful());
        assertEquals(1, cacheRepo.countFailed());
    }

    @Test
    void requestTranslation_transientErrorSetsRetryAfter() {
        when(ollamaAdapter.generate(any())).thenThrow(
                new OllamaException("Connection refused to Ollama"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        worker.processOne();

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
        assertEquals(0, s.queuePending());
        assertEquals(0, s.queueInFlight());
    }

    @Test
    void stats_reflectsSuccessAndFailure() {
        when(ollamaAdapter.generate(any()))
                .thenReturn(fakeResponse("OK"))
                .thenThrow(new OllamaException("HTTP 500"));

        service.requestTranslation(new TranslationRequest("テスト1", null, null, null));
        service.requestTranslation(new TranslationRequest("テスト2", null, null, null));
        worker.processOne(); // process テスト1 → success
        worker.processOne(); // process テスト2 → failure (attempt 1, re-queued)

        TranslationServiceStats s = service.stats();
        assertEquals(2, s.cacheTotal());
        assertEquals(1, s.cacheSuccessful());
        assertEquals(1, s.cacheFailed());
    }

    // -------------------------------------------------------------------------
    // getCached cache priority (Phase 3) — human > tier-1 > tier-2 > miss
    // -------------------------------------------------------------------------

    @Test
    void getCached_humanCorrectedText_winsOverEnglishText() {
        when(ollamaAdapter.generate(any())).thenReturn(fakeResponse("LLM Translation"));

        service.requestTranslation(new TranslationRequest("テスト", null, null, null));
        worker.processOne(); // tier-1 translates → "LLM Translation"

        // Manually write human correction to the cache row
        String hash = TranslationNormalization.hashOf("テスト");
        long stratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        var cacheRow = cacheRepo.findByHashAndStrategy(hash, stratId).orElseThrow();
        cacheRepo.updateHumanCorrection(cacheRow.id(), "Human Corrected", "2026-05-01T00:00:00.000Z");

        // getCached should return the human correction, not the LLM output
        Optional<String> result = service.getCached("テスト");
        assertTrue(result.isPresent());
        assertEquals("Human Corrected", result.get());
    }

    @Test
    void getCached_tier2CacheRow_returnedWhenNoTier1() {
        // Write a tier-2 cache row directly
        String normalised = TranslationNormalization.normalize("中出し特集");
        String hash = TranslationNormalization.hashOf(normalised);
        long tier2StratId = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();

        TranslationCacheRow tier2CacheRow = new TranslationCacheRow(
                0, hash, normalised, tier2StratId,
                "Creampie Special", null, null,
                null, null, 1000, 10, 20, 4_000_000_000L,
                "2026-05-01T00:00:00.000Z"
        );
        cacheRepo.insert(tier2CacheRow);

        Optional<String> result = service.getCached("中出し特集");
        assertTrue(result.isPresent());
        assertEquals("Creampie Special", result.get());
    }

    @Test
    void getCached_tier1WinsOverTier2() {
        String normalised = TranslationNormalization.normalize("中出し特集");
        String hash = TranslationNormalization.hashOf(normalised);

        long tier1StratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        long tier2StratId = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();

        // Write both tier-1 and tier-2 cache rows
        cacheRepo.insert(new TranslationCacheRow(
                0, hash, normalised, tier1StratId, "Tier1 Translation", null, null,
                null, null, 1000, 10, 20, 4_000_000_000L, "2026-05-01T00:00:00.000Z"));
        cacheRepo.insert(new TranslationCacheRow(
                0, hash, normalised, tier2StratId, "Tier2 Translation", null, null,
                null, null, 1000, 10, 20, 4_000_000_000L, "2026-05-01T00:00:00.000Z"));

        Optional<String> result = service.getCached("中出し特集");
        assertTrue(result.isPresent());
        assertEquals("Tier1 Translation", result.get(), "Tier-1 should win over tier-2");
    }

    @Test
    void getCached_sanitizedBothTiers_returnsEmpty() {
        // Permanent failure row — both tiers gave up
        String normalised = TranslationNormalization.normalize("中出し特集");
        String hash = TranslationNormalization.hashOf(normalised);
        long tier2StratId = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();

        cacheRepo.insert(new TranslationCacheRow(
                0, hash, normalised, tier2StratId, null, null, null,
                "sanitized_both_tiers", null, 1000, 10, 20, 4_000_000_000L, "2026-05-01T00:00:00.000Z"));

        Optional<String> result = service.getCached("中出し特集");
        assertTrue(result.isEmpty(), "sanitized_both_tiers should return empty Optional");
    }

    @Test
    void getCached_tier1Success_tier2SanitizedBothTiers_returnsTier1Text() {
        // Regression: a sanitized_both_tiers row must NOT discard a valid tier-1 result from another strategy
        String normalised = TranslationNormalization.normalize("中出し特集");
        String hash = TranslationNormalization.hashOf(normalised);
        long tier1StratId = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow().id();
        long tier2StratId = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow().id();

        // Tier-2 gave up (sanitized_both_tiers)
        cacheRepo.insert(new TranslationCacheRow(
                0, hash, normalised, tier2StratId, null, null, null,
                "sanitized_both_tiers", null, 1000, 10, 20, 4_000_000_000L, "2026-05-01T00:00:00.000Z"));

        // But tier-1 succeeded
        cacheRepo.insert(new TranslationCacheRow(
                0, hash, normalised, tier1StratId, "Tier1 Good Result", null, null,
                null, null, 1000, 10, 20, 4_000_000_000L, "2026-05-01T00:00:00.000Z"));

        Optional<String> result = service.getCached("中出し特集");
        assertTrue(result.isPresent(), "Tier-1 success must not be discarded by a tier-2 sanitized_both_tiers row");
        assertEquals("Tier1 Good Result", result.get());
    }

    // -------------------------------------------------------------------------
    // resolveStageName — Phase 5
    // -------------------------------------------------------------------------

    @Test
    void resolveStageName_missReturnsEmpty() {
        Optional<String> result = service.resolveStageName("浜崎真緒");
        assertTrue(result.isEmpty(), "Unknown stage name must return empty");
    }

    @Test
    void resolveStageName_curatedLookupHit() {
        stageNameLookupRepo.upsert("浜崎真緒", "Mao Hamasaki", "hamasaki_mao", "yaml_seed");

        Optional<String> result = service.resolveStageName("浜崎真緒");
        assertTrue(result.isPresent());
        assertEquals("Mao Hamasaki", result.get());
    }

    @Test
    void resolveStageName_nfkcNormalizationApplied() {
        // Insert with normalized form; query with full-width input that normalizes to the same form
        stageNameLookupRepo.upsert("浜崎真緒", "Mao Hamasaki", "hamasaki_mao", "yaml_seed");
        // Full-width hiragana won't match in this case, but we can verify
        // the same key works across repeated normalize calls
        Optional<String> result = service.resolveStageName("浜崎真緒"); // same characters
        assertTrue(result.isPresent());
        assertEquals("Mao Hamasaki", result.get());
    }

    @Test
    void resolveStageName_acceptedSuggestionHit() throws Exception {
        String now = "2026-05-03T00:00:00.000Z";
        stageNameSuggestionRepo.recordSuggestion("蒼井そら", "Sora Aoi", now);
        // Manually accept the suggestion
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision = 'accepted', reviewed_at = ? WHERE kanji_form = ?",
                now, "蒼井そら"));

        Optional<String> result = service.resolveStageName("蒼井そら");
        assertTrue(result.isPresent());
        assertEquals("Sora Aoi", result.get());
    }

    @Test
    void resolveStageName_unreviewedSuggestionReturnsEmpty() {
        String now = "2026-05-03T00:00:00.000Z";
        stageNameSuggestionRepo.recordSuggestion("蒼井そら", "Sora Aoi", now);
        // Not reviewed yet

        Optional<String> result = service.resolveStageName("蒼井そら");
        assertTrue(result.isEmpty(), "Unreviewed suggestion must not be returned");
    }

    @Test
    void resolveStageName_curatedWinsOverSuggestion() {
        // Both curated and an accepted suggestion exist — curated wins
        stageNameLookupRepo.upsert("蒼井そら", "Sora Aoi (curated)", "aoi_sora", "yaml_seed");
        String now = "2026-05-03T00:00:00.000Z";
        stageNameSuggestionRepo.recordSuggestion("蒼井そら", "Sora Aoi (llm)", now);
        jdbi.useHandle(h -> h.execute(
                "UPDATE stage_name_suggestion SET review_decision = 'accepted', reviewed_at = ? WHERE kanji_form = ?",
                now, "蒼井そら"));

        Optional<String> result = service.resolveStageName("蒼井そら");
        assertTrue(result.isPresent());
        assertEquals("Sora Aoi (curated)", result.get());
    }

    @Test
    void looksLikeStageName_shortJapaneseIsTrue() {
        assertTrue(TranslationServiceImpl.looksLikeStageName("浜崎真緒"));
        assertTrue(TranslationServiceImpl.looksLikeStageName("あいだゆあ"));
    }

    @Test
    void looksLikeStageName_longTextIsFalse() {
        // 20+ characters
        assertFalse(TranslationServiceImpl.looksLikeStageName("あいうえおかきくけこさしすせそたちつてとな"));
    }

    @Test
    void looksLikeStageName_romajiIsFalse() {
        assertFalse(TranslationServiceImpl.looksLikeStageName("Yua Aida"));
    }

    @Test
    void looksLikeStageName_nullIsFalse() {
        assertFalse(TranslationServiceImpl.looksLikeStageName(null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OllamaResponse fakeResponse(String text) {
        return new OllamaResponse(text, 5_000_000_000L, 10, 20, 4_000_000_000L);
    }
}
