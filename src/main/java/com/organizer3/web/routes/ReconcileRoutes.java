package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.sync.ReconcileDetailSerializer;
import com.organizer3.sync.ReconcileReport;
import com.organizer3.sync.ReconcileService;
import com.organizer3.sync.ReconcileService.SweepRowResult;
import com.organizer3.sync.ReconcileService.TrustVolumeResult;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.volume.SyncVolumeTask;

import java.util.function.Supplier;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP surface for the reconcile-only pass.
 *
 * <ul>
 *   <li>{@code POST /api/reconcile/run} — run the reconcile pass (optional body:
 *       {@code {"verbose": bool, "sweep": bool}}); returns a summary map plus optional
 *       {@code sweptCount} if sweep was requested. Persists with
 *       {@code triggered_by='manual'}.</li>
 *   <li>{@code GET /api/reconcile/recent?limit=N} — returns the N most recent persisted
 *       reports, newest first. Default limit = 10.</li>
 *   <li>{@code POST /api/reconcile/sweep-row?id=N} — sweep a single past-grace
 *       {@code title_locations} row by id; 404/409 guards enforce the grace check.</li>
 *   <li>{@code POST /api/reconcile/trust-volume?titleId=N&trustVolumeId=X} — trigger a
 *       volume sync on the non-trusted duplicate volume so it becomes stale on next sync.</li>
 * </ul>
 */
@Slf4j
public class ReconcileRoutes {

    private static final int DEFAULT_RECENT_LIMIT = 10;

    private final ReconcileService reconcileService;
    private final ReconcileReportRepository reportRepo;
    /** Lazily resolved so this routes instance can be constructed before the TaskRunner. */
    private final Supplier<TaskRunner> taskRunnerSupplier;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReconcileRoutes(ReconcileService reconcileService,
                           ReconcileReportRepository reportRepo,
                           Supplier<TaskRunner> taskRunnerSupplier) {
        this.reconcileService = reconcileService;
        this.reportRepo = reportRepo;
        this.taskRunnerSupplier = taskRunnerSupplier;
    }

    public void register(Javalin app) {

        // POST /api/reconcile/run
        app.post("/api/reconcile/run", ctx -> {
            boolean verbose = false;
            boolean sweep   = false;

            String body = ctx.body();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode node = json.readTree(body);
                    if (node.has("verbose")) verbose = node.get("verbose").asBoolean(false);
                    if (node.has("sweep"))   sweep   = node.get("sweep").asBoolean(false);
                } catch (Exception e) {
                    log.warn("Could not parse reconcile/run body: {}", e.getMessage());
                }
            }

            ReconcileReport report = reconcileService.run(verbose);
            String detailJson = ReconcileDetailSerializer.toJson(report);
            long rowId = reconcileService.persist(report, "manual", detailJson);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportId",                rowId);
            result.put("generatedAt",             report.generatedAt().toString());
            result.put("duplicateLiveLocations",  report.duplicateLiveLocations());
            result.put("pendingGrace",            report.pendingGrace());
            result.put("oldestPendingGraceDays",  report.oldestPendingGraceDays());
            result.put("pastGraceStragglers",     report.pastGraceStragglers());
            result.put("actressFolderMismatches", report.actressFolderMismatches());
            result.put("clean",                   report.isClean());

            if (sweep) {
                int deleted = reconcileService.sweepPastGraceStragglers();
                if (deleted < 0) {
                    result.put("sweptCount", -1);
                    result.put("sweepResult", "REFUSED — catastrophic-delete guard tripped");
                } else {
                    result.put("sweptCount", deleted);
                    result.put("sweepResult", "Deleted " + deleted + " past-grace stale rows");
                }
            }

            ctx.json(result);
        });

        // GET /api/reconcile/recent?limit=N
        app.get("/api/reconcile/recent", ctx -> {
            int limit = DEFAULT_RECENT_LIMIT;
            String limitParam = ctx.queryParam("limit");
            if (limitParam != null) {
                try { limit = Integer.parseInt(limitParam); }
                catch (NumberFormatException ignored) {}
            }
            ctx.json(reportRepo.findRecent(Math.max(1, Math.min(limit, 100))));
        });

        // GET /api/reconcile/last?trigger=coherent_sync
        // Returns the most recent persisted report for the given trigger value.
        // Defaults to "coherent_sync" if param is absent.
        // Returns 404 if no matching report exists.
        // Returns 400 for unrecognised trigger values.
        app.get("/api/reconcile/last", ctx -> {
            String trigger = ctx.queryParamAsClass("trigger", String.class)
                    .getOrDefault("coherent_sync");
            if (!"coherent_sync".equals(trigger) && !"manual".equals(trigger)) {
                ctx.status(400).json(Map.of("error",
                        "Invalid trigger value '" + trigger + "'. Must be 'coherent_sync' or 'manual'."));
                return;
            }
            reportRepo.findLastByTrigger(trigger)
                    .ifPresentOrElse(
                            ctx::json,
                            () -> ctx.status(404).json(Map.of("error", "No report found for trigger: " + trigger))
                    );
        });

        // POST /api/reconcile/sweep-row?id=N
        // Deletes a single title_locations row if it is past-grace.
        // 200  {"deleted": true, "titleId": N, "volumeId": "...", "path": "..."}
        // 404  {"error": "..."}
        // 409  {"error": "row not past grace", "staleDays": X|null, "graceDays": Y}
        app.post("/api/reconcile/sweep-row", ctx -> {
            String idParam = ctx.queryParam("id");
            if (idParam == null || idParam.isBlank()) {
                ctx.status(400).json(Map.of("error", "Query param 'id' is required"));
                return;
            }
            long locationId;
            try {
                locationId = Long.parseLong(idParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Query param 'id' must be a valid long"));
                return;
            }

            SweepRowResult result = reconcileService.sweepRow(locationId);
            switch (result) {
                case SweepRowResult.Deleted d -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("deleted", true);
                    body.put("titleId", d.titleId());
                    body.put("volumeId", d.volumeId());
                    body.put("path", d.path());
                    ctx.status(200).json(body);
                }
                case SweepRowResult.NotFound ignored -> ctx.status(404).json(Map.of("error", "Row not found: id=" + locationId));
                case SweepRowResult.InGrace ig -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("error", "row not past grace");
                    body.put("staleDays", ig.staleDays()); // may be null if stale_since is null
                    body.put("graceDays", ig.graceDays());
                    ctx.status(409).json(body);
                }
            }
        });

        // POST /api/reconcile/trust-volume?titleId=N&trustVolumeId=X
        // Triggers a volume sync of the non-trusted duplicate volume.
        // 202  {"taskId": "...", "runId": "...", "otherVolumeId": "...", "otherPartitionId": "..."}
        // 404  {"error": "..."}
        // 409  {"error": "..."}
        app.post("/api/reconcile/trust-volume", ctx -> {
            String titleIdParam = ctx.queryParam("titleId");
            String trustVolumeId = ctx.queryParam("trustVolumeId");
            if (titleIdParam == null || titleIdParam.isBlank() || trustVolumeId == null || trustVolumeId.isBlank()) {
                ctx.status(400).json(Map.of("error", "Query params 'titleId' and 'trustVolumeId' are required"));
                return;
            }
            long titleId;
            try {
                titleId = Long.parseLong(titleIdParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Query param 'titleId' must be a valid long"));
                return;
            }

            TrustVolumeResult resolution = reconcileService.resolveTrustVolume(titleId, trustVolumeId);
            switch (resolution) {
                case TrustVolumeResult.TitleNotFound ignored -> {
                    ctx.status(404).json(Map.of("error", "Title not found or has no live locations: titleId=" + titleId));
                }
                case TrustVolumeResult.InsufficientLocations insuf -> {
                    ctx.status(409).json(Map.of(
                            "error", "Title does not have live locations on at least 2 distinct volumes",
                            "liveVolumeCount", insuf.liveVolumeCount()));
                }
                case TrustVolumeResult.TrustVolumeNotInLocations ignored -> {
                    ctx.status(409).json(Map.of(
                            "error", "trustVolumeId '" + trustVolumeId + "' is not among the live locations for titleId=" + titleId));
                }
                case TrustVolumeResult.TooManyVolumes tooMany -> {
                    ctx.status(409).json(Map.of(
                            "error", "Title has live locations on " + tooMany.volumeCount()
                                    + " distinct volumes — too complex for v1 trust-volume (expected exactly 2)",
                            "volumeCount", tooMany.volumeCount()));
                }
                case TrustVolumeResult.Ok ok -> {
                    // Trigger a volume sync of the other volume (not a partition-scoped task — see
                    // implementation notes: no partition-sync Task exists; volume sync is the wired path).
                    try {
                        var run = taskRunnerSupplier.get().start(SyncVolumeTask.ID,
                                new TaskInputs(Map.of("volumeId", ok.otherVolumeId())));
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("taskId", run.taskId());
                        body.put("runId", run.runId());
                        body.put("otherVolumeId", ok.otherVolumeId());
                        body.put("otherPartitionId", ok.otherPartitionId());
                        ctx.status(202).json(body);
                    } catch (TaskRunner.TaskInFlightException e) {
                        ctx.status(409).json(Map.of(
                                "error", "task in flight",
                                "runningTaskId", e.runningTaskId,
                                "runningRunId", e.runningRunId));
                    }
                }
            }
        });
    }
}
