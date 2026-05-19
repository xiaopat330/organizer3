package com.organizer3.enrichment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.AssistContext;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BatchedEnsembleProcessor}. Uses mocked orchestrator that returns
 * canned futures; PostProcessingRules is disabled so rules can't perturb scripted outcomes.
 */
class BatchedEnsembleProcessorTest {

    private static final String PHI4  = "phi4";
    private static final String GEMMA = "gemma3:12b";

    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private OllamaModelOrchestrator orchestrator;
    private EnrichmentAutoApplier autoApplier;
    private ObjectMapper mapper;
    private BatchedEnsembleProcessor processor;

    @BeforeEach
    void setUp() {
        reviewQueueRepo = mock(EnrichmentReviewQueueRepository.class);
        orchestrator    = mock(OllamaModelOrchestrator.class);
        autoApplier     = mock(EnrichmentAutoApplier.class);
        mapper          = new ObjectMapper();

        EnrichmentAssistConfig cfg = new EnrichmentAssistConfig(
                "suggest", PHI4, GEMMA, 60, 60, "v7-kanji-bridge", 3, false, /* batchSize */ 10);

        processor = new BatchedEnsembleProcessor(
                reviewQueueRepo, orchestrator, cfg,
                new PostProcessingRules(false), mapper, autoApplier);

        // Default context: empty folder + no actresses.
        when(reviewQueueRepo.findContextForAssist(anyLong()))
                .thenReturn(new AssistContext(null, List.of()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String detailJson(String code, String slug1, String slug2) {
        return "{\"code\":\"" + code + "\","
                + "\"linked_slugs\":[],"
                + "\"candidates\":["
                + "{\"slug\":\"" + slug1 + "\",\"title_original\":\"T1\"},"
                + "{\"slug\":\"" + slug2 + "\",\"title_original\":\"T2\"}"
                + "]}";
    }

    private static OpenRow makeRow(long id, long titleId, String code, String slug1, String slug2) {
        return new OpenRow(id, titleId, code, null, "ambiguous", "code_search",
                "2026-01-01T00:00:00Z", detailJson(code, slug1, slug2));
    }

    private static OllamaResponse resp(int pick, String confidence) {
        String json = "{\"pick\":" + pick + ",\"confidence\":\"" + confidence + "\",\"reason\":\"ok\"}";
        return new OllamaResponse(json, 0L, 0, 0, 0L);
    }

    private static OllamaResponse abstainResp() {
        return new OllamaResponse("{\"pick\":null,\"confidence\":\"low\",\"reason\":\"abstain\"}",
                0L, 0, 0, 0L);
    }

    /** Wires orchestrator to return a canned response for a given model + title code combo. */
    private void scriptModel(String model, String code, OllamaResponse response) {
        when(orchestrator.submit(eq(model), argThat(req ->
                req != null && req.prompt() != null && req.prompt().contains("Product code: " + code))))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * Happy path: 3 rows, 2 agreed + 1 conflict. Auto-apply called for the 2 agreed rows.
     * setAiSuggestion called 3 times (once per row).
     */
    @Test
    void process_happyPath_2AgreedAnd1Conflict() throws Exception {
        OpenRow r1 = makeRow(1L, 10L, "TST-001", "tst-001-a", "tst-001-b");
        OpenRow r2 = makeRow(2L, 20L, "TST-002", "tst-002-a", "tst-002-b");
        OpenRow r3 = makeRow(3L, 30L, "TST-003", "tst-003-a", "tst-003-b");

        // r1 + r2 both agree on index 1; r3 conflicts.
        scriptModel(PHI4,  "TST-001", resp(1, "high"));
        scriptModel(GEMMA, "TST-001", resp(1, "high"));
        scriptModel(PHI4,  "TST-002", resp(1, "high"));
        scriptModel(GEMMA, "TST-002", resp(1, "high"));
        scriptModel(PHI4,  "TST-003", resp(1, "medium"));
        scriptModel(GEMMA, "TST-003", resp(2, "medium"));

        // findOpenById used by auto-apply path for agreed rows.
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(
                java.util.Optional.of(makeRow(1L, 10L, "TST-001", "tst-001-a", "tst-001-b")));
        when(reviewQueueRepo.findOpenById(2L)).thenReturn(
                java.util.Optional.of(makeRow(2L, 20L, "TST-002", "tst-002-a", "tst-002-b")));
        when(autoApplier.apply(any())).thenReturn(true);

        List<AssistResult> processed = new ArrayList<>();
        BatchedEnsembleProcessor.ProgressSink sink = new BatchedEnsembleProcessor.ProgressSink() {
            @Override
            public void rowProcessed(long rowId, String code, AssistResult result) {
                processed.add(result);
            }
        };

        BatchedEnsembleProcessor.ProcessingResult result =
                processor.process(List.of(r1, r2, r3), sink, () -> false);

        assertEquals(3, result.processed());
        assertEquals(2, result.agreed());
        assertEquals(2, result.autoApplied());
        assertEquals(0, result.errors());

        // Sink received 3 callbacks, 2 agreed + 1 conflict.
        assertEquals(3, processed.size());
        assertEquals("agreed",   processed.get(0).outcome());
        assertEquals("agreed",   processed.get(1).outcome());
        assertEquals("conflict", processed.get(2).outcome());

        // setAiSuggestion called once per row with 7-arg form.
        verify(reviewQueueRepo, times(3)).setAiSuggestion(
                anyLong(), any(), anyString(), any(), any(Instant.class), any(), any());

        // auto-apply called for the 2 agreed rows only.
        verify(autoApplier, times(2)).apply(any(OpenRow.class));
    }

    /**
     * When one model fails (future completes exceptionally), the row is treated as a
     * single-model outcome and the batch continues.
     */
    @Test
    void process_modelFailure_encodedAsAbstain_batchContinues() throws Exception {
        OpenRow r1 = makeRow(1L, 10L, "TST-001", "tst-001-a", "tst-001-b");
        OpenRow r2 = makeRow(2L, 20L, "TST-002", "tst-002-a", "tst-002-b");

        // r1 phi4 fails; gemma picks index 1 → gemma_only.
        CompletableFuture<OllamaResponse> failFuture = new CompletableFuture<>();
        failFuture.completeExceptionally(new RuntimeException("simulated phi4 outage"));
        when(orchestrator.submit(eq(PHI4), argThat(req -> req.prompt().contains("TST-001"))))
                .thenReturn(failFuture);
        scriptModel(GEMMA, "TST-001", resp(1, "medium"));

        // r2 both agree.
        scriptModel(PHI4,  "TST-002", resp(1, "high"));
        scriptModel(GEMMA, "TST-002", resp(1, "high"));

        when(reviewQueueRepo.findOpenById(2L)).thenReturn(
                java.util.Optional.of(makeRow(2L, 20L, "TST-002", "tst-002-a", "tst-002-b")));
        when(autoApplier.apply(any())).thenReturn(true);

        BatchedEnsembleProcessor.ProcessingResult result =
                processor.process(List.of(r1, r2), new BatchedEnsembleProcessor.ProgressSink(){}, () -> false);

        assertEquals(2, result.processed());
        assertEquals(1, result.agreed());   // r2 only
        assertEquals(1, result.autoApplied());
        assertEquals(0, result.errors());

        verify(reviewQueueRepo, times(2)).setAiSuggestion(
                anyLong(), any(), anyString(), any(), any(Instant.class), any(), any());
    }

    /**
     * When autoApplier is null (backfill path), agreed rows are counted but not applied.
     */
    @Test
    void process_nullAutoApplier_agreedNotAutoApplied() throws Exception {
        EnrichmentAssistConfig cfg = new EnrichmentAssistConfig(
                "suggest", PHI4, GEMMA, 60, 60, "v7-kanji-bridge", 3, false, 10);
        BatchedEnsembleProcessor noApplyProcessor = new BatchedEnsembleProcessor(
                reviewQueueRepo, orchestrator, cfg,
                new PostProcessingRules(false), mapper, null /* no auto-apply */);

        OpenRow r1 = makeRow(1L, 10L, "TST-001", "tst-001-a", "tst-001-b");
        scriptModel(PHI4,  "TST-001", resp(1, "high"));
        scriptModel(GEMMA, "TST-001", resp(1, "high"));

        BatchedEnsembleProcessor.ProcessingResult result =
                noApplyProcessor.process(List.of(r1), new BatchedEnsembleProcessor.ProgressSink(){}, () -> false);

        assertEquals(1, result.processed());
        assertEquals(1, result.agreed());
        assertEquals(0, result.autoApplied()); // not applied — no applier
        verifyNoInteractions(autoApplier);
    }

    /**
     * Cancellation at the chunk boundary stops processing and returns partial results.
     */
    @Test
    void process_cancellation_stopsAtChunkBoundary() throws Exception {
        EnrichmentAssistConfig cfg = new EnrichmentAssistConfig(
                "suggest", PHI4, GEMMA, 60, 60, "v7-kanji-bridge", 3, false, 2 /* chunk=2 */);
        BatchedEnsembleProcessor smallChunkProcessor = new BatchedEnsembleProcessor(
                reviewQueueRepo, orchestrator, cfg,
                new PostProcessingRules(false), mapper, null /* no auto-apply needed */);

        // 4 rows, chunk=2 → 2 chunks. Cancel before second chunk.
        // Use a broad default stub so all orchestrator.submit calls succeed.
        when(orchestrator.submit(anyString(), any(OllamaRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(resp(1, "high")));

        List<OpenRow> rows = List.of(
                makeRow(1L, 10L, "TST-001", "a", "b"),
                makeRow(2L, 20L, "TST-002", "a", "b"),
                makeRow(3L, 30L, "TST-003", "a", "b"),
                makeRow(4L, 40L, "TST-004", "a", "b"));

        // The processor checks cancellation twice per chunk (start + mid-chunk after pass 1).
        // Cancel after both checks of chunk 0 have passed (i.e., on the 3rd check = start of chunk 1).
        int[] calls = {0};
        BatchedEnsembleProcessor.CancellationCheck check = () -> ++calls[0] > 2;

        BatchedEnsembleProcessor.ProcessingResult result =
                smallChunkProcessor.process(rows, new BatchedEnsembleProcessor.ProgressSink(){}, check);

        // Only the first chunk of 2 rows should be processed.
        assertEquals(2, result.processed());
        verify(reviewQueueRepo, times(2)).setAiSuggestion(
                anyLong(), any(), anyString(), any(), any(Instant.class), any(), any());
    }
}
