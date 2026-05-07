package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.volume.CoherentMultiVolumeSyncTask;

import java.util.Map;

/**
 * MCP tool: {@code sync_coherent}
 *
 * <p>Enqueues the {@link CoherentMultiVolumeSyncTask}, which scans every configured
 * volume in turn (with orphan prune suppressed per-volume) and runs a single global
 * orphan evaluation after all volumes have been observed. This is the safe way to
 * synchronise the library after manual cross-volume folder movements.
 *
 * <p>Returns immediately with a {@code runId}. Poll {@code get_task_run_status} for progress.
 * Conflicts if any other utility task is already running.
 */
public final class SyncCoherentTool implements Tool {

    private final TaskRunner taskRunner;

    public SyncCoherentTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "sync_coherent"; }
    @Override public String description() {
        return "Start a coherent multi-volume sync. Scans every configured volume in turn and "
             + "evaluates orphans once after all volumes have been observed — prevents false "
             + "orphan detection after manual cross-volume folder moves. "
             + "Returns a runId immediately; poll get_task_run_status for progress. "
             + "Holds the task lock for the duration (may run for hours).";
    }

    @Override
    public JsonNode inputSchema() {
        // No required inputs; all volumes are inferred from config.
        return Schemas.object().build();
    }

    @Override
    public Object call(JsonNode args) {
        try {
            TaskRun run = taskRunner.start(CoherentMultiVolumeSyncTask.ID, new TaskInputs(Map.of()));
            return new Result(run.runId(), run.taskId(), false, null, null);
        } catch (TaskRunner.TaskInFlightException e) {
            return new Result(null, null, true, e.runningTaskId, e.runningRunId);
        }
    }

    public record Result(String runId, String taskId,
                         boolean conflict,
                         String runningTaskId, String runningRunId) {}
}
