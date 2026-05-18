package com.organizer3.enrichment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnsembleAssistCallerTest {

    private static final String PHI4 = "phi4";
    private static final String GEMMA = "gemma3:12b";

    private static final String SAMPLE_DETAIL = """
            {
              "code": "ABC-123",
              "linked_slugs": ["yu-tano"],
              "candidates": [
                { "slug": "abc-123-alpha",
                  "title_original": "candidate A",
                  "release_date": "2023-05-01",
                  "maker": "S1",
                  "cast": [ {"slug": "yu-tano", "name": "田野憂"} ],
                  "duration_minutes": 120,
                  "rating_avg": 4.32,
                  "rating_count": 89 },
                { "slug": "abc-123-beta",
                  "title_original": "candidate B",
                  "release_date": "2023-04-01",
                  "maker": "Madonna",
                  "cast": [ {"slug": "other", "name": "東実果"} ] },
                { "slug": "abc-123-gamma",
                  "title_original": "candidate C",
                  "release_date": "2023-03-01",
                  "maker": "Idea Pocket",
                  "cast": [ {"slug": "third", "name": "他"} ] }
              ],
              "fetched_at": "2026-05-17T10:00:00Z"
            }
            """;

    private ObjectMapper objectMapper;
    private EnrichmentAssistConfig config;
    private OllamaModelOrchestrator orchestrator;
    private EnsembleAssistCaller caller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        config = EnrichmentAssistConfig.defaults(); // primary=phi4, secondary=gemma3:12b
        orchestrator = mock(OllamaModelOrchestrator.class);
        caller = new EnsembleAssistCaller(orchestrator, config, objectMapper);
    }

    private EnrichmentReviewQueueRepository.OpenRow sampleRow() {
        return new EnrichmentReviewQueueRepository.OpenRow(
                42L, 100L, "ABC-123", null,
                "ambiguous", "javdb_search", "2026-05-17T00:00:00Z",
                SAMPLE_DETAIL);
    }

    private static OllamaResponse resp(String text) {
        return new OllamaResponse(text, 0L, 0, 0, 0L);
    }

    private static String pickJson(Integer pick, String confidence, String reason) {
        String pickPart = (pick == null) ? "null" : pick.toString();
        return "{\"pick\": " + pickPart
                + ", \"confidence\": \"" + confidence + "\""
                + ", \"reason\": \"" + reason + "\"}";
    }

    private void stub(String model, OllamaResponse response) {
        when(orchestrator.submit(eq(model), any(OllamaRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void stubFailing(String model, Throwable t) {
        CompletableFuture<OllamaResponse> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        when(orchestrator.submit(eq(model), any(OllamaRequest.class))).thenReturn(f);
    }

    // ------------------------------------------------------------------ 5 voting outcomes

    @Test
    void agreed_bothPickSameIndex_returnsAgreedWithMinConfidence() {
        stub(PHI4,  resp(pickJson(2, "high",   "matches cast B")));
        stub(GEMMA, resp(pickJson(2, "medium", "cast B match")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("agreed", result.outcome());
        assertEquals("medium", result.confidence(),     "min(high, medium) = medium");
        assertEquals("abc-123-beta", result.suggestedSlug());
        assertEquals(Integer.valueOf(2), result.phi4Pick());
        assertEquals(Integer.valueOf(2), result.gemmaPick());
    }

    @Test
    void phi4Only_gemmaAbstains_returnsPhi4Pick() {
        stub(PHI4,  resp(pickJson(1, "high", "code+cast match")));
        stub(GEMMA, resp(pickJson(null, "low", "ambiguous")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("phi4_only", result.outcome());
        assertEquals("high", result.confidence());
        assertEquals("abc-123-alpha", result.suggestedSlug());
        assertEquals(Integer.valueOf(1), result.phi4Pick());
        assertNull(result.gemmaPick());
    }

    @Test
    void gemmaOnly_phi4Abstains_returnsGemmaPick() {
        stub(PHI4,  resp(pickJson(null, "low", "no clear winner")));
        stub(GEMMA, resp(pickJson(3, "medium", "release date matches")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("gemma_only", result.outcome());
        assertEquals("medium", result.confidence());
        assertEquals("abc-123-gamma", result.suggestedSlug());
        assertNull(result.phi4Pick());
        assertEquals(Integer.valueOf(3), result.gemmaPick());
    }

    @Test
    void conflict_modelsDisagree_returnsConflictWithNullSlug() {
        stub(PHI4,  resp(pickJson(1, "medium", "alpha")));
        stub(GEMMA, resp(pickJson(2, "high",   "beta")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("conflict", result.outcome());
        assertNull(result.confidence());
        assertNull(result.suggestedSlug());
        assertEquals(Integer.valueOf(1), result.phi4Pick());
        assertEquals(Integer.valueOf(2), result.gemmaPick());
    }

    @Test
    void bothAbstain_returnsBothAbstainWithNullSlug() {
        stub(PHI4,  resp(pickJson(null, "low", "unsure")));
        stub(GEMMA, resp(pickJson(null, "low", "unsure")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("both_abstain", result.outcome());
        assertNull(result.confidence());
        assertNull(result.suggestedSlug());
        assertNull(result.phi4Pick());
        assertNull(result.gemmaPick());
    }

    // ------------------------------------------------------------------ defensive paths

    @Test
    void phi4OutOfRangeIndex_treatedAsAbstain_fallsThroughToGemma() {
        stub(PHI4,  resp(pickJson(99, "high", "oops")));
        stub(GEMMA, resp(pickJson(2,  "medium", "beta")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("gemma_only", result.outcome());
        assertEquals("abc-123-beta", result.suggestedSlug());
        assertNull(result.phi4Pick());
        assertEquals(Integer.valueOf(2), result.gemmaPick());
    }

    @Test
    void phi4OutOfRangeAndGemmaAbstains_returnsBothAbstain() {
        stub(PHI4,  resp(pickJson(99, "high", "oops")));
        stub(GEMMA, resp(pickJson(null, "low", "unsure")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("both_abstain", result.outcome());
        assertNull(result.suggestedSlug());
    }

    @Test
    void phi4FutureFailsExceptionally_treatedAsAbstainGemmaStillConsulted() {
        stubFailing(PHI4, new RuntimeException("connection refused"));
        stub(GEMMA, resp(pickJson(1, "high", "alpha matches")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("gemma_only", result.outcome());
        assertEquals("abc-123-alpha", result.suggestedSlug());
        assertNull(result.phi4Pick());
        assertEquals(Integer.valueOf(1), result.gemmaPick());
    }

    @Test
    void malformedJsonFromOneModel_treatedAsAbstain() {
        stub(PHI4,  resp("not valid json at all"));
        stub(GEMMA, resp(pickJson(2, "medium", "beta")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("gemma_only", result.outcome());
        assertNull(result.phi4Pick());
        assertEquals(Integer.valueOf(2), result.gemmaPick());
    }

    // ------------------------------------------------------------------ Track G overload

    @Test
    void evaluateOverload_threadsFolderPathAndActressNamesIntoPromptInput() {
        AssistPromptBuilder.Input input = caller.materializeInput(
                sampleRow(),
                "/Volumes/x/Yu Tano/ABC-123",
                java.util.List.of("Yu Tano", "Mika Azuma"));

        assertEquals("/Volumes/x/Yu Tano/ABC-123", input.folderPath());
        assertEquals(java.util.List.of("Yu Tano", "Mika Azuma"), input.actressNames());
        assertEquals("ABC-123", input.code());
        // user prompt must include both hints (folder name + filed-under actress)
        String user = AssistPromptBuilder.buildUserPrompt(input);
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("Folder name: ABC-123"),
                "user prompt must show the trailing folder segment");
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("Filed under actress(es): Yu Tano, Mika Azuma"),
                "user prompt must list the romaji actresses for the kanji bridge");
    }

    @Test
    void evaluateOverload_nullFolderEmptyNames_omitsHintLines() {
        AssistPromptBuilder.Input input = caller.materializeInput(
                sampleRow(), null, java.util.List.of());

        assertNull(input.folderPath());
        assertNull(input.actressNames(),
                "empty list collapses to null so the prompt omits the Filed-under line");
        String user = AssistPromptBuilder.buildUserPrompt(input);
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("Folder name:"),
                "no folder hint when path is null");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("Filed under actress(es):"),
                "no actress hint when names are empty");
    }

    // ------------------------------------------------------------------ Phase 4 Track B: post-processing rules integration

    /** Detail JSON where the linked actress appears in BOTH candidates and the agreed
     *  candidate's title strictly contains the other's title (bonus-version pattern). */
    private static final String BONUS_PATTERN_DETAIL = """
            {
              "code": "ABC-123",
              "linked_slugs": ["yu-tano"],
              "candidates": [
                { "slug": "abc-123-short",
                  "title_original": "ABC-123 Canonical Edition",
                  "cast": [ {"slug": "yu-tano", "name": "Yu Tano"} ] },
                { "slug": "abc-123-bonus",
                  "title_original": "ABC-123 Canonical Edition Bonus Extra Long Cut",
                  "cast": [ {"slug": "yu-tano", "name": "Yu Tano"} ] }
              ],
              "fetched_at": "2026-05-17T10:00:00Z"
            }
            """;

    private EnrichmentReviewQueueRepository.OpenRow bonusRow() {
        return new EnrichmentReviewQueueRepository.OpenRow(
                42L, 100L, "ABC-123", null,
                "ambiguous", "javdb_search", "2026-05-17T00:00:00Z",
                BONUS_PATTERN_DETAIL);
    }

    @Test
    void postProcessing_enabled_rule1OverridesAgreedBonusPickToShorterCanonical() {
        EnsembleAssistCaller withRules = new EnsembleAssistCaller(
                orchestrator, config, objectMapper,
                new PostProcessingRules(true));

        // Both models agree on candidate 2 (the bonus version).
        stub(PHI4,  resp(pickJson(2, "high", "matches")));
        stub(GEMMA, resp(pickJson(2, "high", "matches")));

        AssistResult result = withRules.evaluate(bonusRow(),
                "/Volumes/x/Yu Tano/ABC-123", java.util.List.of("Yu Tano"));

        assertEquals("agreed_with_override", result.outcome());
        assertEquals("abc-123-short", result.suggestedSlug());
        // Pick indices preserved — record what the models actually said.
        assertEquals(Integer.valueOf(2), result.phi4Pick());
        assertEquals(Integer.valueOf(2), result.gemmaPick());
    }

    @Test
    void postProcessing_disabled_noOverrideEvenWhenRule1Conditions() {
        EnsembleAssistCaller withoutRules = new EnsembleAssistCaller(
                orchestrator, config, objectMapper,
                new PostProcessingRules(false));

        stub(PHI4,  resp(pickJson(2, "high", "matches")));
        stub(GEMMA, resp(pickJson(2, "high", "matches")));

        AssistResult result = withoutRules.evaluate(bonusRow(),
                "/Volumes/x/Yu Tano/ABC-123", java.util.List.of("Yu Tano"));

        assertEquals("agreed", result.outcome());
        assertEquals("abc-123-bonus", result.suggestedSlug());
    }

    @Test
    void zeroCandidatesAfterParsing_throwsIllegalStateException() {
        String emptyDetail = """
                { "code": "ABC-123", "linked_slugs": [], "candidates": [] }
                """;
        EnrichmentReviewQueueRepository.OpenRow row = new EnrichmentReviewQueueRepository.OpenRow(
                42L, 100L, "ABC-123", null,
                "ambiguous", "javdb_search", "2026-05-17T00:00:00Z",
                emptyDetail);

        assertThrows(IllegalStateException.class, () -> caller.evaluate(row));
    }

    // ------------------------------------------------------------------ Phase 5 Track A — vote(...) direct tests

    /**
     * Build a 3-candidate fixture; only the {@code slug()} field is consulted by {@code vote()}.
     */
    private static java.util.List<AssistPromptBuilder.Input.Candidate> threeCandidates() {
        return java.util.List.of(
                new AssistPromptBuilder.Input.Candidate("alpha", "T1", null, null, java.util.List.of(), null, null, null),
                new AssistPromptBuilder.Input.Candidate("beta",  "T2", null, null, java.util.List.of(), null, null, null),
                new AssistPromptBuilder.Input.Candidate("gamma", "T3", null, null, java.util.List.of(), null, null, null));
    }

    @Test
    void vote_bothPickSameIndex_returnsAgreedMinConfidence() {
        AssistResult r = EnsembleAssistCaller.vote(
                2, "high",   "rA",
                2, "medium", "rB",
                threeCandidates());
        assertEquals("agreed", r.outcome());
        assertEquals("medium", r.confidence());
        assertEquals("beta", r.suggestedSlug());
        assertEquals(Integer.valueOf(2), r.phi4Pick());
        assertEquals(Integer.valueOf(2), r.gemmaPick());
    }

    @Test
    void vote_phi4OnlyAbstainsGemma_returnsPhi4Only() {
        AssistResult r = EnsembleAssistCaller.vote(
                1, "high", "rA",
                null, null, null,
                threeCandidates());
        assertEquals("phi4_only", r.outcome());
        assertEquals("high", r.confidence());
        assertEquals("alpha", r.suggestedSlug());
        assertEquals(Integer.valueOf(1), r.phi4Pick());
        assertNull(r.gemmaPick());
    }

    @Test
    void vote_gemmaOnly_returnsGemmaOnly() {
        AssistResult r = EnsembleAssistCaller.vote(
                null, null, null,
                3, "medium", "rB",
                threeCandidates());
        assertEquals("gemma_only", r.outcome());
        assertEquals("medium", r.confidence());
        assertEquals("gamma", r.suggestedSlug());
        assertNull(r.phi4Pick());
        assertEquals(Integer.valueOf(3), r.gemmaPick());
    }

    @Test
    void vote_conflict_returnsConflictWithNullSlug() {
        AssistResult r = EnsembleAssistCaller.vote(
                1, "medium", "rA",
                2, "high",   "rB",
                threeCandidates());
        assertEquals("conflict", r.outcome());
        assertNull(r.confidence());
        assertNull(r.suggestedSlug());
        assertEquals(Integer.valueOf(1), r.phi4Pick());
        assertEquals(Integer.valueOf(2), r.gemmaPick());
    }

    @Test
    void vote_bothAbstain_returnsBothAbstain() {
        AssistResult r = EnsembleAssistCaller.vote(
                null, null, null,
                null, null, null,
                threeCandidates());
        assertEquals("both_abstain", r.outcome());
        assertNull(r.confidence());
        assertNull(r.suggestedSlug());
        assertNull(r.phi4Pick());
        assertNull(r.gemmaPick());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Phase 5 Track B — parallel ensemble (gated, with memory-pressure fallback)
    // ──────────────────────────────────────────────────────────────────────────

    private EnrichmentAssistConfig configWithParallel(boolean enabled, int memoryBudgetMb) {
        return new EnrichmentAssistConfig(
                "shadow", PHI4, GEMMA, 60, 60, "v7-kanji-bridge", 3, true, 20,
                enabled, memoryBudgetMb);
    }

    private void stubParallel(String model, OllamaResponse response) {
        when(orchestrator.submitParallel(eq(model), any(OllamaRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test
    void parallel_flagOn_andMemoryOk_dispatchesBothViaSubmitParallel() {
        EnrichmentAssistConfig parCfg = configWithParallel(true, 22000);
        EnsembleAssistCaller parCaller = new EnsembleAssistCaller(orchestrator, parCfg, objectMapper);

        when(orchestrator.currentLoadedModelMb()).thenReturn(15000);
        stubParallel(PHI4,  resp(pickJson(2, "high",   "matches cast B")));
        stubParallel(GEMMA, resp(pickJson(2, "medium", "cast B match")));

        AssistResult result = parCaller.evaluate(sampleRow());

        assertEquals("agreed", result.outcome());
        assertEquals("abc-123-beta", result.suggestedSlug());
        // Both submitParallel calls happened — exactly one per model.
        org.mockito.Mockito.verify(orchestrator).submitParallel(eq(PHI4),  any(OllamaRequest.class));
        org.mockito.Mockito.verify(orchestrator).submitParallel(eq(GEMMA), any(OllamaRequest.class));
        // And serial submit() was NOT used on the parallel path.
        org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never())
                .submit(org.mockito.ArgumentMatchers.anyString(), any(OllamaRequest.class));
    }

    @Test
    void parallel_flagOn_memoryPressure_fallsBackToSerial() {
        EnrichmentAssistConfig parCfg = configWithParallel(true, 22000);
        EnsembleAssistCaller parCaller = new EnsembleAssistCaller(orchestrator, parCfg, objectMapper);

        // Budget exceeded — parallel path must be skipped, serial path used instead.
        when(orchestrator.currentLoadedModelMb()).thenReturn(30000);
        stub(PHI4,  resp(pickJson(2, "high",   "matches cast B")));
        stub(GEMMA, resp(pickJson(2, "medium", "cast B match")));

        AssistResult result = parCaller.evaluate(sampleRow());

        assertEquals("agreed", result.outcome());
        org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never())
                .submitParallel(org.mockito.ArgumentMatchers.anyString(), any(OllamaRequest.class));
        org.mockito.Mockito.verify(orchestrator).submit(eq(PHI4),  any(OllamaRequest.class));
        org.mockito.Mockito.verify(orchestrator).submit(eq(GEMMA), any(OllamaRequest.class));
    }

    @Test
    void parallel_flagOff_alwaysUsesSerialPath() {
        // Default config has parallelEnsemble=false. Even if currentLoadedModelMb reports
        // plenty of headroom, the parallel path must remain dormant.
        stub(PHI4,  resp(pickJson(1, "high", "alpha")));
        stub(GEMMA, resp(pickJson(1, "high", "alpha")));

        AssistResult result = caller.evaluate(sampleRow());

        assertEquals("agreed", result.outcome());
        org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never())
                .submitParallel(org.mockito.ArgumentMatchers.anyString(), any(OllamaRequest.class));
        // currentLoadedModelMb should NOT even be consulted when flag is off.
        org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never()).currentLoadedModelMb();
    }

    @Test
    void parallel_oneFutureThrows_otherStillContributes() {
        EnrichmentAssistConfig parCfg = configWithParallel(true, 22000);
        EnsembleAssistCaller parCaller = new EnsembleAssistCaller(orchestrator, parCfg, objectMapper);

        when(orchestrator.currentLoadedModelMb()).thenReturn(15000);
        // phi4 future fails entirely; gemma succeeds. Should NOT bubble, should produce gemma_only.
        CompletableFuture<OllamaResponse> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("ollama exploded"));
        when(orchestrator.submitParallel(eq(PHI4),  any(OllamaRequest.class))).thenReturn(failing);
        stubParallel(GEMMA, resp(pickJson(3, "medium", "release date matches")));

        AssistResult result = parCaller.evaluate(sampleRow());

        assertEquals("gemma_only", result.outcome());
        assertEquals("abc-123-gamma", result.suggestedSlug());
        assertNull(result.phi4Pick());
        assertEquals(Integer.valueOf(3), result.gemmaPick());
    }
}
