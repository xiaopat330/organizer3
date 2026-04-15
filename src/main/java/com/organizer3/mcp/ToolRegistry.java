package com.organizer3.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of MCP tools keyed by name. Preserves insertion order so {@code tools/list}
 * presents tools in a stable, curated order rather than whatever the hash table picks.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("Duplicate tool: " + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> list() {
        return tools.values();
    }
}
