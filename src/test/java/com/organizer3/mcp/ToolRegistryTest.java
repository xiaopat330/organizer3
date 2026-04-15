package com.organizer3.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void registersAndFindsByName() {
        ToolRegistry reg = new ToolRegistry().register(new FakeTool("foo"));
        assertTrue(reg.find("foo").isPresent());
        assertTrue(reg.find("missing").isEmpty());
    }

    @Test
    void preservesInsertionOrder() {
        ToolRegistry reg = new ToolRegistry()
                .register(new FakeTool("zebra"))
                .register(new FakeTool("alpha"))
                .register(new FakeTool("mango"));
        var names = reg.list().stream().map(Tool::name).toList();
        assertEquals(java.util.List.of("zebra", "alpha", "mango"), names);
    }

    @Test
    void duplicateRegistrationFails() {
        ToolRegistry reg = new ToolRegistry().register(new FakeTool("foo"));
        assertThrows(IllegalStateException.class, () -> reg.register(new FakeTool("foo")));
    }

    private static final class FakeTool implements Tool {
        private final String name;
        FakeTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "fake"; }
        @Override public JsonNode inputSchema() { return Schemas.empty(); }
        @Override public Object call(JsonNode args) { return "ok"; }
    }
}
