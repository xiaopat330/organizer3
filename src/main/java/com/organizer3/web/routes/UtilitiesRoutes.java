package com.organizer3.web.routes;

import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.actress.ActressYamlCatalogService;
import com.organizer3.utilities.volume.StaleLocationsService;
import com.organizer3.utilities.volume.VolumeStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
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
    private final TaskRegistry registry;
    private final TaskRunner runner;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public UtilitiesRoutes(VolumeStateService volumeState, StaleLocationsService staleLocations,
                           ActressYamlCatalogService actressCatalog,
                           TaskRegistry registry, TaskRunner runner) {
        this.volumeState = volumeState;
        this.staleLocations = staleLocations;
        this.actressCatalog = actressCatalog;
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

        // Preview for visualize-then-confirm. Currently only volume.clean_stale_locations is
        // previewable. As more preview-backed tasks are added, switch to a per-task dispatcher
        // (tasks declare preview shape in TaskSpec, or a separate PreviewProvider registry).
        app.post("/api/utilities/tasks/{id}/preview", ctx -> {
            String taskId = ctx.pathParam("id");
            if (!"volume.clean_stale_locations".equals(taskId)) {
                ctx.status(404);
                ctx.json(Map.of("error", "no preview available for task " + taskId));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = ctx.bodyAsClass(Map.class);
            if (inputs == null || !(inputs.get("volumeId") instanceof String volumeId)) {
                ctx.status(400);
                ctx.json(Map.of("error", "volumeId is required"));
                return;
            }
            var rows = staleLocations.preview(volumeId);
            ctx.json(Map.of("rows", rows, "count", rows.size()));
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
                    "status",  r.status().name().toLowerCase()));
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

    private Map<String, Object> runStateJson(TaskRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("taskId", run.taskId());
        out.put("status", run.status().name().toLowerCase());
        out.put("summary", run.summary());
        out.put("startedAt", run.startedAt().toString());
        out.put("endedAt", run.endedAt() == null ? null : run.endedAt().toString());
        out.put("events", run.eventSnapshot());
        return out;
    }
}
