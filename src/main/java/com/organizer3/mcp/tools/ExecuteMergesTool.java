package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.duplicates.ExecuteMergeTask;

import java.util.Map;

/**
 * Start the {@code duplicates.execute_merge} background task, which reparents all data from
 * each loser title to its winner and deletes the loser title row for every MERGE-decided
 * merge candidate.
 *
 * <p>Returns a {@code runId} immediately. Poll {@code get_task_run_status} for progress.
 * Requires mutations to be enabled.
 */
public class ExecuteMergesTool implements Tool {

    private final TaskRunner taskRunner;

    public ExecuteMergesTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "execute_merges"; }
    @Override public String description() {
        return "Start the execute-merges task. Reparents all data from each loser title to its winner "
             + "and deletes the loser row for every pending MERGE-decided candidate. "
             + "Returns a runId; poll get_task_run_status for progress.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.empty();
    }

    @Override
    public Object call(JsonNode args) {
        TaskRun run = taskRunner.start(ExecuteMergeTask.ID, new TaskInputs(Map.of()));
        return new Result(run.runId(), run.taskId());
    }

    public record Result(String runId, String taskId) {}
}
