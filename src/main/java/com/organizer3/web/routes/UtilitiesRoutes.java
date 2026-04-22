package com.organizer3.web.routes;

import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.volume.VolumeStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;

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
public final class UtilitiesRoutes {

    private final VolumeStateService volumeState;
    private final TaskRegistry registry;
    private final TaskRunner runner;
    private final ObjectMapper json = new ObjectMapper();

    public UtilitiesRoutes(VolumeStateService volumeState, TaskRegistry registry, TaskRunner runner) {
        this.volumeState = volumeState;
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

        app.post("/api/utilities/tasks/{id}/run", ctx -> {
            String taskId = ctx.pathParam("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = ctx.bodyAsClass(Map.class);
            if (inputs == null) inputs = Map.of();
            try {
                TaskRun run = runner.start(taskId, new TaskInputs(inputs));
                ctx.json(Map.of("runId", run.runId(), "taskId", taskId));
            } catch (IllegalArgumentException e) {
                ctx.status(400);
                ctx.json(Map.of("error", e.getMessage()));
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
     * Streams event history plus future events to an SSE client. On disconnect, the subscriber
     * auto-cleans and the method returns — Javalin closes the underlying response.
     */
    private void streamRun(SseClient client, TaskRun run) {
        client.keepAlive();

        // Replay history so a late subscriber can reconstruct current state.
        for (TaskEvent e : run.eventSnapshot()) {
            sendEvent(client, e);
        }
        // If the run is already terminal, close after replay.
        if (run.status() != TaskRun.Status.RUNNING) {
            client.close();
            return;
        }

        // Subscribe for live events. The subscriber runs on the task thread; it must not block.
        Consumer<TaskEvent> listener = e -> {
            sendEvent(client, e);
            if (e instanceof TaskEvent.TaskEnded) {
                client.close();
            }
        };
        Runnable unsubscribe = run.subscribe(listener);
        client.onClose(unsubscribe::run);
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
        } catch (Exception ex) {
            // A failed send means the client is gone — do not propagate.
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
