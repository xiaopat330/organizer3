package com.organizer3.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the JSON-RPC dispatch over HTTP. Boots a Javalin server on an
 * ephemeral port, registers the MCP endpoint, and exercises the protocol handshake
 * plus tool calls with a plain-old {@link HttpClient} — no MCP SDK involved.
 */
class McpServerIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Javalin app;
    private int port;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry().register(new EchoTool());
        McpServer server = new McpServer(registry, McpConfig.defaults(), "test", "0.0.1");
        app = Javalin.create();
        server.register(app);
        app.start(0);
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        JsonNode resp = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""");
        assertEquals("2.0", resp.path("jsonrpc").asText());
        assertEquals(1, resp.path("id").asInt());
        assertEquals("test", resp.path("result").path("serverInfo").path("name").asText());
        assertTrue(resp.path("result").path("capabilities").has("tools"));
    }

    @Test
    void toolsListEnumeratesRegisteredTools() throws Exception {
        JsonNode resp = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");
        JsonNode tools = resp.path("result").path("tools");
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).path("name").asText());
    }

    @Test
    void toolsCallWrapsResultInContentBlock() throws Exception {
        JsonNode resp = post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"echo","arguments":{"message":"hi"}}}""");
        JsonNode result = resp.path("result");
        assertFalse(result.path("isError").asBoolean(true));
        JsonNode text = result.path("content").get(0);
        assertEquals("text", text.path("type").asText());
        assertTrue(text.path("text").asText().contains("hi"));
    }

    @Test
    void toolsCallUnknownToolReturnsRpcError() throws Exception {
        JsonNode resp = post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"does-not-exist","arguments":{}}}""");
        assertTrue(resp.has("error"));
        assertEquals(-32602, resp.path("error").path("code").asInt());
    }

    @Test
    void notificationReturns204() throws Exception {
        HttpResponse<String> raw = postRaw("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");
        assertEquals(204, raw.statusCode());
        assertTrue(raw.body() == null || raw.body().isEmpty());
    }

    @Test
    void unknownMethodReturnsRpcError() throws Exception {
        JsonNode resp = post("""
                {"jsonrpc":"2.0","id":9,"method":"does/not/exist","params":{}}""");
        assertEquals(-32601, resp.path("error").path("code").asInt());
    }

    @Test
    void toolInvalidArgumentsReturnsToolErrorEnvelope() throws Exception {
        // Echo raises IllegalArgumentException when message is missing; the server
        // should turn that into an isError:true envelope, not an RPC protocol error.
        JsonNode resp = post("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                 "params":{"name":"echo","arguments":{}}}""");
        JsonNode result = resp.path("result");
        assertTrue(result.path("isError").asBoolean(false), "expected tool error envelope");
        assertTrue(result.path("content").get(0).path("text").asText().contains("message"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private JsonNode post(String body) throws Exception {
        return M.readTree(postRaw(body).body());
    }

    private HttpResponse<String> postRaw(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Simple tool: echoes the {@code message} arg and requires it to be present. */
    private static final class EchoTool implements Tool {
        @Override public String name()        { return "echo"; }
        @Override public String description() { return "echo back the message"; }
        @Override public JsonNode inputSchema() {
            return Schemas.object().prop("message", "string", "msg").require("message").build();
        }
        @Override public Object call(JsonNode args) {
            String msg = Schemas.requireString(args, "message");
            ObjectNode out = M.createObjectNode();
            out.put("echoed", msg);
            return out;
        }
    }
}
