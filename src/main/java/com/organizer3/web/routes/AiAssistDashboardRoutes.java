package com.organizer3.web.routes;

import com.organizer3.enrichment.ai.EnrichmentAssistSweeper;
import com.organizer3.enrichment.ai.EnrichmentAutoApplier;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes read-only AI-assist dashboard aggregates plus the long-lived
 * sweeper-task on/off control.
 *
 * <ul>
 *   <li>{@code GET /api/enrichment/assist/dashboard} — summary counts + outcome breakdown</li>
 *   <li>{@code GET /api/enrichment/assist/queue-preview} — next items awaiting AI (param: limit)</li>
 *   <li>{@code GET /api/enrichment/assist/recent} — recently processed rows (params: limit, since)</li>
 *   <li>{@code GET /api/enrichment/assist/sweeper} — sweeper active/inactive state</li>
 *   <li>{@code POST /api/enrichment/assist/sweeper/start} — start the sweeper (idempotent)</li>
 *   <li>{@code POST /api/enrichment/assist/sweeper/stop} — stop the sweeper (idempotent)</li>
 * </ul>
 */
@Slf4j
public class AiAssistDashboardRoutes {

    private static final int APPLY_MAX_LIMIT = 5000;

    private final OllamaModelOrchestrator orchestrator;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final TaskRunner taskRunner;
    private final EnrichmentAutoApplier autoApplier;

    private final ExecutorService applyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apply-agreed");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean applyRunning = new AtomicBoolean(false);
    private final AtomicInteger applyTotal   = new AtomicInteger(0);
    private final AtomicInteger applyApplied = new AtomicInteger(0);
    private final AtomicInteger applyFailed  = new AtomicInteger(0);

    public AiAssistDashboardRoutes(OllamaModelOrchestrator orchestrator,
                                   EnrichmentReviewQueueRepository reviewQueueRepo,
                                   TaskRunner taskRunner,
                                   EnrichmentAutoApplier autoApplier) {
        this.orchestrator    = orchestrator;
        this.reviewQueueRepo = reviewQueueRepo;
        this.taskRunner      = taskRunner;
        this.autoApplier     = autoApplier;
    }

    public void register(Javalin app) {

        // GET /api/enrichment/assist/dashboard
        app.get("/api/enrichment/assist/dashboard", ctx -> {
            OllamaModelOrchestrator.QueueDepths depths = orchestrator.getQueueDepths();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("awaitingAi",          reviewQueueRepo.countAwaitingAi());
            body.put("inFlight",             depths.inFlight());
            body.put("orchestratorQueued",   depths.queued());
            body.put("processedTotal",       reviewQueueRepo.countProcessed());
            body.put("autoApplied",          reviewQueueRepo.countAutoApplied());
            body.put("outcomeCounts",        reviewQueueRepo.outcomeCounts());
            body.put("openAmbiguous",        reviewQueueRepo.countOpen("ambiguous"));
            body.put("openReviewTotal",      reviewQueueRepo.countOpen("ambiguous")
                                           + reviewQueueRepo.countOpen("cast_anomaly")
                                           + reviewQueueRepo.countOpen("fetch_failed"));
            body.put("agreedPending",        reviewQueueRepo.countAgreedReadyToApply());
            ctx.json(body);
        });

        // GET /api/enrichment/assist/queue-preview?limit=15
        app.get("/api/enrichment/assist/queue-preview", ctx -> {
            int limit = clamp(queryInt(ctx.queryParam("limit"), 15), 1, 100);
            List<Map<String, Object>> rows = reviewQueueRepo.listOpenAwaitingAi(limit).stream()
                    .map(row -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reviewQueueId", row.id());
                        m.put("titleId",       row.titleId());
                        m.put("code",          row.titleCode());
                        m.put("createdAt",     row.createdAt());
                        return m;
                    })
                    .toList();
            ctx.json(rows);
        });

        // GET /api/enrichment/assist/recent?limit=50&since=<iso>
        app.get("/api/enrichment/assist/recent", ctx -> {
            int    limit = clamp(queryInt(ctx.queryParam("limit"), 50), 1, 200);
            String since = ctx.queryParam("since");

            List<Map<String, Object>> rows = reviewQueueRepo.listRecentlyProcessed(limit, since).stream()
                    .map(row -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reviewQueueId", row.reviewQueueId());
                        m.put("code",          row.code());
                        m.put("outcome",       row.outcome());
                        m.put("slug",          row.slug());
                        m.put("reason",        row.reason());
                        m.put("autoApplied",   row.autoApplied());
                        m.put("resolved",      row.resolvedAt() != null);
                        m.put("at",            row.at());
                        return m;
                    })
                    .toList();
            ctx.json(rows);
        });

        // GET /api/enrichment/assist/sweeper — { active, runId }
        app.get("/api/enrichment/assist/sweeper", ctx -> {
            Optional<TaskRun> sweeper = runningSweeper();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("active", sweeper.isPresent());
            body.put("runId",  sweeper.map(TaskRun::runId).orElse(null));
            ctx.json(body);
        });

        // POST /api/enrichment/assist/sweeper/start — idempotent
        app.post("/api/enrichment/assist/sweeper/start", ctx -> {
            Optional<TaskRun> existing = runningSweeper();
            if (existing.isPresent()) {
                // Already running — do not start a second one.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("active", true);
                body.put("runId",  existing.get().runId());
                ctx.json(body);
                return;
            }
            try {
                TaskRun run = taskRunner.start(EnrichmentAssistSweeper.ID, new TaskInputs(Map.of()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("active", true);
                body.put("runId",  run.runId());
                ctx.json(body);
            } catch (TaskRunner.TaskInFlightException e) {
                // A DIFFERENT utility task is running.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("active", false);
                body.put("error", "another task running");
                body.put("runningTaskId", e.runningTaskId);
                ctx.status(409).json(body);
            }
        });

        // POST /api/enrichment/assist/sweeper/stop — idempotent
        app.post("/api/enrichment/assist/sweeper/stop", ctx -> {
            runningSweeper().ifPresent(run -> taskRunner.cancel(run.runId()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("active", false);
            ctx.json(body);
        });

        // POST /api/enrichment/assist/apply-agreed — bulk auto-resolve every agreed row.
        app.post("/api/enrichment/assist/apply-agreed", ctx -> {
            if (applyRunning.get()) {
                ctx.status(409).json(Map.of(
                        "running", true,
                        "total",   applyTotal.get(),
                        "applied", applyApplied.get(),
                        "failed",  applyFailed.get()));
                return;
            }

            List<OpenRow> rows = reviewQueueRepo.listAutoApplyReady(APPLY_MAX_LIMIT, 0, Integer.MAX_VALUE);
            if (rows.isEmpty()) {
                ctx.status(200).json(Map.of("total", 0, "applied", 0, "failed", 0));
                return;
            }

            // Reset stats on a NEW start only — leave the prior run's tally intact on completion.
            applyTotal.set(rows.size());
            applyApplied.set(0);
            applyFailed.set(0);
            applyRunning.set(true);

            applyExecutor.submit(() -> {
                try {
                    for (OpenRow row : rows) {
                        try {
                            // Re-check liveness: a concurrent sweeper may have resolved this row
                            // between selection and now. Skip without counting to keep tallies honest.
                            if (reviewQueueRepo.findOpenById(row.id()).isEmpty()) continue;
                            if (autoApplier.apply(row)) {
                                applyApplied.incrementAndGet();
                            } else {
                                applyFailed.incrementAndGet();
                            }
                        } catch (Exception e) {
                            applyFailed.incrementAndGet();
                            String codeLabel = row.titleCode() != null ? row.titleCode() : ("id=" + row.id());
                            log.warn("[ai-assist] apply-agreed failed code={} id={}: {}",
                                    codeLabel, row.id(), e.getMessage());
                        }
                    }
                } finally {
                    applyRunning.set(false);
                }
            });

            ctx.status(202).json(Map.of("total", rows.size()));
        });

        // GET /api/enrichment/assist/apply-agreed/status — { running, total, applied, failed }
        app.get("/api/enrichment/assist/apply-agreed/status", ctx -> {
            ctx.json(Map.of(
                    "running", applyRunning.get(),
                    "total",   applyTotal.get(),
                    "applied", applyApplied.get(),
                    "failed",  applyFailed.get()));
        });
    }

    /** The currently-running task iff it is the AI-assist sweeper, else empty. */
    private Optional<TaskRun> runningSweeper() {
        return taskRunner.currentlyRunning()
                .filter(run -> EnrichmentAssistSweeper.ID.equals(run.taskId()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static int queryInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
