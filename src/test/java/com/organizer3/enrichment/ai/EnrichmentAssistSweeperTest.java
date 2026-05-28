package com.organizer3.enrichment.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EnrichmentAssistSweeper}: mode gating, batched Phase-A delegation to the
 * {@link BatchedEnsembleProcessor}, and the soak-gated Phase-B auto-apply path.
 *
 * <p>The sweeper no longer talks to the ensemble directly — Phase A delegates to a mocked
 * {@link BatchedEnsembleProcessor} (which owns persistence + per-row error sentinels; those
 * are covered by {@code BatchedEnsembleProcessorTest}). Phase B (auto-apply of aged agreed
 * suggestions) is exercised against a real in-memory SQLite repo + a mocked
 * {@link EnrichmentAutoApplier}.
 */
class EnrichmentAssistSweeperTest {

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentReviewQueueRepository repo;
    private BatchedEnsembleProcessor batchedProcessor;
    private EnrichmentAutoApplier autoApplier;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new EnrichmentReviewQueueRepository(jdbi);
        batchedProcessor = mock(BatchedEnsembleProcessor.class);
        autoApplier = mock(EnrichmentAutoApplier.class);
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
        // sweeperIntervalSeconds=1 so idle-sleep is short; sweeperBatchSize=10 (default).
        return new EnrichmentAssistConfig(mode, "phi4", "gemma3:12b", 1, 60, "v7-kanji-bridge", 3, true, 20, 10);
    }

    private EnrichmentAssistConfig configWith(String mode, int autoApplyDelaySeconds) {
        return new EnrichmentAssistConfig(mode, "phi4", "gemma3:12b", 1, autoApplyDelaySeconds, "v7-kanji-bridge", 3, true, 20, 10);
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
                               ai_suggestion_reason, ai_suggestion_at,
                               ai_phi4_slug, ai_gemma_slug
                        FROM enrichment_review_queue WHERE id = :id
                        """)
                        .bind("id", rowId)
                        .map((rs, ctx) -> {
                            Map<String, Object> m = new java.util.HashMap<>();
                            m.put("slug",       rs.getString("ai_suggestion_slug"));
                            m.put("confidence", rs.getString("ai_suggestion_confidence"));
                            m.put("reason",     rs.getString("ai_suggestion_reason"));
                            m.put("at",         rs.getString("ai_suggestion_at"));
                            m.put("phi4Slug",   rs.getString("ai_phi4_slug"));
                            m.put("gemmaSlug",  rs.getString("ai_gemma_slug"));
                            return m;
                        })
                        .one());
    }

    /** Convenience for stubbing the batched processor's 4-arg process(...). */
    private void stubProcess(int processed, int errors) {
        when(batchedProcessor.process(anyList(), any(), any(), anyInt()))
                .thenReturn(new BatchedEnsembleProcessor.ProcessingResult(processed, 0, 0, errors));
    }

    // ── 1. mode=off ───────────────────────────────────────────────────────────

    @Test
    void modeOff_exitsImmediately_withoutTouchingRepoOrProcessor() {
        long rowId = enqueueAmbiguous(1L, "ABC-1");
        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("off"), autoApplier);

        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ Integer.MAX_VALUE);
        sweeper.run(new TaskInputs(Map.of()), io);

        assertEquals("ok", io.status);
        verifyNoInteractions(batchedProcessor);
        Map<String, Object> cols = readAiCols(rowId);
        assertNull(cols.get("at"), "row must not have an AI suggestion written");
    }

    // ── 2. mode=shadow, empty queue, cancellation exits the sleep loop ───────

    @Test
    void modeShadow_emptyQueue_sleepsAndExitsOnCancellation() {
        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("shadow"), autoApplier);

        // Allow a few cancellation polls so the sweeper enters the idle-sleep slice,
        // then flip to cancelled. With sleeperIntervalSeconds=1 and 250ms slices,
        // this completes in well under a second.
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 3);

        long start = System.currentTimeMillis();
        sweeper.run(new TaskInputs(Map.of()), io);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("ok", io.status, "cancelled sweeper still ends the phase cleanly");
        assertTrue(io.summary.contains("cancelled"), "summary should mention cancellation");
        verifyNoInteractions(batchedProcessor);
        assertTrue(elapsed < 5000L, "cancellation should be responsive; took " + elapsed + "ms");
    }

    // ── 3. mode=shadow, pending rows → sweeper delegates to the batched processor ──

    @Test
    void modeShadow_pendingRows_delegatesToBatchedProcessor() {
        enqueueAmbiguous(1L, "ABC-1");
        enqueueAmbiguous(2L, "ABC-2");
        enqueueAmbiguous(3L, "ABC-3");

        stubProcess(/* processed */ 3, /* errors */ 0);

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("shadow"), autoApplier);

        // First iteration: rows present → process(...) once. Cancel quickly so we don't loop
        // forever (the mocked processor doesn't clear ai_suggestion_at, so rows stay pending).
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 2);
        sweeper.run(new TaskInputs(Map.of()), io);

        // The sweeper delegated the whole batch to the processor with the configured chunk size (10).
        verify(batchedProcessor, atLeastOnce())
                .process(anyList(), any(), any(), eq(10));
        assertEquals("ok", io.status);
    }

    // ── 4. Phase A: sweeper requests a batch (limit = sweeperBatchSize), never limit 0 ──

    @Test
    void modeShadow_phaseA_requestsBatchSizedQueue() {
        enqueueAmbiguous(1L, "ABC-1");
        enqueueAmbiguous(2L, "ABC-2");

        stubProcess(2, 0);

        EnrichmentReviewQueueRepository spied = spy(repo);
        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(spied, batchedProcessor, configWith("shadow"), autoApplier);
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 2);
        sweeper.run(new TaskInputs(Map.of()), io);

        // Phase A pulls a batch sized by sweeperBatchSize (default 10), never 0.
        verify(spied, atLeastOnce()).listOpenAwaitingAi(10);
        verify(spied, never()).listOpenAwaitingAi(0);
    }

    // ── 5. Phase A survives a processor exception (does not crash the sweeper loop) ──

    @Test
    void modeShadow_processorThrows_sweeperSurvivesAndEndsCleanly() {
        enqueueAmbiguous(1L, "ABC-1");
        when(batchedProcessor.process(anyList(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("processor boom"));

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("shadow"), autoApplier);
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 5);

        // A process()-level throw must NOT kill the long-lived sweeper (parity with the prior
        // per-row try/catch behaviour). The task ends cleanly via cancellation, not by throwing.
        sweeper.run(new TaskInputs(Map.of()), io);

        assertEquals("ok", io.status, "sweeper survives a processor throw and ends the phase cleanly");
        verify(batchedProcessor, atLeastOnce()).process(anyList(), any(), any(), anyInt());
    }

    // ── 6. J1 — daily-summary line emitted at UTC day rollover ──────────────────

    /** Mutable Clock that exposes setNow() for test-driven advancement. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void setNow(Instant t) { this.now = t; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void recordOutcome_atUtcDayRollover_emitsDailySummaryWithPriorDayCounts() {
        // Start at noon on day A so we have a clean UTC date.
        Instant dayA = Instant.parse("2026-05-17T12:00:00Z");
        MutableClock clock = new MutableClock(dayA);

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("shadow"), autoApplier, clock);

        Logger sweeperLogger = (Logger) LoggerFactory.getLogger(EnrichmentAssistSweeper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sweeperLogger.addAppender(appender);
        Level prior = sweeperLogger.getLevel();
        sweeperLogger.setLevel(Level.INFO);
        try {
            // 3 outcomes on day A: 2 agreed + 1 conflict.
            sweeper.recordOutcome("agreed");
            sweeper.recordOutcome("agreed");
            sweeper.recordOutcome("conflict");
            // No summary yet — still day A.
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("daily summary")),
                    "no rollover yet, no summary should have been emitted");
            assertEquals(3, sweeper.processedTodayForTest());

            // Advance to day B (just past midnight UTC), then record 2 more outcomes.
            // The first record() call on day B is what triggers the prior-day summary flush.
            clock.setNow(dayA.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES));
            sweeper.recordOutcome("both_abstain");
            sweeper.recordOutcome("error");

            // Exactly one daily-summary line should have been emitted, with day-A counts.
            List<String> summaryLines = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("daily summary"))
                    .toList();
            assertEquals(1, summaryLines.size(),
                    "exactly one daily-summary line should have been emitted on rollover");
            String line = summaryLines.get(0);
            assertTrue(line.contains("processed=3"),  "expected processed=3 in: " + line);
            assertTrue(line.contains("agreed=2"),     "expected agreed=2 in: "    + line);
            assertTrue(line.contains("conflict=1"),   "expected conflict=1 in: "  + line);
            assertTrue(line.contains("phi4_only=0"),  "expected phi4_only=0 in: " + line);
            assertTrue(line.contains("gemma_only=0"), "expected gemma_only=0 in: "+ line);
            assertTrue(line.contains("both_abstain=0"), "day-A summary should NOT include day-B counts: " + line);
            assertTrue(line.contains("error=0"),      "day-A summary should NOT include day-B error count: " + line);

            // Counters were reset and now reflect day-B outcomes only.
            assertEquals(2, sweeper.processedTodayForTest());
            assertEquals(Integer.valueOf(1), sweeper.outcomeCountsForTest().get("both_abstain"));
            assertEquals(Integer.valueOf(1), sweeper.outcomeCountsForTest().get("error"));
        } finally {
            sweeperLogger.detachAppender(appender);
            sweeperLogger.setLevel(prior);
        }
    }

    // ── Phase B (auto-apply) tests ────────────────────────────────────────────

    /**
     * Enqueues an ambiguous row, then writes an "agreed" AI suggestion with the given
     * timestamp so {@code listAutoApplyReady} (with minAgeSeconds=0) will pick it up.
     * Returns the queue row id.
     */
    private long enqueueAgedAgreed(long titleId, String code, String slug) {
        long rowId = enqueueAmbiguous(titleId, code);
        // Use a past timestamp so age requirements are trivially satisfied.
        repo.setAiSuggestion(rowId, slug, "agreed", "models agree", Instant.parse("2026-01-01T00:00:00Z"));
        return rowId;
    }

    @Test
    void modeAuto_noPendingWrite_oneAgedAgreed_invokesAutoApplier() {
        long rowId = enqueueAgedAgreed(1L, "ABC-1", "abc-1-alpha");
        when(autoApplier.apply(any())).thenReturn(true);

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("auto", 0), autoApplier);
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 30);
        sweeper.run(new TaskInputs(Map.of()), io);

        verify(autoApplier, atLeastOnce()).apply(argThat(r -> r != null && r.id() == rowId));
        // Phase A had nothing to process (ai_suggestion_at already set) → processor untouched.
        verifyNoInteractions(batchedProcessor);
        // The mock does not flip ai_auto_applied, so the same row may be re-picked across
        // iterations until cancellation. Assert at least one success was recorded.
        assertTrue(sweeper.outcomeCountsForTest().getOrDefault("auto_applied", 0) >= 1,
                "expected at least one auto_applied outcome, got: " + sweeper.outcomeCountsForTest());
    }

    @Test
    void modeAuto_bothQueuesNonEmpty_phaseAWins_phaseBDoesNotRun() {
        // One pending suggestion to write AND one aged agreed row in different rows.
        enqueueAmbiguous(1L, "ABC-1");
        enqueueAgedAgreed(2L, "ABC-2", "abc-2-alpha");

        stubProcess(1, 0);

        // Cancel after one Phase A iteration so Phase B never gets a chance. Phase A delegates
        // to the (mocked) processor — zero polls consumed there — so the next top-of-loop check
        // (and the inter-row sleep before it) must trip cancellation.
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 1);

        EnrichmentReviewQueueRepository spied = spy(repo);
        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(spied, batchedProcessor, configWith("auto", 0), autoApplier);
        sweeper.run(new TaskInputs(Map.of()), io);

        // Phase A ran (processor invoked). listAutoApplyReady never consulted because Phase A's
        // continue short-circuited then cancellation tripped.
        verify(batchedProcessor, atLeastOnce()).process(anyList(), any(), any(), anyInt());
        verify(spied, never()).listAutoApplyReady(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(autoApplier);
    }

    @Test
    void modeShadow_withAgedAgreedRow_doesNotInvokeAutoApplier() {
        enqueueAgedAgreed(1L, "ABC-1", "abc-1-alpha");

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("shadow"), autoApplier);
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 30);
        sweeper.run(new TaskInputs(Map.of()), io);

        // Phase B gated to auto mode only. Phase A had nothing pending (ai_suggestion_at set).
        verifyNoInteractions(autoApplier);
        verifyNoInteractions(batchedProcessor);
    }

    @Test
    void modeAuto_autoApplierReturnsFalse_recordsAutoApplyFailedOutcome() {
        enqueueAgedAgreed(1L, "ABC-1", "abc-1-alpha");
        when(autoApplier.apply(any())).thenReturn(false);

        EnrichmentAssistSweeper sweeper =
                new EnrichmentAssistSweeper(repo, batchedProcessor, configWith("auto", 0), autoApplier);
        FakeTaskIO io = new FakeTaskIO(/* cancelAfterPolls */ 30);
        sweeper.run(new TaskInputs(Map.of()), io);

        verify(autoApplier, atLeastOnce()).apply(any());
        assertNull(sweeper.outcomeCountsForTest().get("auto_applied"),
                "no successful applies should be recorded");
        assertTrue(sweeper.outcomeCountsForTest().getOrDefault("auto_apply_failed", 0) >= 1,
                "at least one auto_apply_failed outcome should be recorded");
    }
}
