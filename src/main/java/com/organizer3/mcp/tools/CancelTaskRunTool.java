package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskRunner;

/**
 * Request cancellation of a running background task by runId.
 *
 * <p>Cancellation is cooperative — the task will stop at its next checkpoint.
 * If the run has already ended, or was already cancelled, returns the appropriate outcome.
 * Requires mutations to be enabled.
 */
public class CancelTaskRunTool implements Tool {

    private final TaskRunner taskRunner;

    public CancelTaskRunTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "cancel_task_run"; }
    @Override public String description() {
        return "Request cancellation of a running background task by runId. "
             + "Cancellation is cooperative; the task stops at its next checkpoint.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("runId", "string", "Task run id to cancel.")
                .require("runId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String runId = Schemas.requireString(args, "runId").trim();
        TaskRunner.CancelOutcome outcome = taskRunner.cancel(runId);
        return new Result(runId, outcome.name().toLowerCase());
    }

    public record Result(String runId, String outcome) {}
}
