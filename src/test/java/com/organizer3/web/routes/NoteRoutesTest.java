package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.notes.EntityType;
import com.organizer3.notes.Note;
import com.organizer3.notes.NoteService;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Route-level integration tests for {@link NoteRoutes}.
 *
 * <p>Uses a real embedded Javalin server (port 0) with a mocked {@link NoteService}.
 */
class NoteRoutesTest {

    private WebServer server;
    private NoteService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        service = mock(NoteService.class);
        server = new WebServer(0);
        server.registerNotes(new NoteRoutes(service));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .DELETE()
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Note note(EntityType type, String id, String body) {
        return new Note(type, id, body, 1_000L, 2_000L);
    }

    // ── GET /api/notes/{type}/{id} ────────────────────────────────────────────

    @Test
    void get_returns200WithNoteBody() throws Exception {
        when(service.find(EntityType.ACTRESS, "42"))
                .thenReturn(Optional.of(note(EntityType.ACTRESS, "42", "check resume")));

        HttpResponse<String> res = get("/api/notes/actress/42");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("check resume", body.get("body").asText());
        assertTrue(body.has("createdAt"));
        assertTrue(body.has("updatedAt"));
    }

    @Test
    void get_returns404WhenNoNote() throws Exception {
        when(service.find(any(), any())).thenReturn(Optional.empty());

        HttpResponse<String> res = get("/api/notes/actress/99");

        assertEquals(404, res.statusCode());
    }

    @Test
    void get_returns400OnUnknownType() throws Exception {
        HttpResponse<String> res = get("/api/notes/video/42");

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    // ── PUT /api/notes/{type}/{id} — 404 on unknown id ───────────────────────

    @Test
    void put_returns404OnUnknownEntityId() throws Exception {
        when(service.upsert(EntityType.TITLE, "ZZZ-999", "some note"))
                .thenReturn(NoteService.NOT_FOUND);

        HttpResponse<String> res = put("/api/notes/title/ZZZ-999", Map.of("body", "some note"));

        assertEquals(404, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("error"));
    }

    // ── PUT /api/notes/{type}/{id} — 400 on draft id ─────────────────────────

    @Test
    void put_returns400OnDraftId() throws Exception {
        when(service.upsert(EntityType.ACTRESS, "draft-slug", "some note"))
                .thenReturn(NoteService.DRAFT_REJECTED);

        HttpResponse<String> res = put("/api/notes/actress/draft-slug", Map.of("body", "some note"));

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("drafts cannot have notes", body.get("error").asText());
    }

    // ── PUT /api/notes/{type}/{id} — 400 on overlong body ────────────────────

    @Test
    void put_returns400OnOverlongBody() throws Exception {
        String longBody = "x".repeat(281);
        when(service.upsert(EntityType.ACTRESS, "42", longBody))
                .thenReturn(NoteService.TOO_LONG);

        HttpResponse<String> res = put("/api/notes/actress/42", Map.of("body", longBody));

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("error").asText().contains("280"));
    }

    // ── PUT then DELETE-when-empty ────────────────────────────────────────────

    @Test
    void put_returnsOkWithNoteOnSuccess() throws Exception {
        Note saved = note(EntityType.ACTRESS, "42", "great note");
        when(service.upsert(EntityType.ACTRESS, "42", "great note"))
                .thenReturn(new NoteService.UpsertResult.Ok(saved));

        HttpResponse<String> res = put("/api/notes/actress/42", Map.of("body", "great note"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("great note", body.get("body").asText());
    }

    @Test
    void put_emptyBodyReturns204Deleted() throws Exception {
        when(service.upsert(EntityType.ACTRESS, "42", ""))
                .thenReturn(NoteService.DELETED);

        HttpResponse<String> res = put("/api/notes/actress/42", Map.of("body", ""));

        assertEquals(204, res.statusCode());
    }

    // ── DELETE /api/notes/{type}/{id} ─────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        HttpResponse<String> res = delete("/api/notes/title/ABP-001");

        assertEquals(204, res.statusCode());
        verify(service).delete(EntityType.TITLE, "ABP-001");
    }

    // ── POST /api/notes/batch ─────────────────────────────────────────────────

    @Test
    void batch_returnsPresentNotesOnly() throws Exception {
        when(service.findBatch(eq(EntityType.ACTRESS), anyCollection()))
                .thenReturn(Map.of(
                        "1", note(EntityType.ACTRESS, "1", "note one"),
                        "3", note(EntityType.ACTRESS, "3", "note three")));

        HttpResponse<String> res = post("/api/notes/batch",
                Map.of("type", "actress", "ids", List.of("1", "2", "3")));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("1"));
        assertFalse(body.has("2"), "id with no note should be absent from batch result");
        assertTrue(body.has("3"));
        assertEquals("note one", body.get("1").get("body").asText());
    }

    @Test
    void batch_returnsEmptyMapForEmptyIds() throws Exception {
        HttpResponse<String> res = post("/api/notes/batch",
                Map.of("type", "actress", "ids", List.of()));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(0, body.size());
        verifyNoInteractions(service);
    }

    @Test
    void batch_returns400OnUnknownType() throws Exception {
        HttpResponse<String> res = post("/api/notes/batch",
                Map.of("type", "volume", "ids", List.of("1")));

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void batch_returns400WhenTypeIsMissing() throws Exception {
        HttpResponse<String> res = post("/api/notes/batch", Map.of("ids", List.of("1")));

        assertEquals(400, res.statusCode());
    }
}
