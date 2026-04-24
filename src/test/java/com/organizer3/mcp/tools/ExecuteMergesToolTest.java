package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.duplicates.ExecuteMergeTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecuteMergesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private ExecuteMergesTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new ExecuteMergesTool(taskRunner);
    }

    @Test
    void startsTaskAndReturnsRunId() {
        TaskRun run = mockRun("run-1", ExecuteMergeTask.ID);
        when(taskRunner.start(eq(ExecuteMergeTask.ID), any())).thenReturn(run);

        var r = (ExecuteMergesTool.Result) tool.call(M.createObjectNode());
        assertEquals("run-1",           r.runId());
        assertEquals(ExecuteMergeTask.ID, r.taskId());
    }

    @Test
    void returnsConflictWhenTaskInFlight() {
        when(taskRunner.start(any(), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("other.task", "run-0"));

        var r = (ExecuteMergesTool.ConflictResult) tool.call(M.createObjectNode());
        assertTrue(r.conflict());
        assertEquals("other.task", r.runningTaskId());
        assertEquals("run-0",      r.runningRunId());
    }

    private static TaskRun mockRun(String runId, String taskId) {
        TaskRun run = mock(TaskRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.taskId()).thenReturn(taskId);
        return run;
    }
}
