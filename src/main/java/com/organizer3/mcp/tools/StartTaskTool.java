package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Generic task launcher — validates the task id against the registry then forwards
 * arbitrary string inputs. Use {@code list_task_specs} to discover valid ids and inputs.
 *
 * <p>Returns a {@code runId} immediately. Poll {@code get_task_run_status} for progress.
 * Requires mutations to be enabled.
 */
public class StartTaskTool implements Tool {

    private final TaskRegistry taskRegistry;
    private final TaskRunner taskRunner;

    public StartTaskTool(TaskRegistry taskRegistry, TaskRunner taskRunner) {
        this.taskRegistry = taskRegistry;
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "start_task"; }
    @Override public String description() {
        return "Start a background task by id. Use list_task_specs to see valid task ids and inputs. "
             + "Returns a runId; poll get_task_run_status for progress.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("taskId", "string", "Task id to launch (from list_task_specs).")
                .prop("inputs", "object", "Key-value pairs for task inputs (see list_task_specs for each task's declared inputs).")
                .require("taskId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String taskId = Schemas.requireString(args, "taskId").trim();

        if (taskRegistry.find(taskId).isEmpty()) {
            throw new IllegalArgumentException("Unknown task id: '" + taskId
                    + "'. Use list_task_specs to see valid ids.");
        }

        Map<String, Object> inputs = new HashMap<>();
        JsonNode inputsNode = args.get("inputs");
        if (inputsNode != null && inputsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = inputsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                inputs.put(e.getKey(), e.getValue().asText());
            }
        }

        try {
            TaskRun run = taskRunner.start(taskId, new TaskInputs(inputs));
            return new Result(run.runId(), run.taskId());
        } catch (TaskRunner.TaskInFlightException e) {
            return new ConflictResult(true, e.runningTaskId, e.runningRunId, e.getMessage());
        }
    }

    public record Result(String runId, String taskId) {}
    public record ConflictResult(boolean conflict, String runningTaskId, String runningRunId, String message) {}
}
