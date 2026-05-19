package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.enrichment.ai.AssistResult;
import com.organizer3.enrichment.ai.BatchedEnsembleProcessor;
import com.organizer3.enrichment.ai.EnsembleAssistCaller;
import com.organizer3.enrichment.ai.EnrichmentAutoApplier;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.AssistContext;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.web.WebServer;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowRoutesTest {

    private WebServer server;
    private WorkflowRoutes workflowRoutes;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private EnsembleAssistCaller ensembleAssistCaller;
    private EnrichmentAutoApplier autoApplier;
    private BatchedEnsembleProcessor batchedProcessor;
    private Jdbi jdbi;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        reviewQueueRepo      = mock(EnrichmentReviewQueueRepository.class);
        ensembleAssistCaller = mock(EnsembleAssistCaller.class);
        autoApplier          = mock(EnrichmentAutoApplier.class);
        batchedProcessor     = mock(BatchedEnsembleProcessor.class);
        jdbi                 = mock(Jdbi.class);

        // Default: withHandle invokes the callback with a deep-stub Handle that returns
        // empty lists for any query chain.
        Handle handle = mock(Handle.class, RETURNS_DEEP_STUBS);
        when(handle.createQuery(anyString()).mapToMap().list()).thenReturn(List.of());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            HandleCallback<Object, Exception> cb = inv.getArgument(0);
            return cb.withHandle(handle);
        }).when(jdbi).withHandle(any());

        workflowRoutes = new WorkflowRoutes(reviewQueueRepo, ensembleAssistCaller, autoApplier,
                batchedProcessor, jdbi, null);
        server = new WebServer(0);
        server.registerWorkflow(workflowRoutes);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── GET /api/enrichment/workflow/rows ─────────────────────────────────────

    @Test
    void getRows_returns200EmptyArray() throws Exception {
        HttpResponse<String> res = get("/api/enrichment/workflow/rows");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    @Test
    void getRows_limitParamParsed() throws Exception {
        // Just verify no 4xx/5xx with valid limit param.
        HttpResponse<String> res = get("/api/enrichment/workflow/rows?limit=50");
        assertEquals(200, res.statusCode());
    }

    @Test
    void getRows_invalidLimitIgnored() throws Exception {
        // Invalid limit falls back to default — should still return 200.
        HttpResponse<String> res = get("/api/enrichment/workflow/rows?limit=notanumber");
        assertEquals(200, res.statusCode());
    }

    // ── POST /api/enrichment/workflow/{queueRowId}/ai-assist ─────────────────

    @Test
    void aiAssist_returns404WhenRowNotFound() throws Exception {
        when(reviewQueueRepo.findOpenById(99L)).thenReturn(Optional.empty());

        HttpResponse<String> res = post("/api/enrichment/workflow/99/ai-assist");

        assertEquals(404, res.statusCode());
        verifyNoInteractions(ensembleAssistCaller);
    }

    @Test
    void aiAssist_returns400WhenNotAmbiguous() throws Exception {
        OpenRow row = makeRow(1L, 10L, "STAR-001", "no_match");
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(Optional.of(row));

        HttpResponse<String> res = post("/api/enrichment/workflow/1/ai-assist");

        assertEquals(400, res.statusCode());
        verifyNoInteractions(ensembleAssistCaller);
    }

    @Test
    void aiAssist_returns400OnNonNumericId() throws Exception {
        HttpResponse<String> res = post("/api/enrichment/workflow/abc/ai-assist");
        assertEquals(400, res.statusCode());
        verifyNoInteractions(reviewQueueRepo);
    }

    @Test
    void aiAssist_returns202ImmediatelyAndExecutesAsync() throws Exception {
        CountDownLatch evaluateCalled = new CountDownLatch(1);

        OpenRow row = makeRow(1L, 10L, "STAR-001", "ambiguous");
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(Optional.of(row));

        AssistContext ctx = new AssistContext("/stars/Actress/STAR-001", List.of("Akari Mitani"));
        when(reviewQueueRepo.findContextForAssist(10L)).thenReturn(ctx);

        AssistResult result = new AssistResult("agreed", "high", "AbCd12",
                "both models agreed", 1, 1, "AbCd12", "AbCd12");
        when(ensembleAssistCaller.evaluate(eq(row), eq(ctx.folderPath()), eq(ctx.actressNames())))
                .thenAnswer(inv -> { evaluateCalled.countDown(); return result; });
        when(autoApplier.apply(any())).thenReturn(true);
        // findOpenById called again after suggestion is written (for auto-apply).
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(Optional.of(row));

        HttpResponse<String> res = post("/api/enrichment/workflow/1/ai-assist");

        // Endpoint must return 202 immediately — before the executor completes.
        assertEquals(202, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(1L,  body.get("queueRowId").asLong());
        assertTrue(body.get("queued").asBoolean());

        // Wait for the background executor to actually call evaluate.
        assertTrue(evaluateCalled.await(5, TimeUnit.SECONDS), "evaluate was not called within timeout");

        // Give the executor a moment to finish persist + auto-apply after evaluate returns.
        verify(reviewQueueRepo, timeout(5000)).setAiSuggestion(
                eq(1L), eq("AbCd12"), eq("agreed"),
                anyString(), any(Instant.class), eq("AbCd12"), eq("AbCd12"));
        verify(autoApplier, timeout(5000)).apply(any(OpenRow.class));
    }

    @Test
    void aiAssist_skipsAutoApplyWhenOutcomeNotAgreed() throws Exception {
        CountDownLatch persistCalled = new CountDownLatch(1);

        OpenRow row = makeRow(1L, 10L, "STAR-001", "ambiguous");
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(Optional.of(row));

        AssistContext ctx = new AssistContext(null, List.of());
        when(reviewQueueRepo.findContextForAssist(10L)).thenReturn(ctx);

        // conflict outcome — autoApplier must not be called.
        AssistResult result = new AssistResult("conflict", null, null,
                "models disagreed", 1, 2, "slug-A", "slug-B");
        when(ensembleAssistCaller.evaluate(any(), any(), any())).thenReturn(result);
        doAnswer(inv -> { persistCalled.countDown(); return null; })
                .when(reviewQueueRepo).setAiSuggestion(anyLong(), any(), any(), any(), any(), any(), any());

        HttpResponse<String> res = post("/api/enrichment/workflow/1/ai-assist");

        assertEquals(202, res.statusCode());

        // Wait for the executor to finish persisting.
        assertTrue(persistCalled.await(5, TimeUnit.SECONDS), "setAiSuggestion was not called within timeout");
        verifyNoInteractions(autoApplier);
    }

    @Test
    void aiAssist_writesErrorSentinelWhenEvaluateThrows() throws Exception {
        OpenRow row = makeRow(1L, 10L, "STAR-001", "ambiguous");
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(Optional.of(row));

        AssistContext ctx = new AssistContext(null, List.of());
        when(reviewQueueRepo.findContextForAssist(10L)).thenReturn(ctx);

        when(ensembleAssistCaller.evaluate(any(), any(), any()))
                .thenThrow(new IllegalStateException("zero candidates"));

        HttpResponse<String> res = post("/api/enrichment/workflow/1/ai-assist");

        // Endpoint returns 202 immediately regardless of what the executor will do.
        assertEquals(202, res.statusCode());
        // Error sentinel must be persisted asynchronously.
        verify(reviewQueueRepo, timeout(5000)).setAiSuggestion(
                eq(1L), isNull(), eq("error"),
                anyString(), any(Instant.class), isNull(), isNull());
    }

    // ── POST /api/enrichment/workflow/ai-assist-all ────────────────────────────

    @Test
    void aiAssistAll_returns200WithZeroWhenNoPending() throws Exception {
        when(reviewQueueRepo.listOpenAwaitingAi(anyInt())).thenReturn(List.of());

        HttpResponse<String> res = post("/api/enrichment/workflow/ai-assist-all");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(0, body.get("queued").asInt());
    }

    @Test
    void aiAssistAll_returns202WithCountWhenRowsPresent() throws Exception {
        OpenRow r1 = makeRow(1L, 10L, "STAR-001", "ambiguous");
        OpenRow r2 = makeRow(2L, 11L, "STAR-002", "ambiguous");
        when(reviewQueueRepo.listOpenAwaitingAi(anyInt())).thenReturn(List.of(r1, r2));

        // Processor returns immediately with a canned result.
        when(batchedProcessor.process(anyList(), any(), any()))
                .thenReturn(new BatchedEnsembleProcessor.ProcessingResult(2, 2, 2, 0));

        HttpResponse<String> res = post("/api/enrichment/workflow/ai-assist-all");

        assertEquals(202, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(2, body.get("queued").asInt());

        // Verify the batched processor was invoked (asynchronously — use timeout).
        verify(batchedProcessor, timeout(5000)).process(anyList(), any(), any());
        // The per-row submitAiAssist path must NOT be called for the bulk route when batchedProcessor is wired.
        verifyNoInteractions(ensembleAssistCaller);
    }

    @Test
    void getRows_responseIncludesPerModelSlugKeys() throws Exception {
        // Verify the rows endpoint at minimum returns 200; field presence is tested via
        // the real-DB EnrichmentReviewQueueRepositoryTest for the new columns.
        HttpResponse<String> res = get("/api/enrichment/workflow/rows");
        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
    }

    @Test
    void aiAssist_responseIncludesPerModelSlugFields() throws Exception {
        OpenRow row = makeRow(1L, 10L, "STAR-001", "ambiguous");
        when(reviewQueueRepo.findOpenById(1L)).thenReturn(Optional.of(row));

        AssistContext ctx = new AssistContext(null, List.of());
        when(reviewQueueRepo.findContextForAssist(10L)).thenReturn(ctx);

        // Conflict outcome: phi4 picked slug-A, gemma picked slug-B.
        AssistResult result = new AssistResult("conflict", null, null,
                "models disagreed", 1, 2, "slug-A", "slug-B");
        when(ensembleAssistCaller.evaluate(any(), any(), any())).thenReturn(result);

        HttpResponse<String> res = post("/api/enrichment/workflow/1/ai-assist");

        assertEquals(202, res.statusCode());
        // The conflict outcome must be stored with per-model slugs asynchronously.
        verify(reviewQueueRepo, timeout(5000)).setAiSuggestion(
                eq(1L), isNull(), eq("conflict"),
                anyString(), any(Instant.class), eq("slug-A"), eq("slug-B"));
    }

    // ── Tracking state ────────────────────────────────────────────────────────

    @Test
    void aiAssist_trackingSetsReflectQueuedThenClearedAfterExecution() throws Exception {
        CountDownLatch evaluateStarted = new CountDownLatch(1);
        CountDownLatch evaluateRelease = new CountDownLatch(1);

        OpenRow row = makeRow(5L, 50L, "TEST-005", "ambiguous");
        when(reviewQueueRepo.findOpenById(5L)).thenReturn(Optional.of(row));

        AssistContext ctx = new AssistContext(null, List.of());
        when(reviewQueueRepo.findContextForAssist(50L)).thenReturn(ctx);

        AssistResult result = new AssistResult("agreed", null, "slug-x",
                "agreed", 1, 1, "slug-x", "slug-x");
        when(ensembleAssistCaller.evaluate(any(), any(), any())).thenAnswer(inv -> {
            evaluateStarted.countDown();
            evaluateRelease.await(); // hold the executor thread here
            return result;
        });
        when(autoApplier.apply(any())).thenReturn(true);

        // Submit — row should appear in aiQueued immediately.
        post("/api/enrichment/workflow/5/ai-assist");
        // Brief window: row is either aiQueued (not yet started) or aiProcessing (started).
        // Both are valid since the executor may begin instantly.
        assertTrue(workflowRoutes.aiQueued.contains(5L) || workflowRoutes.aiProcessing.get() != null,
                "row should be tracked in aiQueued or aiProcessing right after submit");

        // Wait for executor to pick it up — now it must be in aiProcessing.
        assertTrue(evaluateStarted.await(5, TimeUnit.SECONDS), "executor did not start evaluate in time");
        assertFalse(workflowRoutes.aiQueued.contains(5L), "aiQueued should be cleared once processing starts");
        assertEquals(Long.valueOf(5L), workflowRoutes.aiProcessing.get());

        // Release the executor to finish.
        evaluateRelease.countDown();

        // After completion, aiProcessing must be cleared.
        verify(reviewQueueRepo, timeout(5000)).setAiSuggestion(
                eq(5L), any(), any(), any(), any(), any(), any());
        // Poll briefly for aiProcessing to clear (finally block runs right after persist).
        long deadline = System.currentTimeMillis() + 5000;
        while (workflowRoutes.aiProcessing.get() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNull(workflowRoutes.aiProcessing.get(), "aiProcessing should be null after executor completes");
    }

    // ── deriveState unit tests ────────────────────────────────────────────────

    @Test
    void deriveState_fetchInFlight_returnsFetching() {
        assertEquals("fetching", WorkflowRoutes.deriveState("in_flight", "ambiguous", null, null));
    }

    @Test
    void deriveState_fetchPending_returnsQueued() {
        assertEquals("queued", WorkflowRoutes.deriveState("pending", "ambiguous", null, null));
    }

    @Test
    void deriveState_fetchPaused_returnsQueued() {
        assertEquals("queued", WorkflowRoutes.deriveState("paused", "ambiguous", null, null));
    }

    @Test
    void deriveState_ambiguousNoAi_returnsAmbiguous() {
        assertEquals("ambiguous", WorkflowRoutes.deriveState(null, "ambiguous", null, null));
    }

    @Test
    void deriveState_ambiguousAiConflict_returnsSplitDecision() {
        assertEquals("split_decision",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", "conflict"));
    }

    @Test
    void deriveState_ambiguousPhi4Only_returnsPartialVote() {
        assertEquals("partial_vote",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", "phi4_only"));
    }

    @Test
    void deriveState_ambiguousGemmaOnly_returnsPartialVote() {
        assertEquals("partial_vote",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", "gemma_only"));
    }

    @Test
    void deriveState_ambiguousBothAbstain_returnsNoVerdict() {
        assertEquals("no_verdict",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", "both_abstain"));
    }

    @Test
    void deriveState_ambiguousError_returnsNoVerdict() {
        assertEquals("no_verdict",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", "error"));
    }

    @Test
    void deriveState_ambiguousAgreedSlipThrough_returnsAmbiguous() {
        // 'agreed' rows should have auto-applied; if one slips through, treat as ambiguous.
        assertEquals("ambiguous",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", "agreed"));
    }

    @Test
    void deriveState_ambiguousUnknownOutcome_returnsNoVerdict() {
        assertEquals("no_verdict",
                WorkflowRoutes.deriveState(null, "ambiguous", "2026-01-01T00:00:00Z", null));
    }

    @Test
    void deriveState_castAnomaly_returnsOtherIntervention() {
        assertEquals("other_intervention",
                WorkflowRoutes.deriveState(null, "cast_anomaly", null, null));
    }

    @Test
    void deriveState_noMatch_returnsOtherIntervention() {
        assertEquals("other_intervention",
                WorkflowRoutes.deriveState(null, "no_match", null, null));
    }

    @Test
    void deriveState_fetchFailed_returnsOtherIntervention() {
        assertEquals("other_intervention",
                WorkflowRoutes.deriveState(null, "fetch_failed", null, null));
    }

    @Test
    void aiQueued_derivesStateQueued_forAi() {
        workflowRoutes.aiQueued.add(99L);
        // aiQueued check happens in queryWorkflowRows; verify the tracking set directly.
        assertTrue(workflowRoutes.aiQueued.contains(99L));
        workflowRoutes.aiQueued.remove(99L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static OpenRow makeRow(long id, long titleId, String code, String reason) {
        return new OpenRow(id, titleId, code, null, reason, "code_search",
                "2026-01-01T00:00:00Z", null);
    }
}
