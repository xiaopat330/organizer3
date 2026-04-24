package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListTaskSpecsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void emptyRegistryReturnsZeroCount() {
        ListTaskSpecsTool tool = new ListTaskSpecsTool(new TaskRegistry(List.of()));
        var r = (ListTaskSpecsTool.Result) tool.call(M.createObjectNode());
        assertEquals(0, r.count());
        assertTrue(r.tasks().isEmpty());
    }

    @Test
    void returnsSpecsForAllRegisteredTasks() {
        TaskSpec spec = new TaskSpec("my.task", "My Task", "Does stuff",
                List.of(new TaskSpec.InputSpec("volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true)));
        TaskRegistry registry = new TaskRegistry(List.of(stubTask(spec)));
        ListTaskSpecsTool tool = new ListTaskSpecsTool(registry);

        var r = (ListTaskSpecsTool.Result) tool.call(M.createObjectNode());
        assertEquals(1, r.count());

        var row = r.tasks().get(0);
        assertEquals("my.task",   row.id());
        assertEquals("My Task",   row.title());
        assertEquals("Does stuff", row.description());
        assertEquals(1, row.inputs().size());

        var input = row.inputs().get(0);
        assertEquals("volumeId",  input.name());
        assertEquals("Volume",    input.label());
        assertEquals("volume_id", input.type());
        assertTrue(input.required());
    }

    @Test
    void mapsInputTypesToLowercase() {
        TaskSpec spec = new TaskSpec("t", "T", "d",
                List.of(new TaskSpec.InputSpec("key", "Key", TaskSpec.InputSpec.InputType.STRING, false)));
        ListTaskSpecsTool tool = new ListTaskSpecsTool(new TaskRegistry(List.of(stubTask(spec))));

        var row = ((ListTaskSpecsTool.Result) tool.call(M.createObjectNode())).tasks().get(0);
        assertEquals("string", row.inputs().get(0).type());
        assertFalse(row.inputs().get(0).required());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Task stubTask(TaskSpec spec) {
        return new Task() {
            @Override public TaskSpec spec() { return spec; }
            @Override public void run(TaskInputs inputs, TaskIO io) {}
        };
    }
}
