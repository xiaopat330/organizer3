package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.trash.TrashScheduleTask;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Schedule one or more trashed items for permanent deletion at the given time.
 *
 * <p>Starts the {@code trash.schedule} background task. Returns a {@code runId} immediately.
 * Poll {@code get_task_run_status} for progress. Requires mutations to be enabled.
 */
public class ScheduleTrashDeletionTool implements Tool {

    private final TaskRunner taskRunner;

    public ScheduleTrashDeletionTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "schedule_trash_deletion"; }
    @Override public String description() {
        return "Schedule trashed items for permanent deletion at a given ISO-8601 time. "
             + "Returns a runId; poll get_task_run_status for progress.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",     "string", "Volume the trash items live on.")
                .propArray("sidecarPaths", "Absolute sidecar paths of the items to schedule (from list_trash_items).")
                .prop("scheduledAt",  "string", "ISO-8601 instant at which deletion becomes eligible (e.g. '2026-05-01T00:00:00Z').")
                .require("volumeId", "sidecarPaths", "scheduledAt")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId    = Schemas.requireString(args, "volumeId").trim();
        String scheduledAt = Schemas.requireString(args, "scheduledAt").trim();

        // Validate scheduledAt is a valid ISO-8601 instant.
        try {
            Instant.parse(scheduledAt);
        } catch (Exception e) {
            throw new IllegalArgumentException("scheduledAt must be an ISO-8601 instant (e.g. 2026-05-01T00:00:00Z) — got: " + scheduledAt);
        }

        JsonNode sidecarPathsNode = args.get("sidecarPaths");
        if (sidecarPathsNode == null || !sidecarPathsNode.isArray() || sidecarPathsNode.isEmpty()) {
            throw new IllegalArgumentException("sidecarPaths must be a non-empty array of path strings");
        }
        List<String> sidecarPaths = new java.util.ArrayList<>();
        sidecarPathsNode.forEach(n -> sidecarPaths.add(n.asText()));

        Map<String, Object> inputs = Map.of(
                "volumeId",     volumeId,
                "sidecarPaths", sidecarPaths,
                "scheduledAt",  scheduledAt
        );
        try {
            TaskRun run = taskRunner.start(TrashScheduleTask.ID, new TaskInputs(inputs));
            return new Result(run.runId(), run.taskId(), volumeId, sidecarPaths.size(), scheduledAt);
        } catch (TaskRunner.TaskInFlightException e) {
            return new ConflictResult(true, e.runningTaskId, e.runningRunId, e.getMessage());
        }
    }

    public record Result(String runId, String taskId, String volumeId,
                         int itemCount, String scheduledAt) {}
    public record ConflictResult(boolean conflict, String runningTaskId, String runningRunId, String message) {}
}
