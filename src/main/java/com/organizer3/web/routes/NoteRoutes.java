package com.organizer3.web.routes;

import com.organizer3.notes.EntityType;
import com.organizer3.notes.Note;
import com.organizer3.notes.NoteService;
import com.organizer3.notes.NoteService.UpsertResult;
import io.javalin.Javalin;
import io.javalin.http.Context;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP endpoints for user notes on actresses and titles.
 *
 * <pre>
 * GET  /api/notes/{type}/{id}   — fetch one note (200 with body, or 404)
 * PUT  /api/notes/{type}/{id}   — upsert note; empty body deletes (204)
 * DELETE /api/notes/{type}/{id} — delete note (204)
 * POST /api/notes/batch         — batch fetch for card grids
 * </pre>
 *
 * <p>{@code type} ∈ {@code actress} | {@code title}.
 */
@Slf4j
@RequiredArgsConstructor
public class NoteRoutes {

    private final NoteService service;

    public void register(Javalin app) {
        app.get("/api/notes/{type}/{id}",    this::handleGet);
        app.put("/api/notes/{type}/{id}",    this::handlePut);
        app.delete("/api/notes/{type}/{id}", this::handleDelete);
        app.post("/api/notes/batch",         this::handleBatch);
    }

    // ── GET /api/notes/{type}/{id} ────────────────────────────────────────────

    private void handleGet(Context ctx) {
        EntityType type = parseType(ctx);
        if (type == null) return;

        String id = ctx.pathParam("id");
        Optional<Note> note = service.find(type, id);
        if (note.isEmpty()) {
            ctx.status(404);
            ctx.json(Map.of("error", "no note for " + type.wireValue() + "/" + id));
            return;
        }
        ctx.json(toJson(note.get()));
    }

    // ── PUT /api/notes/{type}/{id} ────────────────────────────────────────────

    private void handlePut(Context ctx) {
        EntityType type = parseType(ctx);
        if (type == null) return;

        String id = ctx.pathParam("id");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "request body is required"));
            return;
        }

        Object rawBody = body.get("body");
        String bodyText = rawBody == null ? "" : rawBody.toString();

        UpsertResult result = service.upsert(type, id, bodyText);

        if (result instanceof UpsertResult.Ok ok) {
            ctx.json(toJson(ok.note()));
        } else if (result == UpsertResult.DELETED) {
            ctx.status(204);
        } else if (result == UpsertResult.TOO_LONG) {
            ctx.status(400);
            ctx.json(Map.of("error",
                    "body exceeds " + NoteService.MAX_BODY_LENGTH + " characters"));
        } else if (result == UpsertResult.DRAFT_REJECTED) {
            ctx.status(400);
            ctx.json(Map.of("error", "drafts cannot have notes"));
        } else if (result == UpsertResult.NOT_FOUND) {
            ctx.status(404);
            ctx.json(Map.of("error", type.wireValue() + " not found: " + id));
        } else {
            log.error("Unexpected upsert result: {}", result);
            ctx.status(500);
            ctx.json(Map.of("error", "internal error"));
        }
    }

    // ── DELETE /api/notes/{type}/{id} ─────────────────────────────────────────

    private void handleDelete(Context ctx) {
        EntityType type = parseType(ctx);
        if (type == null) return;

        String id = ctx.pathParam("id");
        service.delete(type, id);
        ctx.status(204);
    }

    // ── POST /api/notes/batch ─────────────────────────────────────────────────

    private void handleBatch(Context ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "request body is required"));
            return;
        }

        String typeStr = (String) body.get("type");
        if (typeStr == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "\"type\" is required"));
            return;
        }
        EntityType type;
        try {
            type = EntityType.fromWireValue(typeStr);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(Map.of("error", "unknown type: " + typeStr));
            return;
        }

        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            ctx.json(Map.of());
            return;
        }

        Collection<String> idSet = ids;
        Map<String, Note> notes = service.findBatch(type, idSet);
        Map<String, Object> response = new LinkedHashMap<>();
        for (Map.Entry<String, Note> entry : notes.entrySet()) {
            response.put(entry.getKey(), toJson(entry.getValue()));
        }
        ctx.json(response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses the {@code {type}} path parameter and writes a 400 response on failure.
     *
     * @return the parsed type, or {@code null} if the response was already written
     */
    private EntityType parseType(Context ctx) {
        String typeStr = ctx.pathParam("type");
        try {
            return EntityType.fromWireValue(typeStr);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(Map.of("error", "unknown entity type: " + typeStr));
            return null;
        }
    }

    private static Map<String, Object> toJson(Note note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("body", note.body());
        m.put("createdAt", note.createdAt());
        m.put("updatedAt", note.updatedAt());
        return m;
    }
}
