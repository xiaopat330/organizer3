package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.duplicates.ExecuteDuplicateTrashTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Start the {@code duplicates.execute_trash} background task, which moves all title folders
 * marked TRASH in Duplicate Triage to the volume's trash area and cleans up their DB rows.
 *
 * <p>Optionally scoped to one actress via {@code actressKey}: {@code "id:N"} or
 * {@code "name:Actress Name"}. Omit to execute all pending TRASH decisions.
 *
 * <p>Returns a {@code runId} immediately. Poll {@code get_task_run_status} for progress.
 * Requires mutations and file-ops to be enabled.
 */
public class ExecuteDuplicateTrashTool implements Tool {

    private final TaskRunner taskRunner;

    public ExecuteDuplicateTrashTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "execute_duplicate_trash"; }
    @Override public String description() {
        return "Start the duplicate-trash execution task. Moves all TRASH-decided title folders "
             + "to the volume trash area. Optional actressKey scopes the run to one actress. "
             + "Returns a runId; poll get_task_run_status for progress.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actressKey", "string",
                        "Optional actress scope: 'id:N' for DB id or 'name:Actress Name' for canonical name. "
                      + "Omit to execute all pending TRASH decisions.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String actressKey = Schemas.optString(args, "actressKey", null);

        Map<String, Object> inputs = new HashMap<>();
        if (actressKey != null && !actressKey.isBlank()) {
            inputs.put("actressKey", actressKey.trim());
        }

        try {
            TaskRun run = taskRunner.start(ExecuteDuplicateTrashTask.ID, new TaskInputs(inputs));
            return new Result(run.runId(), run.taskId(), actressKey);
        } catch (TaskRunner.TaskInFlightException e) {
            return new ConflictResult(true, e.runningTaskId, e.runningRunId, e.getMessage());
        }
    }

    public record Result(String runId, String taskId, String actressKey) {}
    public record ConflictResult(boolean conflict, String runningTaskId, String runningRunId, String message) {}
}
