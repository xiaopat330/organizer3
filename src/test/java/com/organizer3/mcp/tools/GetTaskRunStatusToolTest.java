package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetTaskRunStatusToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private GetTaskRunStatusTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new GetTaskRunStatusTool(taskRunner);
    }

    @Test
    void notFoundWhenNoRunIdAndNothingRunning() {
        when(taskRunner.currentlyRunning()).thenReturn(Optional.empty());

        var r = (GetTaskRunStatusTool.Result) tool.call(M.createObjectNode());
        assertFalse(r.found());
        assertNull(r.runId());
        assertTrue(r.log().isEmpty());
    }

    @Test
    void notFoundWhenRunIdUnknown() {
        when(taskRunner.findRun("bad-id")).thenReturn(Optional.empty());

        ObjectNode args = M.createObjectNode();
        args.put("runId", "bad-id");

        var r = (GetTaskRunStatusTool.Result) tool.call(args);
        assertFalse(r.found());
        assertEquals("bad-id", r.runId());
    }

    @Test
    void returnsCurrentlyRunningWhenNoRunIdGiven() {
        TaskRun run = mockRun("run-1", "some.task", TaskRun.Status.RUNNING, "in progress", null, List.of());
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(run));

        var r = (GetTaskRunStatusTool.Result) tool.call(M.createObjectNode());
        assertTrue(r.found());
        assertEquals("run-1",    r.runId());
        assertEquals("some.task", r.taskId());
        assertEquals("running",   r.status());
    }

    @Test
    void lookupsRunByIdWhenProvided() {
        TaskRun run = mockRun("run-42", "dup.task", TaskRun.Status.OK, "done", Instant.parse("2026-04-24T12:00:00Z"), List.of());
        when(taskRunner.findRun("run-42")).thenReturn(Optional.of(run));

        ObjectNode args = M.createObjectNode();
        args.put("runId", "run-42");

        var r = (GetTaskRunStatusTool.Result) tool.call(args);
        assertTrue(r.found());
        assertEquals("run-42",  r.runId());
        assertEquals("ok",      r.status());
        assertNotNull(r.endedAt());
    }

    @Test
    void extractsLogLinesFromEvents() {
        Instant t = Instant.now();
        List<TaskEvent> events = List.of(
                new TaskEvent.PhaseStarted(t, "p1",  "Scanning"),
                new TaskEvent.PhaseLog(t, "p1",      "Found 3 items"),
                new TaskEvent.PhaseProgress(t, "p1", 1, 3, "item-a"),
                new TaskEvent.PhaseEnded(t, "p1",    "ok", 500L, "Scanned 3"),
                new TaskEvent.TaskEnded(t, "ok",     "All done")
        );
        TaskRun run = mockRun("run-1", "t", TaskRun.Status.OK, "All done", t, events);
        when(taskRunner.findRun("run-1")).thenReturn(Optional.of(run));

        ObjectNode args = M.createObjectNode();
        args.put("runId", "run-1");

        var r = (GetTaskRunStatusTool.Result) tool.call(args);
        List<String> log = r.log();
        assertEquals(5, log.size());
        assertTrue(log.get(0).contains("started") && log.get(0).contains("Scanning"));
        assertTrue(log.get(1).contains("Found 3 items"));
        assertTrue(log.get(2).contains("33%") || log.get(2).contains("1/3"));
        assertTrue(log.get(3).contains("ok") && log.get(3).contains("Scanned 3"));
        assertTrue(log.get(4).contains("[task]") && log.get(4).contains("All done"));
    }

    @Test
    void progressSkippedWhenTotalIsZero() {
        Instant t = Instant.now();
        List<TaskEvent> events = List.of(
                new TaskEvent.PhaseProgress(t, "p1", 0, 0, "unknown count")
        );
        TaskRun run = mockRun("run-1", "t", TaskRun.Status.RUNNING, "", null, events);
        when(taskRunner.findRun("run-1")).thenReturn(Optional.of(run));

        ObjectNode args = M.createObjectNode();
        args.put("runId", "run-1");

        var r = (GetTaskRunStatusTool.Result) tool.call(args);
        assertTrue(r.log().isEmpty(), "progress with total=0 should not emit a log line");
    }

    @Test
    void blankRunIdFallsBackToCurrentlyRunning() {
        when(taskRunner.currentlyRunning()).thenReturn(Optional.empty());

        ObjectNode args = M.createObjectNode();
        args.put("runId", "   ");

        var r = (GetTaskRunStatusTool.Result) tool.call(args);
        assertFalse(r.found());
        verify(taskRunner).currentlyRunning();
        verify(taskRunner, never()).findRun(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static TaskRun mockRun(String runId, String taskId, TaskRun.Status status,
                                   String summary, Instant endedAt, List<TaskEvent> events) {
        TaskRun run = mock(TaskRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.taskId()).thenReturn(taskId);
        when(run.status()).thenReturn(status);
        when(run.summary()).thenReturn(summary);
        when(run.endedAt()).thenReturn(endedAt);
        when(run.eventSnapshot()).thenReturn(events);
        return run;
    }
}
