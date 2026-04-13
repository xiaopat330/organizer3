package com.organizer3.shell.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsContext;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandIO} implementation that routes output to the browser over a WebSocket.
 *
 * <p>Each method maps to a typed JSON message on the wire:
 * <ul>
 *   <li>{@code println}  →  {@code {"type":"output","text":"..."}}</li>
 *   <li>{@code startSpinner}  →  {@code {"type":"spinner-start","label":"..."}}</li>
 *   <li>{@code startProgress} →  {@code {"type":"progress-start","label":"...","total":N}}</li>
 *   <li>{@code pick}  →  {@code {"type":"pick","items":[...]}} (blocks until browser responds)</li>
 *   <li>{@code ready} (sent by handler after command completes)  →  {@code {"type":"ready"}}</li>
 * </ul>
 *
 * <p>{@link #receivePickResponse(int)} is called by the WebSocket message handler thread
 * when a {@code pick-response} message arrives from the browser. It unblocks the command
 * thread waiting in {@link #pick}.
 */
@Slf4j
public class WebSocketCommandIO implements CommandIO {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PICK_TIMEOUT_SECONDS = 60;

    private final WsContext ctx;
    private final SynchronousQueue<Integer> pickQueue = new SynchronousQueue<>();

    public WebSocketCommandIO(WsContext ctx) {
        this.ctx = ctx;
    }

    // ── Message output ────────────────────────────────────────────────────────

    @Override
    public void println(String message) {
        send("type", "output", "text", message);
    }

    @Override
    public void println() {
        send("type", "output", "text", "");
    }

    @Override
    public void printlnAnsi(String message) {
        String plain = message.replaceAll("\033\\[[0-9;]*m", "");
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "output");
        msg.put("text", plain);
        msg.put("ansi", message);
        sendMap(msg);
    }

    // ── Status area (no-op — browser has its own status zone) ────────────────

    @Override
    public void status(String message) { /* no-op */ }

    @Override
    public void clearStatus() { /* no-op */ }

    // ── Spinner ───────────────────────────────────────────────────────────────

    @Override
    public Spinner startSpinner(String label) {
        send("type", "spinner-start", "label", label);
        return new WebSocketSpinner(this);
    }

    void sendSpinnerUpdate(String label) {
        send("type", "spinner-update", "label", label);
    }

    void sendSpinnerStop() {
        send("type", "spinner-stop");
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    @Override
    public Progress startProgress(String label, int total) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "progress-start");
        msg.put("label", label);
        msg.put("total", total);
        sendMap(msg);
        return new WebSocketProgress(this, total);
    }

    void sendProgressUpdate(int current, int total, String detail) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "progress-update");
        msg.put("current", current);
        msg.put("total", total);
        if (detail != null) msg.put("detail", detail);
        sendMap(msg);
    }

    void sendProgressStop() {
        send("type", "progress-stop");
    }

    // ── Pick ──────────────────────────────────────────────────────────────────

    @Override
    public Optional<String> pick(List<String> items) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "pick");
        msg.put("items", items);
        sendMap(msg);

        try {
            Integer index = pickQueue.poll(PICK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (index == null || index < 0 || index >= items.size()) {
                return Optional.empty();
            }
            return Optional.of(items.get(index));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Called by the WebSocket message handler when a {@code pick-response} arrives.
     * Unblocks the command thread waiting in {@link #pick}.
     * Pass {@code -1} to cancel (e.g. on WebSocket close).
     */
    public void receivePickResponse(int index) {
        try {
            pickQueue.offer(index, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Wire helpers ──────────────────────────────────────────────────────────

    /** Convenience send for single-field messages like {@code {"type":"spinner-stop"}}. */
    void send(String... keyValues) {
        Map<String, Object> msg = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            msg.put(keyValues[i], keyValues[i + 1]);
        }
        sendMap(msg);
    }

    void sendMap(Map<String, Object> msg) {
        try {
            ctx.send(MAPPER.writeValueAsString(msg));
        } catch (Exception e) {
            log.debug("WebSocket send failed (connection may be closed): {}", e.getMessage());
        }
    }
}
