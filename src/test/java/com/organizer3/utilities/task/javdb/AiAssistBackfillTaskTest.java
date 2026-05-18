package com.organizer3.utilities.task.javdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.enrichment.ai.PostProcessingRules;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for the Phase 5 Track A batched backfill task. Uses an in-memory
 * SQLite repo and a mocked {@link OllamaModelOrchestrator}; the
 * {@link PostProcessingRules} layer is constructed disabled so rules can't perturb
 * scripted outcomes.
 */
class AiAssistBackfillTaskTest {

    private static final String PHI4  = "phi4";
    private static final String GEMMA = "gemma3:12b";

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository spyRepo;
    private OllamaModelOrchestrator orchestrator;
    private ObjectMapper mapper;
    private PostProcessingRules postProcessing;

    /** Scripted JSON-reply text keyed by (model, titleCode). */
    private final Map<String, String> scripted = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        spyRepo        = spy(new EnrichmentReviewQueueRepository(jdbi));
        orchestrator   = mock(OllamaModelOrchestrator.class);
        mapper         = new ObjectMapper();
        postProcessing = new PostProcessingRules(false);

        // Default: respond based on the scripted-reply table; the request's model and
        // prompt body together identify the row (the prompt embeds the title code).
        when(orchestrator.submit(anyString(), any(OllamaRequest.class)))
                .thenAnswer(inv -> {
                    String model = inv.getArgument(0);
                    OllamaRequest req = inv.getArgument(1);
                    String code = extractCodeFromPrompt(req.prompt());
                    String key = model + "|" + code;
                    String reply = scripted.getOrDefault(key, pickJson(null, "low", "no-script"));
                    return CompletableFuture.completedFuture(
                            new OllamaResponse(reply, 0L, 0, 0, 0L));
                });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    private AiAssistBackfillTask buildTask(int batchSize) {
        EnrichmentAssistConfig cfg = new EnrichmentAssistConfig(
                "off", PHI4, GEMMA, 60, 60, "v7-kanji-bridge", 3, false, batchSize);
        return new AiAssistBackfillTask(spyRepo, orchestrator, cfg, postProcessing, mapper, tempDir);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO titles (code, base_code, label, seq_num)
                        VALUES (:c, :c, 'TST', 1)
                        """)
                        .bind("c", code)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void insertEnrichment(long titleId, String slug) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, ?, ?)",
                titleId, slug, Instant.now().toString()));
    }

    /** Insert a resolved ambiguous queue row with the given 2-candidate snapshot. */
    private long insertResolvedAmbiguous(long titleId, String code, String slug1, String slug2) {
        String detail = ("{\"code\":\"" + code + "\","
                + "\"linked_slugs\":[],"
                + "\"candidates\":["
                + "{\"slug\":\"" + slug1 + "\",\"title_original\":\"T1\"},"
                + "{\"slug\":\"" + slug2 + "\",\"title_original\":\"T2\"}"
                + "]}");
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO enrichment_review_queue
                            (title_id, reason, detail, resolved_at, resolution)
                        VALUES (:t, 'ambiguous', :d, :r, 'picked')
                        """)
                        .bind("t", titleId)
                        .bind("d", detail)
                        .bind("r", Instant.now().toString())
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    /** Convenience to set up a full row (title + enrichment + queue) with default 2 candidates. */
    private long[] setupRow(String code, String groundTruth) {
        long t = insertTitle(code);
        insertEnrichment(t, groundTruth);
        // Always emit 2 candidates: alpha + beta; let scripts choose by index.
        long q = insertResolvedAmbiguous(t, code, code.toLowerCase() + "-a", code.toLowerCase() + "-b");
        return new long[]{t, q};
    }

    private void script(String model, String code, Integer pick, String confidence, String reason) {
        scripted.put(model + "|" + code, pickJson(pick, confidence, reason));
    }

    private static String pickJson(Integer pick, String confidence, String reason) {
        String pickPart = (pick == null) ? "null" : pick.toString();
        return "{\"pick\":" + pickPart
                + ",\"confidence\":\"" + (confidence == null ? "low" : confidence) + "\""
                + ",\"reason\":\"" + (reason == null ? "" : reason) + "\"}";
    }

    /** Pull the title code back out of the user prompt; the prompt embeds "Product code: XYZ". */
    private static String extractCodeFromPrompt(String prompt) {
        if (prompt == null) return "";
        for (String line : prompt.split("\\R")) {
            String l = line.trim();
            if (l.startsWith("Product code:")) {
                return l.substring("Product code:".length()).trim();
            }
        }
        return "";
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void backfill_recordsCountsMatchesAndMismatches() throws Exception {
        // 3 rows where ensemble agrees with the human:
        long[] r1 = setupRow("ABC-001", "abc-001-a");
        long[] r2 = setupRow("ABC-002", "abc-002-a");
        long[] r3 = setupRow("ABC-003", "abc-003-a");
        for (String c : new String[]{"ABC-001", "ABC-002", "ABC-003"}) {
            script(PHI4,  c, 1, "high", "match");
            script(GEMMA, c, 1, "high", "match");
        }

        // 1 conflict (no slug — counted in conflict bucket):
        long[] r4 = setupRow("ABC-004", "abc-004-a");
        script(PHI4,  "ABC-004", 1, "medium", "alpha");
        script(GEMMA, "ABC-004", 2, "high",   "beta");

        // 1 mismatch (phi4 picks slug-2; ground truth is slug-1):
        long[] r5 = setupRow("ABC-005", "abc-005-a");
        script(PHI4,  "ABC-005", 2, "high", "ph picks beta");
        script(GEMMA, "ABC-005", null, "low", "gemma abstains");
        long q5 = r5[1];

        AiAssistBackfillTask task = buildTask(/* batchSize */ 5);
        CapturingIO io = new CapturingIO();
        task.run(new TaskInputs(Map.of()), io);

        // setAiSuggestion called exactly 5 times — once per row.
        verify(spyRepo, times(5)).setAiSuggestion(
                anyLong(), any(), anyString(), any(), any(Instant.class));

        // Orchestrator received exactly 5 phi4 submissions and 5 gemma3 submissions
        // (single chunk of 5).
        verify(orchestrator, times(5)).submit(eq(PHI4),  any(OllamaRequest.class));
        verify(orchestrator, times(5)).submit(eq(GEMMA), any(OllamaRequest.class));

        // Report exists and has expected shape.
        List<Path> reports;
        try (var stream = Files.list(tempDir)) {
            reports = stream.filter(p -> p.getFileName().toString()
                    .startsWith("ai-assist-backfill-")).toList();
        }
        assertEquals(1, reports.size(), "exactly one report file written");
        JsonNode root = mapper.readTree(reports.get(0).toFile());

        assertEquals(5, root.path("processed").asInt());
        assertNotNull(root.path("completed_at").asText());
        assertEquals(false, root.path("cancelled").asBoolean());

        JsonNode byOutcome = root.path("by_outcome");
        assertEquals(3, byOutcome.path("agreed").path("n").asInt());
        assertEquals(3, byOutcome.path("agreed").path("matches").asInt());
        assertEquals(1, byOutcome.path("phi4_only").path("n").asInt());
        assertEquals(0, byOutcome.path("phi4_only").path("matches").asInt());
        assertEquals(1, byOutcome.path("conflict").path("n").asInt());
        assertEquals(0, byOutcome.path("conflict").path("matches").asInt());
        assertEquals(0, byOutcome.path("both_abstain").path("n").asInt());
        assertEquals(0, byOutcome.path("error").path("n").asInt());

        // match_rate_on_picked: 3 matches / (3 agreed + 1 phi4_only) = 0.75
        assertEquals(0.75, root.path("match_rate_on_picked").asDouble(), 0.0001);

        // Mismatches list: exactly one entry (the wrong slug from ABC-005).
        JsonNode mismatches = root.path("mismatches");
        assertTrue(mismatches.isArray(), "mismatches must be an array");
        assertEquals(1, mismatches.size(), "exactly one mismatch entry");
        JsonNode mm = mismatches.get(0);
        assertEquals(q5, mm.path("queue_row_id").asLong());
        assertEquals("ABC-005", mm.path("title_code").asText());
        assertEquals("abc-005-b", mm.path("ai_slug").asText());
        assertEquals("abc-005-a", mm.path("ground_truth_slug").asText());

        assertTrue(io.endStatuses.contains("ok"));
    }

    /**
     * Phase 5 Track A — 7 rows + batchSize=3 produces chunks of 3+3+1. The orchestrator
     * must receive exactly 7 phi4 submissions and 7 gemma3 submissions in total, and
     * (because each chunk submits all phi4 before any gemma3) the phi4 calls for chunk N
     * complete before any gemma3 calls for chunk N. We assert the totals plus the
     * chunk-boundary log lines.
     */
    @Test
    void backfill_batchedByModel_threeChunksFor7RowsBatch3() throws Exception {
        for (int i = 1; i <= 7; i++) {
            String code = String.format("ABC-%03d", i);
            setupRow(code, code.toLowerCase() + "-a");
            script(PHI4,  code, 1, "high", "ok");
            script(GEMMA, code, 1, "high", "ok");
        }

        AiAssistBackfillTask task = buildTask(/* batchSize */ 3);
        CapturingIO io = new CapturingIO();
        task.run(new TaskInputs(Map.of()), io);

        verify(orchestrator, times(7)).submit(eq(PHI4),  any(OllamaRequest.class));
        verify(orchestrator, times(7)).submit(eq(GEMMA), any(OllamaRequest.class));

        // Three chunks → three "Batch chunk=" log lines.
        long chunkLogs = io.logs.stream()
                .filter(l -> l.startsWith("Batch chunk="))
                .count();
        assertEquals(3, chunkLogs, "expected 3 chunk-boundary log lines for 7 rows / batch 3");

        // All 7 setAiSuggestion calls.
        verify(spyRepo, times(7)).setAiSuggestion(
                anyLong(), any(), anyString(), any(), any(Instant.class));
    }

    /**
     * Phase 5 Track A — cancellation between chunks halts further processing, but the
     * partial report still gets written and no crash occurs.
     */
    @Test
    void backfill_cancellationBetweenChunks_writesPartialReport() throws Exception {
        for (int i = 1; i <= 5; i++) {
            String code = String.format("ABC-%03d", i);
            setupRow(code, code.toLowerCase() + "-a");
            script(PHI4,  code, 1, "high", "ok");
            script(GEMMA, code, 1, "high", "ok");
        }

        AiAssistBackfillTask task = buildTask(/* batchSize */ 2);

        // Cancel after the FIRST chunk completes (2 rows processed).
        CancelAfterIO io = new CancelAfterIO(/* cancelAfterNCalls */ 2);
        task.run(new TaskInputs(Map.of()), io);

        // First chunk of 2 rows processed fully; second chunk's pre-check triggers cancel.
        verify(orchestrator, times(2)).submit(eq(PHI4),  any(OllamaRequest.class));
        verify(orchestrator, times(2)).submit(eq(GEMMA), any(OllamaRequest.class));

        List<Path> reports;
        try (var stream = Files.list(tempDir)) {
            reports = stream.filter(p -> p.getFileName().toString()
                    .startsWith("ai-assist-backfill-")).toList();
        }
        assertEquals(1, reports.size());
        JsonNode root = mapper.readTree(reports.get(0).toFile());
        assertEquals(true, root.path("cancelled").asBoolean());
        assertEquals(2, root.path("processed").asInt());
        assertTrue(io.endStatuses.contains("ok"));
    }

    /**
     * Phase 5 Track A — when a phi4 future completes exceptionally, the row is treated
     * as a phi4 abstain (gemma_only or both_abstain). The batch continues; no row
     * poisons another.
     */
    @Test
    void backfill_phi4FutureFailure_abstainsForThatRow_batchContinues() throws Exception {
        long[] r1 = setupRow("ABC-001", "abc-001-a");
        long[] r2 = setupRow("ABC-002", "abc-002-a");
        long[] r3 = setupRow("ABC-003", "abc-003-a");

        // Row 1 + Row 3 = ordinary agree. Row 2 = phi4 fails, gemma picks index 1.
        script(PHI4,  "ABC-001", 1, "high", "ok");
        script(GEMMA, "ABC-001", 1, "high", "ok");
        script(GEMMA, "ABC-002", 1, "high", "gemma picks slug-1");
        script(PHI4,  "ABC-003", 1, "high", "ok");
        script(GEMMA, "ABC-003", 1, "high", "ok");

        // Custom phi4 stub that fails the ABC-002 future.
        when(orchestrator.submit(eq(PHI4), any(OllamaRequest.class)))
                .thenAnswer(inv -> {
                    OllamaRequest req = inv.getArgument(1);
                    String code = extractCodeFromPrompt(req.prompt());
                    if ("ABC-002".equals(code)) {
                        CompletableFuture<OllamaResponse> f = new CompletableFuture<>();
                        f.completeExceptionally(new RuntimeException("simulated phi4 outage"));
                        return f;
                    }
                    String reply = scripted.getOrDefault(PHI4 + "|" + code,
                            pickJson(null, "low", "no-script"));
                    return CompletableFuture.completedFuture(
                            new OllamaResponse(reply, 0L, 0, 0, 0L));
                });

        AiAssistBackfillTask task = buildTask(/* batchSize */ 5);
        CapturingIO io = new CapturingIO();
        task.run(new TaskInputs(Map.of()), io);

        // All 3 persisted.
        verify(spyRepo, times(3)).setAiSuggestion(
                anyLong(), any(), anyString(), any(), any(Instant.class));

        // Report: 2 agreed + 1 gemma_only.
        List<Path> reports;
        try (var stream = Files.list(tempDir)) {
            reports = stream.filter(p -> p.getFileName().toString()
                    .startsWith("ai-assist-backfill-")).toList();
        }
        JsonNode root = mapper.readTree(reports.get(0).toFile());
        JsonNode byOutcome = root.path("by_outcome");
        assertEquals(2, byOutcome.path("agreed").path("n").asInt());
        assertEquals(1, byOutcome.path("gemma_only").path("n").asInt());
        // The gemma_only row matched truth (gemma picked slug-1 = abc-002-a).
        assertEquals(1, byOutcome.path("gemma_only").path("matches").asInt());
    }

    // ── CapturingIO ───────────────────────────────────────────────────────────

    static class CapturingIO implements TaskIO {
        final List<String> logs         = new ArrayList<>();
        final List<String> endSummaries = new ArrayList<>();
        final List<String> endStatuses  = new ArrayList<>();

        @Override public void phaseStart(String id, String label) {}
        @Override public void phaseProgress(String id, int current, int total, String detail) {}
        @Override public void phaseLog(String id, String line) { logs.add(line); }
        @Override public void phaseEnd(String id, String status, String summary) {
            endSummaries.add(summary);
            endStatuses.add(status);
        }
        @Override public boolean isCancellationRequested() { return false; }
    }

    /** Returns {@code true} from {@code isCancellationRequested()} after N invocations. */
    static class CancelAfterIO extends CapturingIO {
        private final int n;
        private int calls = 0;
        CancelAfterIO(int cancelAfterNCalls) { this.n = cancelAfterNCalls; }
        @Override public boolean isCancellationRequested() {
            return ++calls > n;
        }
    }
}
