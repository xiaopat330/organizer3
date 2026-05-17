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

import java.time.Instant;
import java.util.List;
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
 * <p><b>Phase 1 scope</b>: this sweeper does <i>not</i> auto-apply suggestions even
 * when {@link EnrichmentAssistConfig#mode() mode}={@code auto}. It only fills the
 * suggestion columns. Phase 3 will add an auto-apply timer over the same data.
 * The mode check at the top of the loop exits only on {@code off}; {@code shadow},
 * {@code suggest}, and {@code auto} behave identically here.
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
                    + "two-model ensemble (phi4 + gemma3) and recording the suggestion. Does NOT "
                    + "auto-apply suggestions in Phase 1; sweeper exits cleanly when mode=off or "
                    + "the task is cancelled.",
            List.of()
    );

    private final EnrichmentReviewQueueRepository queueRepo;
    private final EnsembleAssistCaller caller;
    private final EnrichmentAssistConfig config;

    public EnrichmentAssistSweeper(EnrichmentReviewQueueRepository queueRepo,
                                   EnsembleAssistCaller caller,
                                   EnrichmentAssistConfig config) {
        this.queueRepo = Objects.requireNonNull(queueRepo, "queueRepo");
        this.caller    = Objects.requireNonNull(caller, "caller");
        this.config    = Objects.requireNonNull(config, "config");
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

        while (!io.isCancellationRequested()) {
            // One row at a time per the atomic-operations rule.
            List<OpenRow> rows = queueRepo.listOpenAwaitingAi(1);

            if (rows.isEmpty()) {
                log.info("[ai-assist] no work, sleeping {}s", intervalSec);
                io.phaseProgress("sweep", processed, -1,
                        "idle — " + processed + " processed, " + errors + " error(s)");
                if (!sleepInterruptible(intervalSec * 1000L, io)) break;
                continue;
            }

            OpenRow row = rows.get(0);
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

                processed++;
                log.info("[ai-assist] {} → {} ({})",
                        row.titleCode(),
                        result.outcome(),
                        truncate60(result.reason()));
                io.phaseLog("sweep",
                        "ok code=" + row.titleCode() + " outcome=" + result.outcome()
                                + " slug=" + (result.suggestedSlug() != null ? result.suggestedSlug() : "-"));
            } catch (Exception e) {
                errors++;
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
            }

            // Brief delay between rows so the sweeper never hogs the orchestrator.
            if (!sleepInterruptible(INTER_ROW_DELAY_MS, io)) break;
        }

        String summary = processed + " suggestion(s) written"
                + (errors > 0 ? " · " + errors + " error(s)" : "")
                + (io.isCancellationRequested() ? " (cancelled)" : "");
        io.phaseEnd("sweep", "ok", summary);
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

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= REASON_MAX_LEN ? s : s.substring(0, REASON_MAX_LEN);
    }

    private static String truncate60(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60);
    }
}
