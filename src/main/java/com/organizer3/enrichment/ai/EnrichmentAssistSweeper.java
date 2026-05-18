package com.organizer3.enrichment.ai;

import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.AssistContext;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wave 3 Track G — long-lived utility task that drains
 * {@code enrichment_review_queue} rows awaiting an AI suggestion.
 *
 * <p>Per iteration: pulls a single open ambiguous row via
 * {@link EnrichmentReviewQueueRepository#listOpenAwaitingAi(int) listOpenAwaitingAi(1)}
 * (atomic — one row at a time per the Utilities task-runner rule), enriches with
 * folder path + actress hints via {@link EnrichmentReviewQueueRepository#findContextForAssist(long)},
 * asks the {@link EnsembleAssistCaller}, and writes the result to the four
 * {@code ai_suggestion_*} columns.
 *
 * <p><b>Phase 3 scope</b>: when {@link EnrichmentAssistConfig#mode() mode}={@code auto},
 * each iteration also drains a single aged agreed suggestion via
 * {@link EnrichmentReviewQueueRepository#listAutoApplyReady} and applies it through
 * {@link EnrichmentAutoApplier}. Phase A (write a new suggestion) wins over Phase B
 * (auto-apply) when both queues have work, so the writer keeps moving and the applier
 * processes on the trailing edge. In {@code shadow} / {@code suggest} modes only
 * Phase A runs; on {@code off} the sweeper exits.
 *
 * <p><b>Failure handling</b>: if the caller throws (e.g.
 * {@link IllegalStateException} for a row with zero candidates), we still write a
 * sentinel suggestion with {@code confidence="error"} so the row will not be re-tried
 * on every sweep — {@link EnrichmentReviewQueueRepository#listOpenAwaitingAi}
 * filters on {@code ai_suggestion_at IS NULL}, so a non-null timestamp removes it
 * from future iterations until a human / reset clears the columns.
 *
 * <p>Implements the existing {@link Task} interface; cancellation is polled between
 * rows and inside the sleep loop via {@link TaskIO#isCancellationRequested()}.
 */
@Slf4j
public final class EnrichmentAssistSweeper implements Task {

    public static final String ID = "enrichment.ai_assist_sweeper";

    /** Hardcoded for Phase 1 — see report; not exposed as a config knob. */
    static final long INTER_ROW_DELAY_MS = 1000L;

    /** Cap on the reason/error string persisted to the suggestion column. */
    static final int REASON_MAX_LEN = 240;

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "AI assist — review queue sweeper",
            "Continuously drains open ambiguous review-queue rows by submitting them to the "
                    + "two-model ensemble (phi4 + gemma3) and recording the suggestion. In mode=auto "
                    + "also auto-applies aged agreed suggestions via EnrichmentAutoApplier. Sweeper "
                    + "exits cleanly when mode=off or the task is cancelled.",
            List.of()
    );

    private final EnrichmentReviewQueueRepository queueRepo;
    private final EnsembleAssistCaller caller;
    private final EnrichmentAssistConfig config;
    private final EnrichmentAutoApplier autoApplier;
    private final Clock clock;

    // Daily-summary rollup state — updated on the sweeper thread only, so no synchronization needed.
    // Outcome keys mirror EnsembleAssistCaller.vote(): agreed, phi4_only, gemma_only, conflict, both_abstain, error.
    private final Map<String, Integer> outcomeCounts = new LinkedHashMap<>();
    private int processedToday = 0;
    private LocalDate dayBucketStart = null;

    public EnrichmentAssistSweeper(EnrichmentReviewQueueRepository queueRepo,
                                   EnsembleAssistCaller caller,
                                   EnrichmentAssistConfig config,
                                   EnrichmentAutoApplier autoApplier) {
        this(queueRepo, caller, config, autoApplier, Clock.systemUTC());
    }

    /** Test-friendly constructor: inject a Clock so daily-rollup boundaries can be advanced. */
    public EnrichmentAssistSweeper(EnrichmentReviewQueueRepository queueRepo,
                                   EnsembleAssistCaller caller,
                                   EnrichmentAssistConfig config,
                                   EnrichmentAutoApplier autoApplier,
                                   Clock clock) {
        this.queueRepo   = Objects.requireNonNull(queueRepo, "queueRepo");
        this.caller      = Objects.requireNonNull(caller, "caller");
        this.config      = Objects.requireNonNull(config, "config");
        this.autoApplier = Objects.requireNonNull(autoApplier, "autoApplier");
        this.clock       = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        // Mode gate — defensive; the UI shouldn't start the sweeper when mode=off.
        if ("off".equals(config.mode())) {
            log.info("[ai-assist] mode=off, sweeper exiting");
            io.phaseStart("sweep", "AI assist sweeper");
            io.phaseEnd("sweep", "ok", "mode=off — sweeper exited without work");
            return;
        }

        int intervalSec = Math.max(1, config.sweeperIntervalSeconds());
        io.phaseStart("sweep", "AI assist sweeper (mode=" + config.mode() + ")");

        int processed = 0;
        int errors    = 0;
        int autoApplied = 0;
        int autoApplyFailed = 0;

        // NOTE: Reads config.mode() per iteration so a live-swap of the config reference
        // would take effect immediately. However, EnrichmentAssistConfig is loaded
        // once at boot today; flipping mode in YAML requires app restart. To pause
        // auto-apply without restart, cancel this task via the Utilities task runner.
        while (!io.isCancellationRequested()) {
            // PHASE A — write a new suggestion for an open ambiguous row.
            // One row at a time per the atomic-operations rule.
            List<OpenRow> rows = queueRepo.listOpenAwaitingAi(1);

            if (!rows.isEmpty()) {
                OpenRow row = rows.get(0);
                ProcessOutcome po = processOneRow(row, io);
                if (po == ProcessOutcome.ERROR) errors++; else processed++;
                if (!sleepInterruptible(INTER_ROW_DELAY_MS, io)) break;
                continue;
            }

            // PHASE B — apply an aged agreed suggestion (mode=auto only).
            if ("auto".equals(config.mode())) {
                List<OpenRow> aged = queueRepo.listAutoApplyReady(1, config.autoApplyDelaySeconds());
                if (!aged.isEmpty()) {
                    OpenRow row = aged.get(0);
                    boolean ok;
                    try {
                        ok = autoApplier.apply(row);
                    } catch (Exception e) {
                        ok = false;
                        log.warn("[ai-assist] auto-apply threw code={}: {}",
                                row.titleCode(), e.getMessage());
                    }
                    if (ok) {
                        autoApplied++;
                        recordOutcome("auto_applied");
                        io.phaseLog("sweep",
                                "auto-applied code=" + row.titleCode()
                                        + " slug=" + row.aiSuggestionSlug());
                    } else {
                        autoApplyFailed++;
                        recordOutcome("auto_apply_failed");
                        io.phaseLog("sweep",
                                "auto-apply failed code=" + row.titleCode());
                    }
                    if (!sleepInterruptible(INTER_ROW_DELAY_MS, io)) break;
                    continue;
                }
            }

            // Both queues empty — idle sleep.
            log.info("[ai-assist] no work, sleeping {}s", intervalSec);
            io.phaseProgress("sweep", processed, -1,
                    "idle — " + processed + " processed, " + errors + " error(s)"
                            + (autoApplied > 0 ? ", " + autoApplied + " auto-applied" : ""));
            if (!sleepInterruptible(intervalSec * 1000L, io)) break;
        }

        String summary = processed + " suggestion(s) written"
                + (errors > 0 ? " · " + errors + " error(s)" : "")
                + (autoApplied > 0 ? " · " + autoApplied + " auto-applied" : "")
                + (autoApplyFailed > 0 ? " · " + autoApplyFailed + " auto-apply failed" : "")
                + (io.isCancellationRequested() ? " (cancelled)" : "");
        io.phaseEnd("sweep", "ok", summary);
    }

    /** Outcome of one Phase-A row attempt — used only to bump processed-vs-errors counters. */
    private enum ProcessOutcome { OK, ERROR }

    /** Phase-A row body: ask the ensemble + persist suggestion / error sentinel. */
    private ProcessOutcome processOneRow(OpenRow row, TaskIO io) {
        try {
            AssistContext ctx = queueRepo.findContextForAssist(row.titleId());
            AssistResult result = caller.evaluate(row, ctx.folderPath(), ctx.actressNames());

            queueRepo.setAiSuggestion(
                    row.id(),
                    result.suggestedSlug(),
                    // Phase 1 stores the ensemble outcome label in the confidence column —
                    // see setAiSuggestion javadoc. The column is unconstrained TEXT.
                    result.outcome(),
                    truncate(result.reason()),
                    Instant.now());

            recordOutcome(result.outcome());
            log.info("[ai-assist] {} → {} ({})",
                    row.titleCode(),
                    result.outcome(),
                    truncate60(result.reason()));
            io.phaseLog("sweep",
                    "ok code=" + row.titleCode() + " outcome=" + result.outcome()
                            + " slug=" + (result.suggestedSlug() != null ? result.suggestedSlug() : "-"));
            return ProcessOutcome.OK;
        } catch (Exception e) {
            recordOutcome("error");
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("[ai-assist] failed code={} : {}", row.titleCode(), msg);
            // Persist a sentinel so this row is not re-tried forever. The
            // listOpenAwaitingAi filter excludes rows whose ai_suggestion_at is non-null.
            try {
                queueRepo.setAiSuggestion(row.id(), null, "error", truncate(msg), Instant.now());
            } catch (RuntimeException sentinelErr) {
                log.error("[ai-assist] failed to write error sentinel for row {} — row may re-trigger",
                        row.id(), sentinelErr);
            }
            io.phaseLog("sweep",
                    "ERR code=" + row.titleCode() + " : " + truncate60(msg));
            return ProcessOutcome.ERROR;
        }
    }

    /**
     * Sleeps in short slices so cancellation polling stays responsive even with long
     * sweeperIntervalSeconds. Returns false if cancellation was requested mid-sleep.
     */
    private static boolean sleepInterruptible(long totalMs, TaskIO io) {
        long remaining = totalMs;
        final long slice = 250L;
        while (remaining > 0) {
            if (io.isCancellationRequested()) return false;
            long toSleep = Math.min(slice, remaining);
            try {
                Thread.sleep(toSleep);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= toSleep;
        }
        return !io.isCancellationRequested();
    }

    /**
     * Records one outcome for the daily-summary rollup. If the UTC date has rolled forward
     * since the bucket started, emits an INFO summary line for the prior day and resets the
     * counters before recording the new outcome under the new day. The in-progress day is
     * intentionally NOT flushed on sweeper stop (Phase 1 / cron-style behavior).
     */
    void recordOutcome(String outcome) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (dayBucketStart == null) {
            dayBucketStart = today;
        } else if (today.isAfter(dayBucketStart)) {
            emitDailySummary(dayBucketStart);
            outcomeCounts.clear();
            processedToday = 0;
            dayBucketStart = today;
        }
        outcomeCounts.merge(outcome == null ? "unknown" : outcome, 1, Integer::sum);
        processedToday++;
    }

    private void emitDailySummary(LocalDate day) {
        log.info("[ai-assist] daily summary: processed={} agreed={} phi4_only={} gemma_only={}"
                        + " conflict={} both_abstain={} error={} auto_applied={} auto_apply_failed={}",
                processedToday,
                outcomeCounts.getOrDefault("agreed", 0),
                outcomeCounts.getOrDefault("phi4_only", 0),
                outcomeCounts.getOrDefault("gemma_only", 0),
                outcomeCounts.getOrDefault("conflict", 0),
                outcomeCounts.getOrDefault("both_abstain", 0),
                outcomeCounts.getOrDefault("error", 0),
                outcomeCounts.getOrDefault("auto_applied", 0),
                outcomeCounts.getOrDefault("auto_apply_failed", 0));
    }

    // Package-private accessors for tests.
    Map<String, Integer> outcomeCountsForTest() { return outcomeCounts; }
    int processedTodayForTest() { return processedToday; }
    LocalDate dayBucketStartForTest() { return dayBucketStart; }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= REASON_MAX_LEN ? s : s.substring(0, REASON_MAX_LEN);
    }

    private static String truncate60(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60);
    }
}
