package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.trash.TrashScheduleTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduleTrashDeletionToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private ScheduleTrashDeletionTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new ScheduleTrashDeletionTool(taskRunner);
    }

    @Test
    void startsTaskAndReturnsResult() {
        TaskRun run = mockRun("run-1", TrashScheduleTask.ID);
        when(taskRunner.start(eq(TrashScheduleTask.ID), any())).thenReturn(run);

        var r = (ScheduleTrashDeletionTool.Result) tool.call(args("vol-a",
                new String[]{"/_trash/item.json"}, "2026-05-01T00:00:00Z"));

        assertEquals("run-1",               r.runId());
        assertEquals(TrashScheduleTask.ID,  r.taskId());
        assertEquals("vol-a",               r.volumeId());
        assertEquals(1,                     r.itemCount());
        assertEquals("2026-05-01T00:00:00Z", r.scheduledAt());
    }

    @Test
    void countsMultiplePaths() {
        TaskRun run = mockRun("run-2", TrashScheduleTask.ID);
        when(taskRunner.start(any(), any())).thenReturn(run);

        var r = (ScheduleTrashDeletionTool.Result) tool.call(args("vol-a",
                new String[]{"/_trash/a.json", "/_trash/b.json", "/_trash/c.json"},
                "2026-05-01T00:00:00Z"));
        assertEquals(3, r.itemCount());
    }

    @Test
    void rejectsInvalidScheduledAt() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("vol-a", new String[]{"/_trash/x.json"}, "not-a-date")));
    }

    @Test
    void rejectsEmptySidecarPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("vol-a", new String[]{}, "2026-05-01T00:00:00Z")));
    }

    @Test
    void returnsConflictWhenTaskInFlight() {
        when(taskRunner.start(any(), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("other.task", "run-0"));

        var r = (ScheduleTrashDeletionTool.ConflictResult) tool.call(args("vol-a",
                new String[]{"/_trash/x.json"}, "2026-05-01T00:00:00Z"));
        assertTrue(r.conflict());
        assertEquals("other.task", r.runningTaskId());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ObjectNode args(String volumeId, String[] paths, String scheduledAt) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId",   volumeId);
        n.put("scheduledAt", scheduledAt);
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
