package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.TaskSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StartTaskToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private TaskRegistry taskRegistry;
    private StartTaskTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        taskRegistry = new TaskRegistry(List.of(stubTask("my.task")));
        tool = new StartTaskTool(taskRegistry, taskRunner);
    }

    @Test
    void startsKnownTaskAndReturnsRunId() {
        TaskRun run = mockRun("run-1", "my.task");
        when(taskRunner.start(eq("my.task"), any())).thenReturn(run);

        var r = (StartTaskTool.Result) tool.call(args("my.task", null));
        assertEquals("run-1",   r.runId());
        assertEquals("my.task", r.taskId());
    }

    @Test
    void forwardsInputsToTaskRunner() {
        TaskRun run = mockRun("run-2", "my.task");
        when(taskRunner.start(eq("my.task"), any())).thenReturn(run);

        ObjectNode args = M.createObjectNode();
        args.put("taskId", "my.task");
        ObjectNode inputs = args.putObject("inputs");
        inputs.put("volumeId", "vol-a");
        inputs.put("key",      "value");

        tool.call(args);
        verify(taskRunner).start(eq("my.task"), argThat(ti ->
                "vol-a".equals(ti.values().get("volumeId"))
             && "value".equals(ti.values().get("key"))
        ));
    }

    @Test
    void rejectsUnknownTaskId() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("unknown.task", null)));
        verifyNoInteractions(taskRunner);
    }

    @Test
    void returnsConflictWhenTaskInFlight() {
        when(taskRunner.start(any(), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("other.task", "run-0"));

        var r = (StartTaskTool.ConflictResult) tool.call(args("my.task", null));
        assertTrue(r.conflict());
        assertEquals("other.task", r.runningTaskId());
        assertEquals("run-0",      r.runningRunId());
    }

    @Test
    void worksWithNullInputsNode() {
        TaskRun run = mockRun("run-3", "my.task");
        when(taskRunner.start(eq("my.task"), any())).thenReturn(run);

        ObjectNode args = M.createObjectNode();
        args.put("taskId", "my.task");
        // no "inputs" key at all

        var r = (StartTaskTool.Result) tool.call(args);
        assertEquals("run-3", r.runId());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Task stubTask(String id) {
        TaskSpec spec = new TaskSpec(id, "Title", "Desc", List.of());
        return new Task() {
            @Override public TaskSpec spec() { return spec; }
            @Override public void run(TaskInputs inputs, TaskIO io) {}
        };
    }

    private static TaskRun mockRun(String runId, String taskId) {
        TaskRun run = mock(TaskRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.taskId()).thenReturn(taskId);
        return run;
    }

    private static ObjectNode args(String taskId, String ignored) {
        ObjectNode n = M.createObjectNode();
        n.put("taskId", taskId);
        return n;
    }
}
