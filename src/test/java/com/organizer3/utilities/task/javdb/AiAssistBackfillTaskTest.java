package com.organizer3.utilities.task.javdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.enrichment.ai.AssistResult;
import com.organizer3.enrichment.ai.EnsembleAssistCaller;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link AiAssistBackfillTask}. Uses real in-memory SQLite for
 * the review-queue repo and a Mockito-mocked {@link EnsembleAssistCaller}.
 */
class AiAssistBackfillTaskTest {

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository spyRepo;
    private EnsembleAssistCaller caller;
    private ObjectMapper mapper;
    private AiAssistBackfillTask task;

    /** Stable per-title results keyed by titleCode. */
    private final Map<String, AssistResult> scriptedResults = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        spyRepo = spy(new EnrichmentReviewQueueRepository(jdbi));
        caller  = mock(EnsembleAssistCaller.class);
        mapper  = new ObjectMapper();
        task    = new AiAssistBackfillTask(spyRepo, caller, mapper, tempDir);

        when(caller.evaluate(any(EnrichmentReviewQueueRepository.OpenRow.class),
                             any(), anyList()))
                .thenAnswer(inv -> {
                    EnrichmentReviewQueueRepository.OpenRow row = inv.getArgument(0);
                    AssistResult r = scriptedResults.get(row.titleCode());
                    if (r == null) {
                        throw new IllegalStateException("no scripted result for " + row.titleCode());
                    }
                    return r;
                });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /** Insert a resolved ambiguous queue row pointing at {@code titleId}. */
    private long insertResolvedAmbiguous(long titleId) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO enrichment_review_queue
                            (title_id, reason, detail, resolved_at, resolution)
                        VALUES (:t, 'ambiguous', :d, :r, 'picked')
                        """)
                        .bind("t", titleId)
                        .bind("d", "{\"candidates\":[{\"slug\":\"x\"}]}")
                        .bind("r", Instant.now().toString())
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void scriptResult(String code, String outcome, String slug) {
        scriptedResults.put(code, new AssistResult(
                outcome,
                outcome.equals("agreed") || outcome.equals("agreed_with_override") ? "high" : null,
                slug,
                "scripted",
                slug != null ? 1 : null,
                slug != null && outcome.equals("agreed") ? 1 : null));
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @Test
    void backfill_recordsCountsMatchesAndMismatches() throws Exception {
        // 3 rows where ensemble agrees with the human:
        long t1 = insertTitle("ABC-001"); insertEnrichment(t1, "slug-1"); long q1 = insertResolvedAmbiguous(t1);
        long t2 = insertTitle("ABC-002"); insertEnrichment(t2, "slug-2"); long q2 = insertResolvedAmbiguous(t2);
        long t3 = insertTitle("ABC-003"); insertEnrichment(t3, "slug-3"); long q3 = insertResolvedAmbiguous(t3);
        scriptResult("ABC-001", "agreed", "slug-1");
        scriptResult("ABC-002", "agreed", "slug-2");
        scriptResult("ABC-003", "agreed", "slug-3");

        // 1 conflict (no slug — counted as n=1 in conflict bucket, matches=0):
        long t4 = insertTitle("ABC-004"); insertEnrichment(t4, "slug-4"); long q4 = insertResolvedAmbiguous(t4);
        scriptResult("ABC-004", "conflict", null);

        // 1 mismatch (phi4_only picks a different slug than ground truth):
        long t5 = insertTitle("ABC-005"); insertEnrichment(t5, "slug-5-truth"); long q5 = insertResolvedAmbiguous(t5);
        scriptResult("ABC-005", "phi4_only", "slug-5-WRONG");

        CapturingIO io = new CapturingIO();
        task.run(new TaskInputs(Map.of()), io);

        // setAiSuggestion called exactly 5 times — once per row.
        verify(spyRepo, times(5)).setAiSuggestion(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                anyString(),
                org.mockito.ArgumentMatchers.any(),
                any(Instant.class));

        // evaluate called 5 times.
        verify(caller, times(5)).evaluate(any(EnrichmentReviewQueueRepository.OpenRow.class),
                                          any(), anyList());

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
        assertEquals("slug-5-WRONG", mm.path("ai_slug").asText());
        assertEquals("slug-5-truth", mm.path("ground_truth_slug").asText());

        // Sanity: phase ended OK.
        assertTrue(io.endStatuses.contains("ok"));
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
}
