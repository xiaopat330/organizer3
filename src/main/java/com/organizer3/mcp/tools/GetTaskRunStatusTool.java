package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Get the status and log of a background task run by runId.
 *
 * <p>Omit {@code runId} to query whatever task is currently running (if any).
 * Returns the run's status, summary, and a flat list of log lines extracted from
 * {@code PhaseLog} and {@code PhaseEnded} events for easy reading.
 */
public class GetTaskRunStatusTool implements Tool {

    private final TaskRunner taskRunner;

    public GetTaskRunStatusTool(TaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    @Override public String name()        { return "get_task_run_status"; }
    @Override public String description() {
        return "Get status and log of a background task run by runId. "
             + "Omit runId to check the currently running task (if any).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("runId", "string", "Task run id (from start_task or a specific task tool). "
                             + "Omit to query the currently running task.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String runId = Schemas.optString(args, "runId", null);

        TaskRun run;
        if (runId == null || runId.isBlank()) {
            Optional<TaskRun> active = taskRunner.currentlyRunning();
            if (active.isEmpty()) return new Result(false, null, null, null, null, null, List.of());
            run = active.get();
        } else {
            Optional<TaskRun> found = taskRunner.findRun(runId.trim());
            if (found.isEmpty()) return new Result(false, runId, null, null, null, null, List.of());
            run = found.get();
        }

        List<String> log = buildLog(run);
        return new Result(
                true,
                run.runId(),
                run.taskId(),
                run.status().name().toLowerCase(),
                run.summary(),
                run.endedAt() != null ? run.endedAt().toString() : null,
                log
        );
    }

    private static List<String> buildLog(TaskRun run) {
        List<String> lines = new ArrayList<>();
        for (TaskEvent event : run.eventSnapshot()) {
            switch (event) {
                case TaskEvent.PhaseStarted ps ->
                        lines.add("[" + ps.phaseId() + "] started — " + ps.label());
                case TaskEvent.PhaseLog pl ->
                        lines.add("[" + pl.phaseId() + "] " + pl.line());
                case TaskEvent.PhaseProgress pp -> {
                    if (pp.total() > 0) {
                        int pct = (int) ((long) pp.current() * 100 / pp.total());
                        lines.add("[" + pp.phaseId() + "] " + pct + "% (" + pp.current()
                                + "/" + pp.total() + ")" + (pp.detail().isBlank() ? "" : " — " + pp.detail()));
                    }
                }
                case TaskEvent.PhaseEnded pe ->
                        lines.add("[" + pe.phaseId() + "] " + pe.status() + " — " + pe.summary());
                case TaskEvent.TaskEnded te ->
                        lines.add("[task] " + te.status() + " — " + te.summary());
                default -> {}
            }
        }
        return lines;
    }

    public record Result(boolean found, String runId, String taskId, String status,
                         String summary, String endedAt, List<String> log) {}
}
