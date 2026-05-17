package com.organizer3.enrichment.ai;

import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EnrichmentAssistSweeper}: mode gating, atomic single-row processing,
 * persistence side-effects, and the error-sentinel invariant that prevents thrown rows
 * from being re-tried on every sweep.
 *
 * <p>Uses a real in-memory SQLite repo (matches {@code EnrichmentReviewQueueRepositoryTest})
 * and a Mockito-stubbed {@link EnsembleAssistCaller}.
 */
class EnrichmentAssistSweeperTest {

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentReviewQueueRepository repo;
    private EnsembleAssistCaller caller;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new EnrichmentReviewQueueRepository(jdbi);
        caller = mock(EnsembleAssistCaller.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    /**
     * Counted/cancellable TaskIO: records phase events and reports cancellation
     * after the configured number of {@code isCancellationRequested()} polls.
     */
    private static final class FakeTaskIO implements TaskIO {
        final AtomicInteger pollCount = new AtomicInteger();
        final int cancelAfterPolls;
        final List<String> logs = new ArrayList<>();
        String summary;
        String status;

        FakeTaskIO(int cancelAfterPolls) { this.cancelAfterPolls = cancelAfterPolls; }

        @Override public void phaseStart(String phaseId, String label) { }
        @Override public void phaseProgress(String phaseId, int current, int total, String detail) { }
        @Override public void phaseLog(String phaseId, String line) { logs.add(line); }
        @Override public void phaseEnd(String phaseId, String status, String summary) {
            this.status = status; this.summary = summary;
        }
        @Override public boolean isCancellationRequested() {
            return pollCount.incrementAndGet() > cancelAfterPolls;
        }
    }

    private EnrichmentAssistConfig configWith(String mode) {
        // sweeperIntervalSeconds=1 so idle-sleep is short; values don't otherwise matter.
        return new EnrichmentAssistConfig(mode, "phi4", "gemma3:12b", 1, 60, "v7-kanji-bridge");
    }

    /** Enqueues an open ambiguous row for an existing title with the given snapshot. */
    private long enqueueAmbiguous(long titleId, String code) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES ("
                        + titleId + ", '" + code + "', 'T', 'T', " + titleId + ")"));
        repo.enqueueWithDetail(titleId, null, "ambiguous", "javdb_search",
                "{\"code\":\"" + code + "\",\"candidates\":[{\"slug\":\"x\"}]}");
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = :t")
                        .bind("t", titleId).mapTo(Long.class).one());
    }

    private Map<String, Object> readAiCols(long rowId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT ai_suggestion_slug, ai_suggestion_confidence,
                               ai_suggestion_reason, ai_suggestion_at
                        FROM enrichment_review_queue WHERE id = :id
                        """)
                        .bind("id", rowId)
                        .map((rs, ctx) -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("slug",       rs.getString("ai_suggestion_slug"));
                            m.put("confidence", rs.getString("ai_suggestion_confidence"));
                            m.put("reason",     rs.getString("ai_suggestion_reason"));
                            m.put("at",         rs.getString("ai_suggestion_at"));
                            return m;
                        })
                        .one());
    }

    // ── 1. mode=off ───────────────────────────────────────────────────────────

    @Test
    void modeOff_exitsImmediately_withoutTouchingRepoOrCaller() {
        long rowId = enqueueAmbiguous(1L, "ABC-1");
        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, caller, configWith("off"));

        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ Integer.MAX_VALUE);
        sweeper.run(new TaskInputs(Map.of()), io);

        assertEquals("ok", io.status);
        verifyNoInteractions(caller);
        Map<String, Object> cols = readAiCols(rowId);
        assertNull(cols.get("at"), "row must not have an AI suggestion written");
    }

    // ── 2. mode=shadow, empty queue, cancellation exits the sleep loop ───────

    @Test
    void modeShadow_emptyQueue_sleepsAndExitsOnCancellation() {
        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, caller, configWith("shadow"));

        // Allow a few cancellation polls so the sweeper enters the idle-sleep slice,
        // then flip to cancelled. With sleeperIntervalSeconds=1 and 250ms slices,
        // this completes in well under a second.
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 3);

        long start = System.currentTimeMillis();
        sweeper.run(new TaskInputs(Map.of()), io);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("ok", io.status, "cancelled sweeper still ends the phase cleanly");
        assertTrue(io.summary.contains("cancelled"), "summary should mention cancellation");
        verifyNoInteractions(caller);
        assertTrue(elapsed < 5000L, "cancellation should be responsive; took " + elapsed + "ms");
    }

    // ── 3. mode=shadow, 3 rows → all get setAiSuggestion ─────────────────────

    @Test
    void modeShadow_processesAllRowsOneAtATime_writesSuggestions() {
        long id1 = enqueueAmbiguous(1L, "ABC-1");
        long id2 = enqueueAmbiguous(2L, "ABC-2");
        long id3 = enqueueAmbiguous(3L, "ABC-3");

        when(caller.evaluate(any(), any(), any())).thenReturn(
                new AssistResult("agreed", "high", "abc-1-alpha", "matches cast", 1, 1),
                new AssistResult("phi4_only", "medium", "abc-2-alpha", "phi4 picks alpha", 1, null),
                new AssistResult("both_abstain", null, null, "no clear winner", null, null)
        );

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, caller, configWith("shadow"));

        // Allow enough cancellation polls that 3 rows process before we cancel during
        // the next idle-sleep. Each row body polls cancellation ~5x (loop entry + the
        // 4x250ms inter-row sleep slices); generous budget keeps this robust.
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 100);
        sweeper.run(new TaskInputs(Map.of()), io);

        // All three rows now have AI suggestions written.
        Map<String, Object> a = readAiCols(id1);
        assertEquals("abc-1-alpha", a.get("slug"));
        assertEquals("agreed",      a.get("confidence"));
        assertEquals("matches cast", a.get("reason"));
        assertNotNull(a.get("at"));

        Map<String, Object> b = readAiCols(id2);
        assertEquals("abc-2-alpha", b.get("slug"));
        assertEquals("phi4_only",   b.get("confidence"));

        Map<String, Object> c = readAiCols(id3);
        assertNull(c.get("slug"), "both_abstain has null suggestedSlug");
        assertEquals("both_abstain", c.get("confidence"));
        assertNotNull(c.get("at"), "even abstain writes the timestamp so the row is not retried");

        verify(caller, times(3)).evaluate(any(), any(), any());
        // After processing, listOpenAwaitingAi must return 0 — the atomic-1 contract held.
        assertTrue(repo.listOpenAwaitingAi(10).isEmpty(),
                "all rows must have ai_suggestion_at set so they drop out of the awaiting-ai list");
    }

    // ── 4. caller throws → sentinel suggestion persisted ──────────────────────

    @Test
    void callerThrows_persistsErrorSentinel_soRowNotRetried() {
        long rowId = enqueueAmbiguous(1L, "ABC-1");

        when(caller.evaluate(any(), any(), any()))
                .thenThrow(new IllegalStateException("zero candidates after parsing"));

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, caller, configWith("shadow"));
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 100);
        sweeper.run(new TaskInputs(Map.of()), io);

        Map<String, Object> cols = readAiCols(rowId);
        assertNull(cols.get("slug"), "error sentinel has no suggested slug");
        assertEquals("error", cols.get("confidence"), "error sentinel marks the column");
        assertNotNull(cols.get("reason"));
        assertTrue(((String) cols.get("reason")).contains("zero candidates"),
                "reason carries the truncated exception message");
        assertNotNull(cols.get("at"), "ai_suggestion_at must be non-null so listOpenAwaitingAi skips it");

        assertTrue(repo.listOpenAwaitingAi(10).isEmpty(),
                "errored row must not re-appear in the awaiting-ai backlog");
    }

    // ── 5. atomic-operations rule: listOpenAwaitingAi(1) ──────────────────────

    @Test
    void listOpenAwaitingAi_alwaysCalledWithLimitOne() {
        // Three pending rows + a mock that records the limit argument used.
        enqueueAmbiguous(1L, "ABC-1");
        enqueueAmbiguous(2L, "ABC-2");

        EnrichmentReviewQueueRepository spied = spy(repo);
        when(caller.evaluate(any(), any(), any()))
                .thenReturn(new AssistResult("agreed", "high", "x", "ok", 1, 1));

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(spied, caller, configWith("shadow"));
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 60);
        sweeper.run(new TaskInputs(Map.of()), io);

        // Every call must pass limit=1 per the atomic-operations rule.
        verify(spied, atLeastOnce()).listOpenAwaitingAi(1);
        verify(spied, never()).listOpenAwaitingAi(0);
        verify(spied, never()).listOpenAwaitingAi(2);
        verify(spied, never()).listOpenAwaitingAi(10);
    }
}
