package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.trash.TrashRestoreTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Restore one or more trashed items to their original paths.
 *
 * <p>Starts the {@code trash.restore} background task. Returns a {@code runId} immediately.
 * Poll {@code get_task_run_status} for progress. Requires mutations and file-ops to be enabled.
 */
public class RestoreTrashedTool implements Tool {

    private final TaskRunner taskRunner;

    public RestoreTrashedTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "restore_trashed"; }
    @Override public String description() {
        return "Restore trashed items to their original paths. "
             + "Returns a runId; poll get_task_run_status for progress.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",       "string", "Volume the trash items live on.")
                .propArray("sidecarPaths", "Absolute sidecar paths of the items to restore (from list_trash_items).")
                .require("volumeId", "sidecarPaths")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.requireString(args, "volumeId").trim();

        JsonNode sidecarPathsNode = args.get("sidecarPaths");
        if (sidecarPathsNode == null || !sidecarPathsNode.isArray() || sidecarPathsNode.isEmpty()) {
            throw new IllegalArgumentException("sidecarPaths must be a non-empty array of path strings");
        }
        List<String> sidecarPaths = new ArrayList<>();
        sidecarPathsNode.forEach(n -> sidecarPaths.add(n.asText()));

        Map<String, Object> inputs = Map.of(
                "volumeId",     volumeId,
                "sidecarPaths", sidecarPaths
        );
        try {
            TaskRun run = taskRunner.start(TrashRestoreTask.ID, new TaskInputs(inputs));
            return new Result(run.runId(), run.taskId(), volumeId, sidecarPaths.size());
        } catch (TaskRunner.TaskInFlightException e) {
            return new ConflictResult(true, e.runningTaskId, e.runningRunId, e.getMessage());
        }
    }

    public record Result(String runId, String taskId, String volumeId, int itemCount) {}
    public record ConflictResult(boolean conflict, String runningTaskId, String runningRunId, String message) {}
}
