package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.TitleBrowseService.FlagResult;
import com.organizer3.web.TitleBrowseService.TitleFlagState;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Route integration tests for the three title flag endpoints:
 *   POST /api/titles/{code}/favorite
 *   POST /api/titles/{code}/bookmark
 *   POST /api/titles/{code}/reject
 */
class TitleFlagRoutesTest {

    private WebServer server;
    private TitleBrowseService browseService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        browseService = mock(TitleBrowseService.class);

        server = new WebServer(0);
        server.registerTitleRoutes(new TitleRoutes(browseService, null, null));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── POST /api/titles/{code}/favorite ────────────────────────────────────

    @Test
    void favorite_success_returnsFullFlagState() throws Exception {
        when(browseService.toggleFavorite("ABP-001"))
                .thenReturn(new FlagResult.Ok(new TitleFlagState("ABP-001", true, false, false)));

        HttpResponse<String> res = post("/api/titles/ABP-001/favorite");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("ABP-001", body.get("code").asText());
        assertTrue(body.get("favorite").asBoolean());
        assertFalse(body.get("bookmark").asBoolean());
        assertFalse(body.get("rejected").asBoolean());
    }

    @Test
    void favorite_notFound_returns404() throws Exception {
        when(browseService.toggleFavorite("MISSING-001"))
                .thenReturn(new FlagResult.NotFound());

        HttpResponse<String> res = post("/api/titles/MISSING-001/favorite");

        assertEquals(404, res.statusCode());
        assertTrue(mapper.readTree(res.body()).has("error"));
    }

    @Test
    void favorite_refusedWhileRejected_returns400WithReason() throws Exception {
        when(browseService.toggleFavorite("ABP-002"))
                .thenReturn(new FlagResult.Refused("title is rejected; clear reject first"));

        HttpResponse<String> res = post("/api/titles/ABP-002/favorite");

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("title is rejected; clear reject first", body.get("error").asText());
    }

    // ── POST /api/titles/{code}/bookmark ────────────────────────────────────

    @Test
    void bookmark_success_returnsFullFlagState() throws Exception {
        when(browseService.toggleBookmark("SSIS-001"))
                .thenReturn(new FlagResult.Ok(new TitleFlagState("SSIS-001", false, true, false)));

        HttpResponse<String> res = post("/api/titles/SSIS-001/bookmark");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("SSIS-001", body.get("code").asText());
        assertFalse(body.get("favorite").asBoolean());
        assertTrue(body.get("bookmark").asBoolean());
        assertFalse(body.get("rejected").asBoolean());
    }

    @Test
    void bookmark_notFound_returns404() throws Exception {
        when(browseService.toggleBookmark("MISSING-002"))
                .thenReturn(new FlagResult.NotFound());

        HttpResponse<String> res = post("/api/titles/MISSING-002/bookmark");

        assertEquals(404, res.statusCode());
    }

    @Test
    void bookmark_refusedWhileRejected_returns400() throws Exception {
        when(browseService.toggleBookmark("ABP-003"))
                .thenReturn(new FlagResult.Refused("title is rejected; clear reject first"));

        HttpResponse<String> res = post("/api/titles/ABP-003/bookmark");

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("title is rejected; clear reject first", body.get("error").asText());
    }

    // ── POST /api/titles/{code}/reject ──────────────────────────────────────

    @Test
    void reject_setTrue_clearsFavAndBookmarkInResponse() throws Exception {
        when(browseService.toggleRejected("ABP-004"))
                .thenReturn(new FlagResult.Ok(new TitleFlagState("ABP-004", false, false, true)));

        HttpResponse<String> res = post("/api/titles/ABP-004/reject");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("ABP-004", body.get("code").asText());
        assertFalse(body.get("favorite").asBoolean());
        assertFalse(body.get("bookmark").asBoolean());
        assertTrue(body.get("rejected").asBoolean());
    }

    @Test
    void reject_setFalse_returnsRejectedFalse() throws Exception {
        when(browseService.toggleRejected("ABP-005"))
                .thenReturn(new FlagResult.Ok(new TitleFlagState("ABP-005", false, false, false)));

        HttpResponse<String> res = post("/api/titles/ABP-005/reject");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertFalse(body.get("rejected").asBoolean());
    }

    @Test
    void reject_notFound_returns404() throws Exception {
        when(browseService.toggleRejected("MISSING-003"))
                .thenReturn(new FlagResult.NotFound());

        HttpResponse<String> res = post("/api/titles/MISSING-003/reject");

        assertEquals(404, res.statusCode());
        assertTrue(mapper.readTree(res.body()).has("error"));
    }
}
