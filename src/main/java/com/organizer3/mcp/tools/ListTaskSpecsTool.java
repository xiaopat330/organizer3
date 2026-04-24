package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * List all registered background task specs — ids, titles, descriptions, and declared inputs.
 * Use {@code start_task} to launch a task by id, or the specific convenience tools
 * (e.g. {@code execute_duplicate_trash}, {@code execute_merges}).
 */
public class ListTaskSpecsTool implements Tool {

    private final TaskRegistry registry;

    public ListTaskSpecsTool(TaskRegistry registry) {
        this.registry = registry;
    }

    @Override public String name()        { return "list_task_specs"; }
    @Override public String description() {
        return "List all registered background task specs (ids, titles, descriptions, inputs). "
             + "Use start_task to launch a task by id.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.empty();
    }

    @Override
    public Object call(JsonNode args) {
        List<SpecRow> rows = registry.specs().stream()
                .map(s -> new SpecRow(s.id(), s.title(), s.description(),
                        s.inputs().stream()
                                .map(i -> new InputRow(i.name(), i.label(),
                                        i.type().name().toLowerCase(), i.required()))
                                .toList()))
                .toList();
        return new Result(rows.size(), rows);
    }

    public record InputRow(String name, String label, String type, boolean required) {}
    public record SpecRow(String id, String title, String description, List<InputRow> inputs) {}
    public record Result(int count, List<SpecRow> tasks) {}
}
