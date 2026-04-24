package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.utilities.task.TaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CancelTaskRunToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TaskRunner taskRunner;
    private CancelTaskRunTool tool;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        tool = new CancelTaskRunTool(taskRunner);
    }

    @ParameterizedTest
    @EnumSource(TaskRunner.CancelOutcome.class)
    void returnsOutcomeNameLowercase(TaskRunner.CancelOutcome outcome) {
        when(taskRunner.cancel("run-1")).thenReturn(outcome);

        var r = (CancelTaskRunTool.Result) tool.call(args("run-1"));
        assertEquals("run-1",                   r.runId());
        assertEquals(outcome.name().toLowerCase(), r.outcome());
    }

    @Test
    void passesTrimmedRunIdToRunner() {
        when(taskRunner.cancel("run-1")).thenReturn(TaskRunner.CancelOutcome.REQUESTED);

        tool.call(args("  run-1  "));
        verify(taskRunner).cancel("run-1");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ObjectNode args(String runId) {
        ObjectNode n = M.createObjectNode();
        n.put("runId", runId);
        return n;
    }
}
