package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.volume.SyncVolumeTask;

import java.util.Map;

/**
 * MCP tool: {@code sync_volume}
 *
 * <p>Enqueues the {@link SyncVolumeTask} for a single named volume — equivalent to
 * {@code mount → sync all → sync covers → unmount} scoped to that one volume. Lighter
 * than {@code sync_coherent} (which walks every configured volume) and appropriate when
 * only one volume needs to be re-scanned, e.g. after manually placing title folders that
 * must register in {@code titles}/{@code title_locations}.
 *
 * <p>Returns immediately with a {@code runId}. Poll {@code get_task_run_status} for
 * progress. Conflicts if any other utility task is already running.
 */
public final class SyncVolumeTool implements Tool {

    private final TaskRunner taskRunner;

    public SyncVolumeTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "sync_volume"; }
    @Override public String description() {
        return "Start a sync for a single volume: mount → sync titles → sync covers → unmount. "
             + "Lighter than sync_coherent (which walks every configured volume). "
             + "Use when only one volume needs to be re-scanned, e.g. after manually placing "
             + "title folders that need to register in titles/title_locations. "
             + "Returns a runId immediately; poll get_task_run_status for progress. "
             + "Holds the task lock for the duration.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string", "Volume to sync (e.g. \"s\"). Must be a configured volume id.")
                .require("volumeId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.requireString(args, "volumeId").trim();
        try {
            TaskRun run = taskRunner.start(SyncVolumeTask.ID, new TaskInputs(Map.of("volumeId", volumeId)));
            return new Result(run.runId(), run.taskId(), false, null, null);
        } catch (TaskRunner.TaskInFlightException e) {
            return new Result(null, null, true, e.runningTaskId, e.runningRunId);
        }
    }

    public record Result(String runId, String taskId,
                         boolean conflict,
                         String runningTaskId, String runningRunId) {}
}
