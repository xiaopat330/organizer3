package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.enrichment.CastAnomalyTriageService;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Route-level integration tests for {@link CastAnomalyTriageRoutes}.
 *
 * <p>Uses a real embedded Javalin server (port 0) with a mocked
 * {@link CastAnomalyTriageService} — consistent with {@code NoMatchTriageRoutesTest}.
 */
class CastAnomalyTriageRoutesTest {

    private WebServer server;
    private CastAnomalyTriageService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        service = mock(CastAnomalyTriageService.class);
        server = new WebServer(0);
        server.registerCastAnomalyTriage(new CastAnomalyTriageRoutes(service));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── POST /api/triage/cast-anomaly/:queueId/add-alias ─────────────────────

    @Test
    void addAlias_happyPath_returns200WithCounts() throws Exception {
        when(service.addAlias(42L, 88L, "黒木麻衣"))
                .thenReturn(new CastAnomalyTriageService.AddAliasResult(true, 3));

        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/42/add-alias",
                Map.of("actressId", 88, "aliasName", "黒木麻衣"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("alias_inserted").asBoolean());
        assertEquals(3, body.get("rows_recovered").asInt());
        verify(service).addAlias(42L, 88L, "黒木麻衣");
    }

    @Test
    void addAlias_queueRowNotFound_returns404() throws Exception {
        when(service.addAlias(anyLong(), anyLong(), anyString()))
                .thenThrow(new CastAnomalyTriageService.NotFoundException("queue row not found: 99"));

        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/99/add-alias",
                Map.of("actressId", 88, "aliasName", "Some Name"));

        assertEquals(404, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("error"));
    }

    @Test
    void addAlias_validationError_returns400() throws Exception {
        when(service.addAlias(anyLong(), anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("actress 77 is not linked to title 10"));

        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/42/add-alias",
                Map.of("actressId", 77, "aliasName", "Some Name"));

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("error"));
    }

    @Test
    void addAlias_nonNumericQueueId_returns400() throws Exception {
        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/not-a-number/add-alias",
                Map.of("actressId", 88, "aliasName", "Some Name"));

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void addAlias_missingActressId_returns400() throws Exception {
        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/42/add-alias",
                Map.of("aliasName", "Some Name"));

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void addAlias_missingAliasName_returns400() throws Exception {
        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/42/add-alias",
                Map.of("actressId", 88));

        assertEquals(400, res.statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void addAlias_zeroRowsRecovered_returns200() throws Exception {
        when(service.addAlias(42L, 88L, "Already Known"))
                .thenReturn(new CastAnomalyTriageService.AddAliasResult(true, 0));

        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/42/add-alias",
                Map.of("actressId", 88, "aliasName", "Already Known"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("alias_inserted").asBoolean());
        assertEquals(0, body.get("rows_recovered").asInt());
    }

    @Test
    void addAlias_wrongReason_returns400() throws Exception {
        when(service.addAlias(42L, 88L, "Some Name"))
                .thenThrow(new IllegalArgumentException("queue row 42 has reason 'ambiguous', expected 'cast_anomaly'"));

        HttpResponse<String> res = post(
                "/api/triage/cast-anomaly/42/add-alias",
                Map.of("actressId", 88, "aliasName", "Some Name"));

        assertEquals(400, res.statusCode());
    }
}
