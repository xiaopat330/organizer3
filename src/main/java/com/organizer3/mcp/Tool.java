package com.organizer3.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Contract for a single MCP tool. Each tool is stateless (all state comes from injected
 * dependencies) and must produce a JSON-serializable result for any valid input.
 *
 * <p>Tools are registered into a {@link ToolRegistry} at startup. The registry handles
 * transport concerns (JSON-RPC envelope, error mapping) so tool implementations can focus
 * on extracting arguments and producing a result object.
 */
public interface Tool {

    /** Unique tool identifier surfaced to MCP clients in {@code tools/list}. */
    String name();

    /** One-line human-readable summary shown in tool pickers. */
    String description();

    /**
     * JSON Schema (draft 2020-12) describing the tool's {@code arguments} object.
     * Returned verbatim to the client as {@code inputSchema}.
     */
    JsonNode inputSchema();

    /**
     * Execute the tool with the given arguments. Implementations may return any Jackson-
     * serializable value; the registry will wrap it into an MCP content block.
     *
     * @param args client-supplied arguments, never null (empty object if omitted)
     */
    Object call(JsonNode args) throws Exception;
}
