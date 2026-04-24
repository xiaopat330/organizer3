package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.duplicates.ExecuteDuplicateTrashTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecuteDuplicateTrashToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private ExecuteDuplicateTrashTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new ExecuteDuplicateTrashTool(taskRunner);
    }

    @Test
    void startsTaskAndReturnsRunId() {
        TaskRun run = mockRun("run-1", ExecuteDuplicateTrashTask.ID);
        when(taskRunner.start(eq(ExecuteDuplicateTrashTask.ID), any())).thenReturn(run);

        var r = (ExecuteDuplicateTrashTool.Result) tool.call(M.createObjectNode());
        assertEquals("run-1", r.runId());
        assertEquals(ExecuteDuplicateTrashTask.ID, r.taskId());
        assertNull(r.actressKey());
    }

    @Test
    void passesActressKeyToTask() {
        TaskRun run = mockRun("run-2", ExecuteDuplicateTrashTask.ID);
        when(taskRunner.start(eq(ExecuteDuplicateTrashTask.ID), any())).thenReturn(run);

        ObjectNode args = M.createObjectNode();
        args.put("actressKey", "id:42");

        var r = (ExecuteDuplicateTrashTool.Result) tool.call(args);
        assertEquals("id:42", r.actressKey());
    }

    @Test
    void returnsConflictWhenTaskInFlight() {
        when(taskRunner.start(any(), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("other.task", "run-0"));

        var r = (ExecuteDuplicateTrashTool.ConflictResult) tool.call(M.createObjectNode());
        assertTrue(r.conflict());
        assertEquals("other.task", r.runningTaskId());
        assertEquals("run-0",      r.runningRunId());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static TaskRun mockRun(String runId, String taskId) {
        TaskRun run = mock(TaskRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.taskId()).thenReturn(taskId);
        return run;
    }
}
