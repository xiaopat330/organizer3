package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.trash.TrashRestoreTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RestoreTrashedToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private RestoreTrashedTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new RestoreTrashedTool(taskRunner);
    }

    @Test
    void startsTaskAndReturnsResult() {
        TaskRun run = mockRun("run-1", TrashRestoreTask.ID);
        when(taskRunner.start(eq(TrashRestoreTask.ID), any())).thenReturn(run);

        var r = (RestoreTrashedTool.Result) tool.call(args("vol-a",
                new String[]{"/_trash/item.json", "/_trash/other.json"}));

        assertEquals("run-1",              r.runId());
        assertEquals(TrashRestoreTask.ID,  r.taskId());
        assertEquals("vol-a",              r.volumeId());
        assertEquals(2,                    r.itemCount());
    }

    @Test
    void rejectsEmptySidecarPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("vol-a", new String[]{})));
    }

    @Test
    void returnsConflictWhenTaskInFlight() {
        when(taskRunner.start(any(), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("other.task", "run-0"));

        var r = (RestoreTrashedTool.ConflictResult) tool.call(args("vol-a",
                new String[]{"/_trash/x.json"}));
        assertTrue(r.conflict());
        assertEquals("other.task", r.runningTaskId());
        assertEquals("run-0",      r.runningRunId());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ObjectNode args(String volumeId, String[] paths) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        ArrayNode arr = n.putArray("sidecarPaths");
        for (String p : paths) arr.add(p);
        return n;
    }

    private static TaskRun mockRun(String runId, String taskId) {
        TaskRun run = mock(TaskRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.taskId()).thenReturn(taskId);
        return run;
    }
}
