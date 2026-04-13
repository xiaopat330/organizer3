package com.organizer3.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.shell.CommandDispatcher;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.WebSocketCommandIO;
import io.javalin.Javalin;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket endpoint that exposes the CLI command dispatcher to the browser.
 *
 * <p>Commands are executed on a dedicated single-thread executor so the WebSocket
 * handler thread remains free to receive {@code pick-response} messages while a
 * command is running.
 *
 * <p>Protocol — browser → server:
 * <pre>
 *   {"type":"command",       "text":"mount a"}
 *   {"type":"pick-response", "index":2}
 * </pre>
 *
 * <p>Protocol — server → browser: see {@link WebSocketCommandIO}.
 */
@Slf4j
public class WebTerminalHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CommandDispatcher dispatcher;
    private final SessionContext session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-terminal");
        t.setDaemon(true);
        return t;
    });

    /** The IO for the currently-executing command, if any. Used to route pick-responses. */
    private final AtomicReference<WebSocketCommandIO> activeIO = new AtomicReference<>();

    public WebTerminalHandler(CommandDispatcher dispatcher, SessionContext session) {
        this.dispatcher = dispatcher;
        this.session = session;
    }

    public void register(Javalin app) {
        app.ws("/ws/terminal", ws -> {
            ws.onConnect(this::onConnect);
            ws.onMessage(this::onMessage);
            ws.onClose(this::onClose);
        });
    }

    private void onConnect(WsConnectContext ctx) {
        sendPrompt(ctx);
        sendRaw(ctx, Map.of("type", "ready"));
    }

    private void onMessage(WsMessageContext ctx) {
        String raw = ctx.message();
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warn("Invalid WebSocket message: {}", raw);
            return;
        }

        String type = node.path("type").asText();
        switch (type) {
            case "command" -> {
                String text = node.path("text").asText("").trim();
                if (text.isBlank()) {
                    sendPrompt(ctx);
                    sendRaw(ctx, Map.of("type", "ready"));
                    return;
                }
                executor.submit(() -> runCommand(ctx, text));
            }
            case "pick-response" -> {
                int index = node.path("index").asInt(-1);
                WebSocketCommandIO io = activeIO.get();
                if (io != null) {
                    io.receivePickResponse(index);
                }
            }
            default -> log.warn("Unknown WebSocket message type: {}", type);
        }
    }

    private void onClose(WsCloseContext ctx) {
        // Unblock any pick() waiting for a browser response
        WebSocketCommandIO io = activeIO.get();
        if (io != null) {
            io.receivePickResponse(-1);
        }
    }

    private void runCommand(WsContext ctx, String line) {
        WebSocketCommandIO io = new WebSocketCommandIO(ctx);
        activeIO.set(io);
        try {
            dispatcher.dispatch(line, session, io);
        } finally {
            activeIO.set(null);
            sendPrompt(ctx);
            sendRaw(ctx, Map.of("type", "ready"));
        }
    }

    private void sendPrompt(WsContext ctx) {
        String prompt = session.getMountedVolumeId() != null
                ? "[MOUNT → " + session.getMountedVolumeId() + "] ▶ "
                : "[UNMOUNTED] ▶ ";
        sendRaw(ctx, Map.of("type", "prompt", "text", prompt));
    }

    private static void sendRaw(WsContext ctx, Map<String, Object> msg) {
        try {
            ctx.send(MAPPER.writeValueAsString(msg));
        } catch (Exception e) {
            log.debug("WebSocket send failed: {}", e.getMessage());
        }
    }
}
