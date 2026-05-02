package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.FolderInfo;
import com.organizer3.javdb.enrichment.NoMatchTriageService;
import com.organizer3.javdb.enrichment.NoMatchTriageService.NoMatchTriageRow;
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

class NoMatchTriageRoutesTest {

    private WebServer server;
    private NoMatchTriageService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        service = mock(NoMatchTriageService.class);
        server = new WebServer(0);
        server.registerNoMatchTriage(new NoMatchTriageRoutes(service));
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

    private HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static NoMatchTriageRow makeRow(long titleId, String code) {
        return new NoMatchTriageRow(titleId, code, code, List.of(5L), List.of("Actress A"),
                "/stars/Actress/Title", "vol1", 1, "2026-01-01T00:00:00Z", false, List.of());
    }

    // ── GET /api/triage/no-match ───────────────────────────────────────────────

    @Test
    void getList_returns200WithRows() throws Exception {
        when(service.list(isNull(), eq(false))).thenReturn(List.of(makeRow(10L, "STAR-001")));

        HttpResponse<String> res = get("/api/triage/no-match");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("STAR-001", body.get(0).get("code").asText());
        verify(service).list(null, false);
    }

    @Test
    void getList_emptyReturnsEmptyArray() throws Exception {
        when(service.list(any(), anyBoolean())).thenReturn(List.of());

        HttpResponse<String> res = get("/api/triage/no-match");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(0, body.size());
    }

    @Test
    void getList_actressIdQueryParam() throws Exception {
        when(service.list(eq(5L), eq(false))).thenReturn(List.of());

        HttpResponse<String> res = get("/api/triage/no-match?actressId=5");

        assertEquals(200, res.statusCode());
        verify(service).list(5L, false);
    }

    @Test
    void getList_orphanQueryParam() throws Exception {
        when(service.list(isNull(), eq(true))).thenReturn(List.of());

        HttpResponse<String> res = get("/api/triage/no-match?orphan=1");

        assertEquals(200, res.statusCode());
        verify(service).list(null, true);
    }

    @Test
    void getList_invalidActressId_returns400() throws Exception {
        HttpResponse<String> res = get("/api/triage/no-match?actressId=not-a-number");

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    // ── POST /api/triage/no-match/:id/reassign ─────────────────────────────────

    @Test
    void reassign_returns204OnSuccess() throws Exception {
        doNothing().when(service).tryOtherActress(10L, 7L);

        HttpResponse<String> res = post("/api/triage/no-match/10/reassign",
                Map.of("actressId", 7));

        assertEquals(204, res.statusCode());
        verify(service).tryOtherActress(10L, 7L);
    }

    @Test
    void reassign_returns400WhenActressIdMissing() throws Exception {
        HttpResponse<String> res = post("/api/triage/no-match/10/reassign", Map.of());

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void reassign_returns400WhenServiceThrows() throws Exception {
        doThrow(new IllegalArgumentException("not in cache"))
                .when(service).tryOtherActress(10L, 99L);

        HttpResponse<String> res = post("/api/triage/no-match/10/reassign",
                Map.of("actressId", 99));

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("error"));
    }

    @Test
    void reassign_returns400OnNonNumericId() throws Exception {
        HttpResponse<String> res = post("/api/triage/no-match/abc/reassign",
                Map.of("actressId", 7));

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    // ── POST /api/triage/no-match/:id/manual ──────────────────────────────────

    @Test
    void manual_returns204OnSuccess() throws Exception {
        doNothing().when(service).manualSlugEntry(10L, "AbCd12");

        HttpResponse<String> res = post("/api/triage/no-match/10/manual",
                Map.of("javdbSlug", "AbCd12"));

        assertEquals(204, res.statusCode());
        verify(service).manualSlugEntry(10L, "AbCd12");
    }

    @Test
    void manual_returns400WhenSlugMissing() throws Exception {
        HttpResponse<String> res = post("/api/triage/no-match/10/manual", Map.of());

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void manual_returns400WhenServiceThrows() throws Exception {
        doThrow(new IllegalArgumentException("slug not found"))
                .when(service).manualSlugEntry(10L, "BadSlug1");

        HttpResponse<String> res = post("/api/triage/no-match/10/manual",
                Map.of("javdbSlug", "BadSlug1"));

        assertEquals(400, res.statusCode());
    }

    @Test
    void manual_returns400OnNonNumericId() throws Exception {
        HttpResponse<String> res = post("/api/triage/no-match/abc/manual",
                Map.of("javdbSlug", "AbCd12"));

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    // ── POST /api/triage/no-match/:id/resolve ─────────────────────────────────

    @Test
    void resolve_returns204OnSuccess() throws Exception {
        doNothing().when(service).markResolved(10L);

        HttpResponse<String> res = post("/api/triage/no-match/10/resolve", Map.of());

        assertEquals(204, res.statusCode());
        verify(service).markResolved(10L);
    }

    @Test
    void resolve_returns404WhenNotFound() throws Exception {
        doThrow(new IllegalArgumentException("not found"))
                .when(service).markResolved(99L);

        HttpResponse<String> res = post("/api/triage/no-match/99/resolve", Map.of());

        assertEquals(404, res.statusCode());
    }

    @Test
    void resolve_returns400OnNonNumericId() throws Exception {
        HttpResponse<String> res = post("/api/triage/no-match/abc/resolve", Map.of());

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    // ── GET /api/triage/no-match/:id/folder ───────────────────────────────────

    @Test
    void folder_returnsPathAndVolumeId() throws Exception {
        when(service.openFolder(10L)).thenReturn(
                Optional.of(new FolderInfo("/stars/Actress/STAR-001", "vol1")));

        HttpResponse<String> res = get("/api/triage/no-match/10/folder");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("/stars/Actress/STAR-001", body.get("path").asText());
        assertEquals("vol1", body.get("volumeId").asText());
    }

    @Test
    void folder_returns404WhenNoLocation() throws Exception {
        when(service.openFolder(99L)).thenReturn(Optional.empty());

        HttpResponse<String> res = get("/api/triage/no-match/99/folder");

        assertEquals(404, res.statusCode());
    }

    @Test
    void folder_returns400OnNonNumericId() throws Exception {
        HttpResponse<String> res = get("/api/triage/no-match/abc/folder");

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }
}
