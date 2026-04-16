package com.organizer3.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP server implementation.
 *
 * <p>Protocol transport is MCP Streamable HTTP: a single {@code POST <path>} endpoint
 * that accepts JSON-RPC 2.0 request bodies and returns JSON-RPC responses. Mounted on
 * the existing Javalin web server — no separate listener.
 *
 * <p>Implements the minimum protocol surface needed for tool-calling agents:
 * <ul>
 *   <li>{@code initialize} — handshake returning server info + tool capability advertisement</li>
 *   <li>{@code notifications/initialized} — ack, no response</li>
 *   <li>{@code tools/list} — enumerate tools from the {@link ToolRegistry}</li>
 *   <li>{@code tools/call} — dispatch to a tool and wrap the result in an MCP content block</li>
 * </ul>
 */
@Slf4j
public class McpServer {

    /**
     * Version of the MCP spec this server implements. Clients that support a different
     * version will either negotiate down or reject — we return this verbatim.
     */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ToolRegistry registry;
    private final McpConfig config;
    private final String serverName;
    private final String serverVersion;

    public McpServer(ToolRegistry registry, McpConfig config, String serverName, String serverVersion) {
        this.registry = registry;
        this.config = config;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    /** Mount the MCP endpoint on the given Javalin instance. */
    public void register(Javalin app) {
        if (!config.isEnabled()) {
            log.info("MCP server disabled via config — endpoint not registered");
            return;
        }
        String path = config.effectivePath();
        app.post(path, this::handle);
        log.info("MCP server mounted at {} ({} tools)", path, registry.list().size());
    }

    // ── JSON-RPC dispatch ────────────────────────────────────────────────────

    private void handle(Context ctx) {
        JsonNode request;
        try {
            request = MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(rpcError(null, -32700, "Parse error: " + e.getMessage()));
            return;
        }

        // Notifications have no id and no response.
        boolean isNotification = !request.hasNonNull("id");
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");
        JsonNode params = request.path("params");

        try {
            Object result = dispatch(method, params);
            if (isNotification) {
                ctx.status(204);
                return;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", MAPPER.valueToTree(result));
            ctx.json(response);
        } catch (RpcException e) {
            if (isNotification) {
                ctx.status(204);
                return;
            }
            ctx.json(rpcError(id, e.code, e.getMessage()));
        } catch (Exception e) {
            log.warn("MCP internal error on method {}: {}", method, e.getMessage(), e);
            if (isNotification) {
                ctx.status(204);
                return;
            }
            ctx.json(rpcError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private Object dispatch(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "initialize"                 -> initialize();
            case "notifications/initialized"  -> null;  // notification, caller handles 204
            case "ping"                       -> MAPPER.createObjectNode();
            case "tools/list"                 -> toolsList();
            case "tools/call"                 -> toolsCall(params);
            default -> throw new RpcException(-32601, "Method not found: " + method);
        };
    }

    private Object initialize() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);
        return result;
    }

    private Object toolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode arr = result.putArray("tools");
        for (Tool tool : registry.list()) {
            ObjectNode t = arr.addObject();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private Object toolsCall(JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        if (name.isBlank()) throw new RpcException(-32602, "Missing tool name");
        JsonNode args = params.hasNonNull("arguments") ? params.get("arguments") : MAPPER.createObjectNode();
        Tool tool = registry.find(name)
                .orElseThrow(() -> new RpcException(-32602, "Unknown tool: " + name));

        Object result;
        try {
            result = tool.call(args);
        } catch (IllegalArgumentException e) {
            return toolErrorResult("Invalid arguments: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", name, e.getMessage(), e);
            return toolErrorResult(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        String jsonText = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        ObjectNode envelope = MAPPER.createObjectNode();
        ArrayNode content = envelope.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", jsonText);
        envelope.put("isError", false);
        return envelope;
    }

    private ObjectNode toolErrorResult(String message) {
        ObjectNode envelope = MAPPER.createObjectNode();
        ArrayNode content = envelope.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", message);
        envelope.put("isError", true);
        return envelope;
    }

    private ObjectNode rpcError(JsonNode id, int code, String message) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id == null || id.isMissingNode()) resp.putNull("id");
        else resp.set("id", id);
        ObjectNode error = resp.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return resp;
    }

    private static final class RpcException extends RuntimeException {
        final int code;
        RpcException(int code, String message) { super(message); this.code = code; }
    }
}
