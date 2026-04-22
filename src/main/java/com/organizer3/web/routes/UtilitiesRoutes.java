package com.organizer3.web.routes;

import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.utilities.actress.ActressYamlCatalogService;
import com.organizer3.utilities.backup.BackupCatalogService;
import com.organizer3.utilities.covers.OrphanedCoversService;
import com.organizer3.utilities.health.LibraryHealthCheck;
import com.organizer3.utilities.health.LibraryHealthReport;
import com.organizer3.utilities.health.LibraryHealthService;
import com.organizer3.utilities.volume.StaleLocationsService;
import com.organizer3.utilities.volume.VolumeStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * HTTP surface for the Utilities section.
 *
 * <ul>
 *   <li>{@code GET  /api/utilities/volumes}        — list payload for the volume picker.</li>
 *   <li>{@code GET  /api/utilities/volumes/{id}}   — detail for a single volume.</li>
 *   <li>{@code GET  /api/utilities/tasks}          — registered task specs (for UI + MCP discovery).</li>
 *   <li>{@code POST /api/utilities/tasks/{id}/run} — starts a task; body is the inputs map. Returns {@code {runId}}.</li>
 *   <li>{@code GET  /api/utilities/runs/{runId}}   — terminal state + event history (polling fallback).</li>
 *   <li>{@code GET  /api/utilities/runs/{runId}/events} — SSE stream of events.</li>
 * </ul>
 */
@Slf4j
public final class UtilitiesRoutes {

    private final VolumeStateService volumeState;
    private final StaleLocationsService staleLocations;
    private final ActressYamlCatalogService actressCatalog;
    private final ActressYamlLoader actressLoader;
    private final BackupCatalogService backupCatalog;
    private final UserDataBackupService backupService;
    private final LibraryHealthService healthService;
    private final OrphanedCoversService orphanedCoversService;
    private final TaskRegistry registry;
    private final TaskRunner runner;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public UtilitiesRoutes(VolumeStateService volumeState, StaleLocationsService staleLocations,
                           ActressYamlCatalogService actressCatalog,
                           ActressYamlLoader actressLoader,
                           BackupCatalogService backupCatalog,
                           UserDataBackupService backupService,
                           LibraryHealthService healthService,
                           OrphanedCoversService orphanedCoversService,
                           TaskRegistry registry, TaskRunner runner) {
        this.volumeState = volumeState;
        this.staleLocations = staleLocations;
        this.actressCatalog = actressCatalog;
        this.actressLoader = actressLoader;
        this.backupCatalog = backupCatalog;
        this.backupService = backupService;
        this.healthService = healthService;
        this.orphanedCoversService = orphanedCoversService;
        this.registry = registry;
        this.runner = runner;
    }

    public void register(Javalin app) {
        app.get("/api/utilities/volumes", ctx -> ctx.json(volumeState.list()));

        app.get("/api/utilities/volumes/{id}", ctx ->
                volumeState.find(ctx.pathParam("id")).ifPresentOrElse(
                        ctx::json,
                        () -> { ctx.status(404); ctx.json(Map.of("error", "volume not found")); }));

        app.get("/api/utilities/tasks", ctx -> ctx.json(registry.specs()));

        // Actress Data — list + detail endpoints for the dedicated screen.
        app.get("/api/utilities/actress-yamls", ctx -> {
            try {
                ctx.json(actressCatalog.list());
            } catch (java.io.IOException e) {
                log.error("Failed to list actress YAMLs", e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });
        // Backup — snapshot list + per-snapshot detail
        app.get("/api/utilities/backup/snapshots", ctx -> ctx.json(backupCatalog.list()));
        app.get("/api/utilities/backup/snapshots/{name}", ctx -> {
            String name = ctx.pathParam("name");
            var resolved = backupCatalog.resolve(name);
            if (resolved.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "no such snapshot: " + name));
                return;
            }
            try {
                ctx.json(backupService.snapshotDetail(resolved.get()));
            } catch (java.io.IOException e) {
                log.error("Failed to read snapshot {}", name, e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        app.get("/api/utilities/actress-yamls/{slug}", ctx -> {
            String slug = ctx.pathParam("slug");
            try {
                actressCatalog.find(slug).ifPresentOrElse(
                        ctx::json,
                        () -> { ctx.status(404); ctx.json(Map.of("error", "no such YAML: " + slug)); });
            } catch (java.io.IOException e) {
                log.error("Failed to read actress YAML {}", slug, e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        // Library Health — check list + latest report + per-check detail
        app.get("/api/utilities/health/checks", ctx -> {
            // Always-available list so the UI can render empty rows before the first scan.
            ctx.json(healthService.checks().stream().map(UtilitiesRoutes::checkMetaJson).toList());
        });
        app.get("/api/utilities/health/report/latest", ctx -> {
            var latest = healthService.latest();
            if (latest.isEmpty()) {
                ctx.json(Map.of("scanned", false));
                return;
            }
            ctx.json(reportSummaryJson(latest.get()));
        });
        app.get("/api/utilities/health/report/latest/{checkId}", ctx -> {
            String checkId = ctx.pathParam("checkId");
            var latest = healthService.latest();
            if (latest.isEmpty()) { ctx.status(404); ctx.json(Map.of("error", "no report yet")); return; }
            var entry = latest.get().checks().get(checkId);
            if (entry == null) { ctx.status(404); ctx.json(Map.of("error", "no such check: " + checkId)); return; }
            ctx.json(entry);
        });

        // Preview for visualize-then-confirm. Currently only volume.clean_stale_locations is
        // previewable. As more preview-backed tasks are added, switch to a per-task dispatcher
        // (tasks declare preview shape in TaskSpec, or a separate PreviewProvider registry).
        app.post("/api/utilities/tasks/{id}/preview", ctx -> {
            String taskId = ctx.pathParam("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = ctx.bodyAsClass(Map.class);
            if (inputs == null) inputs = Map.of();

            switch (taskId) {
                case "covers.clean_orphaned" -> {
                    // Preview echoes the service's full scan — rows (capped for the UI) + total
                    // size so the user can see what "Proceed" will delete before committing.
                    var pv = orphanedCoversService.preview();
                    int cap = 200;
                    var capped = pv.rows().size() > cap ? pv.rows().subList(0, cap) : pv.rows();
                    ctx.json(Map.of(
                            "count", pv.count(),
                            "totalBytes", pv.totalBytes(),
                            "truncated", pv.rows().size() > cap,
                            "rows", capped));
                }
                case "volume.clean_stale_locations" -> {
                    if (!(inputs.get("volumeId") instanceof String volumeId)) {
                        ctx.status(400);
                        ctx.json(Map.of("error", "volumeId is required"));
                        return;
                    }
                    var rows = staleLocations.preview(volumeId);
                    ctx.json(Map.of("rows", rows, "count", rows.size()));
                }
                case "actress.load_one" -> {
                    if (!(inputs.get("slug") instanceof String slug)) {
                        ctx.status(400);
                        ctx.json(Map.of("error", "slug is required"));
                        return;
                    }
                    try {
                        ctx.json(actressLoader.plan(slug));
                    } catch (IllegalArgumentException e) {
                        ctx.status(404);
                        ctx.json(Map.of("error", e.getMessage()));
                    } catch (Exception e) {
                        log.error("Failed to plan actress load for {}", slug, e);
                        ctx.status(500);
                        ctx.json(Map.of("error", e.getMessage()));
                    }
                }
                case "backup.restore" -> {
                    if (!(inputs.get("snapshotName") instanceof String name)) {
                        ctx.status(400);
                        ctx.json(Map.of("error", "snapshotName is required"));
                        return;
                    }
                    var resolved = backupCatalog.resolve(name);
                    if (resolved.isEmpty()) {
                        ctx.status(404);
                        ctx.json(Map.of("error", "no such snapshot: " + name));
                        return;
                    }
                    try {
                        ctx.json(backupService.previewRestore(resolved.get()));
                    } catch (Exception e) {
                        log.error("Failed to preview restore of {}", name, e);
                        ctx.status(500);
                        ctx.json(Map.of("error", e.getMessage()));
                    }
                }
                default -> {
                    ctx.status(404);
                    ctx.json(Map.of("error", "no preview available for task " + taskId));
                }
            }
        });

        app.post("/api/utilities/tasks/{id}/run", ctx -> {
            String taskId = ctx.pathParam("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = ctx.bodyAsClass(Map.class);
            if (inputs == null) inputs = Map.of();
            try {
                TaskRun run = runner.start(taskId, new TaskInputs(inputs));
                ctx.json(Map.of("runId", run.runId(), "taskId", taskId));
            } catch (TaskRunner.TaskInFlightException e) {
                ctx.status(409);  // Conflict — a task is already in flight
                ctx.json(Map.of(
                        "error", e.getMessage(),
                        "runningTaskId", e.runningTaskId,
                        "runningRunId",  e.runningRunId));
            } catch (IllegalArgumentException e) {
                ctx.status(400);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        // Global "is a task running?" probe for clients arriving mid-flight
        // (new tab, refresh, pill was dismissed). Returns the active run if any.
        app.get("/api/utilities/active", ctx -> {
            var running = runner.currentlyRunning();
            if (running.isEmpty()) { ctx.json(Map.of("active", false)); return; }
            TaskRun r = running.get();
            ctx.json(Map.of(
                    "active",  true,
                    "taskId",  r.taskId(),
                    "runId",   r.runId(),
                    "status",  r.status().name().toLowerCase(),
                    "cancelRequested", r.isCancellationRequested()));
        });

        app.post("/api/utilities/runs/{runId}/cancel", ctx -> {
            String runId = ctx.pathParam("runId");
            TaskRunner.CancelOutcome outcome = runner.cancel(runId);
            switch (outcome) {
                case NOT_FOUND -> { ctx.status(404); ctx.json(Map.of("error", "run not found")); }
                case REQUESTED, ALREADY_REQUESTED, ALREADY_ENDED ->
                        ctx.status(204);
            }
        });

        app.get("/api/utilities/runs/{runId}", ctx -> {
            String runId = ctx.pathParam("runId");
            runner.findRun(runId).ifPresentOrElse(
                    run -> ctx.json(runStateJson(run)),
                    () -> { ctx.status(404); ctx.json(Map.of("error", "run not found")); });
        });

        app.sse("/api/utilities/runs/{runId}/events", client -> {
            String runId = client.ctx().pathParam("runId");
            TaskRun run = runner.findRun(runId).orElse(null);
            if (run == null) {
                client.sendEvent("error", "run not found");
                client.close();
                return;
            }
            streamRun(client, run);
        });
    }

    /**
     * Streams event history plus future events to an SSE client. Atomic subscribe-with-replay
     * ensures no event slips through between the history snapshot and the live subscription.
     * On disconnect, the subscriber auto-cleans.
     */
    private void streamRun(SseClient client, TaskRun run) {
        client.keepAlive();

        Consumer<TaskEvent> listener = e -> {
            sendEvent(client, e);
            if (e instanceof TaskEvent.TaskEnded) {
                client.close();
            }
        };

        TaskRun.SubscribeHandle handle = run.subscribeWithReplay(listener);
        client.onClose(handle.unsubscribe()::run);

        for (TaskEvent e : handle.replay()) {
            sendEvent(client, e);
        }
        // If the run was already terminal at subscribe time, the TaskEnded in replay won't
        // have triggered the close path (we hadn't sent it yet), so close explicitly.
        if (handle.statusAtSubscribe() != TaskRun.Status.RUNNING) {
            client.close();
        }
    }

    private void sendEvent(SseClient client, TaskEvent e) {
        String type = switch (e) {
            case TaskEvent.TaskStarted s -> "task.started";
            case TaskEvent.PhaseStarted p -> "phase.started";
            case TaskEvent.PhaseProgress p -> "phase.progress";
            case TaskEvent.PhaseLog p -> "phase.log";
            case TaskEvent.PhaseEnded p -> "phase.ended";
            case TaskEvent.TaskEnded t -> "task.ended";
        };
        try {
            client.sendEvent(type, json.writeValueAsString(e));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Programming error — event record cannot be serialized. Log loudly so we notice.
            log.error("Failed to serialize task event {}", e, ex);
        } catch (Exception ex) {
            // Client gone or network error — not actionable, don't propagate.
        }
    }

    private static Map<String, Object> checkMetaJson(LibraryHealthCheck c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("label", c.label());
        m.put("description", c.description());
        m.put("fixRouting", c.fixRouting().name());
        return m;
    }

    private static Map<String, Object> reportSummaryJson(LibraryHealthReport r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", true);
        out.put("runId", r.runId());
        out.put("scannedAt", r.scannedAt().toString());
        // Per-check { id, label, fixRouting, total } — enough to render the list pane.
        List<Map<String, Object>> checks = new java.util.ArrayList<>();
        r.checks().values().forEach(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("label", e.label());
            row.put("description", e.description());
            row.put("fixRouting", e.fixRouting().name());
            row.put("total", e.result().total());
            checks.add(row);
        });
        out.put("checks", checks);
        return out;
    }

    private Map<String, Object> runStateJson(TaskRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("taskId", run.taskId());
        out.put("status", run.status().name().toLowerCase());
        out.put("cancelRequested", run.isCancellationRequested());
        out.put("summary", run.summary());
        out.put("startedAt", run.startedAt().toString());
        out.put("endedAt", run.endedAt() == null ? null : run.endedAt().toString());
        out.put("events", run.eventSnapshot());
        return out;
    }
}
