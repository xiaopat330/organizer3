package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.volume.SyncVolumeTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncVolumeToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private SyncVolumeTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new SyncVolumeTool(taskRunner);
    }

    @Test
    void startsTaskAndReturnsRunId() {
        TaskRun run = mockRun("run-42", SyncVolumeTask.ID);
        when(taskRunner.start(eq(SyncVolumeTask.ID), argThat(inputs ->
                "s".equals(inputs.getString("volumeId"))))).thenReturn(run);

        ObjectNode args = M.createObjectNode();
        args.put("volumeId", "s");

        var r = (SyncVolumeTool.Result) tool.call(args);
        assertEquals("run-42",        r.runId());
        assertEquals(SyncVolumeTask.ID, r.taskId());
        assertFalse(r.conflict());
        assertNull(r.runningTaskId());
        assertNull(r.runningRunId());
    }

    @Test
    void returnsConflictWhenTaskInFlight() {
        when(taskRunner.start(any(), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("other.task", "run-0"));

        ObjectNode args = M.createObjectNode();
        args.put("volumeId", "s");

        var r = (SyncVolumeTool.Result) tool.call(args);
        assertTrue(r.conflict());
        assertEquals("other.task", r.runningTaskId());
        assertEquals("run-0",      r.runningRunId());
        assertNull(r.runId());
    }

    @Test
    void throwsWhenVolumeIdMissing() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(M.createObjectNode()));
    }

    private static TaskRun mockRun(String runId, String taskId) {
        TaskRun run = mock(TaskRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.taskId()).thenReturn(taskId);
        return run;
    }
}
