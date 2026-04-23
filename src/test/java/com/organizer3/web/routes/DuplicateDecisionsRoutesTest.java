package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.DuplicateDecisionRepository;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuplicateDecisionsRoutesTest {

    private WebServer server;
    private DuplicateDecisionRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        repo = mock(DuplicateDecisionRepository.class);
        server = new WebServer(0);
        server.registerDuplicateDecisions(new DuplicateDecisionsRoutes(repo));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .GET().build(),
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
                .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── GET /api/tools/duplicates/decisions ───────────────────────────────────

    @Test
    void getDecisionsReturnsPendingList() throws IOException, InterruptedException {
        DuplicateDecision d = DuplicateDecision.builder()
                .titleCode("ABP-001").volumeId("vol-a").nasPath("/vol/stars/ABP-001")
                .decision("KEEP").createdAt(Instant.now().toString()).build();
        when(repo.listPending()).thenReturn(List.of(d));

        HttpResponse<String> res = get("/api/tools/duplicates/decisions");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("ABP-001", body.get(0).get("titleCode").asText());
        assertEquals("KEEP", body.get(0).get("decision").asText());
    }

    @Test
    void getDecisionsReturnsEmptyArray() throws IOException, InterruptedException {
        when(repo.listPending()).thenReturn(List.of());

        HttpResponse<String> res = get("/api/tools/duplicates/decisions");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    // ── PUT /api/tools/duplicates/decisions ───────────────────────────────────

    @Test
    void putDecisionCallsUpsertAndReturns204() throws IOException, InterruptedException {
        HttpResponse<String> res = put("/api/tools/duplicates/decisions", Map.of(
                "titleCode", "ABP-001",
                "volumeId", "vol-a",
                "nasPath", "/vol/stars/ABP-001",
                "decision", "KEEP"
        ));

        assertEquals(204, res.statusCode());
        verify(repo).upsert(argThat(d ->
                "ABP-001".equals(d.getTitleCode()) &&
                "vol-a".equals(d.getVolumeId()) &&
                "/vol/stars/ABP-001".equals(d.getNasPath()) &&
                "KEEP".equals(d.getDecision())));
    }

    @Test
    void putDecisionAcceptsTrashAndVariant() throws IOException, InterruptedException {
        assertEquals(204, put("/api/tools/duplicates/decisions", Map.of(
                "titleCode", "ABP-001", "volumeId", "vol-a",
                "nasPath", "/path", "decision", "TRASH")).statusCode());
        assertEquals(204, put("/api/tools/duplicates/decisions", Map.of(
                "titleCode", "ABP-001", "volumeId", "vol-a",
                "nasPath", "/path", "decision", "VARIANT")).statusCode());
    }

    @Test
    void putDecisionRejects400WhenFieldMissing() throws IOException, InterruptedException {
        HttpResponse<String> res = put("/api/tools/duplicates/decisions", Map.of(
                "titleCode", "ABP-001",
                "volumeId", "vol-a"
                // nasPath and decision missing
        ));
        assertEquals(400, res.statusCode());
        verifyNoInteractions(repo);
    }

    @Test
    void putDecisionRejects400WhenDecisionInvalid() throws IOException, InterruptedException {
        HttpResponse<String> res = put("/api/tools/duplicates/decisions", Map.of(
                "titleCode", "ABP-001",
                "volumeId", "vol-a",
                "nasPath", "/path",
                "decision", "INVALID"
        ));
        assertEquals(400, res.statusCode());
        verifyNoInteractions(repo);
    }

    // ── DELETE /api/tools/duplicates/decisions/{titleCode}/{volumeId} ─────────

    @Test
    void deleteDecisionCallsDeleteAndReturns204() throws IOException, InterruptedException {
        String nasPath = URLEncoder.encode("/vol/stars/ABP-001", StandardCharsets.UTF_8);
        HttpResponse<String> res = delete(
                "/api/tools/duplicates/decisions/ABP-001/vol-a?nasPath=" + nasPath);

        assertEquals(204, res.statusCode());
        verify(repo).delete("ABP-001", "vol-a", "/vol/stars/ABP-001");
    }

    @Test
    void deleteDecisionRejects400WhenNasPathMissing() throws IOException, InterruptedException {
        HttpResponse<String> res = delete("/api/tools/duplicates/decisions/ABP-001/vol-a");

        assertEquals(400, res.statusCode());
        verifyNoInteractions(repo);
    }
}
