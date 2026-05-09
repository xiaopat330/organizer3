package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Route integration tests for {@code GET /api/actresses/{id}/admin-titles}.
 */
class AdminTitlesRoutesTest {

    private WebServer server;
    private ActressBrowseService actressBrowseService;
    private TitleBrowseService titleBrowseService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        actressBrowseService = mock(ActressBrowseService.class);
        titleBrowseService   = mock(TitleBrowseService.class);

        // AppConfig.get() is called in the route handler to resolve pageSize
        AppConfig.initializeForTest(new OrganizerConfig(
                "Test", null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null));

        server = new WebServer(0);
        server.registerActressRoutes(new ActressRoutes(actressBrowseService, titleBrowseService));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        AppConfig.reset();
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void happyPath_returnsExpectedShape() throws Exception {
        when(actressBrowseService.findById(1L))
                .thenReturn(java.util.Optional.of(mock(com.organizer3.web.ActressSummary.class)));
        TitleBrowseService.AdminTitlesPage fakePage =
                new TitleBrowseService.AdminTitlesPage(List.of(), 1, 0, 5);
        when(titleBrowseService.findAdminTitlesPaged(1L, 1, 5)).thenReturn(fakePage);

        HttpResponse<String> res = get("/api/actresses/1/admin-titles");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("titles"),     "must have 'titles' field");
        assertTrue(body.has("page"),       "must have 'page' field");
        assertTrue(body.has("totalPages"), "must have 'totalPages' field");
        assertTrue(body.has("pageSize"),   "must have 'pageSize' field");
        assertTrue(body.get("titles").isArray());
        assertEquals(1, body.get("page").asInt());
        assertEquals(0, body.get("totalPages").asInt());
        assertEquals(5, body.get("pageSize").asInt());
    }

    @Test
    void pageQueryParamIsRespected() throws Exception {
        when(actressBrowseService.findById(2L))
                .thenReturn(java.util.Optional.of(mock(com.organizer3.web.ActressSummary.class)));
        TitleBrowseService.AdminTitlesPage fakePage =
                new TitleBrowseService.AdminTitlesPage(List.of(), 3, 7, 5);
        when(titleBrowseService.findAdminTitlesPaged(2L, 3, 5)).thenReturn(fakePage);

        HttpResponse<String> res = get("/api/actresses/2/admin-titles?page=3");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(3, body.get("page").asInt());
        assertEquals(7, body.get("totalPages").asInt());
        // verify the service was called with page=3
        verify(titleBrowseService).findAdminTitlesPaged(2L, 3, 5);
    }

    // ── 404 for unknown actress ──────────────────────────────────────────────

    @Test
    void unknownActressReturns404() throws Exception {
        when(actressBrowseService.findById(999L)).thenReturn(java.util.Optional.empty());

        HttpResponse<String> res = get("/api/actresses/999/admin-titles");

        assertEquals(404, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("error"), "404 response must include 'error' field");
    }
}
