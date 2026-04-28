package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.web.TitleDiscoveryService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TitleDiscoveryRoutesTest {

    private WebServer server;
    private TitleDiscoveryService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        service = mock(TitleDiscoveryService.class);
        server = new WebServer(0);
        server.registerTitleDiscovery(new TitleDiscoveryRoutes(service));
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

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── GET /api/javdb/discovery/titles ────────────────────────────────────

    @Test
    void getTitles_recentReturnsServicePage() throws Exception {
        var row = new TitleDiscoveryService.TitleRow(
                42L, "ABC-001", "Some Title",
                new TitleDiscoveryService.ActressCredit(7L, "Alice", "eligible"),
                "vol-a", "2026-04-27", null, null);
        when(service.listRecent(0, 50)).thenReturn(
                new TitleDiscoveryService.TitlePage(List.of(row), 0, 50, false, 1));

        HttpResponse<String> res = get("/api/javdb/discovery/titles?source=recent");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(1, body.get("rows").size());
        assertEquals("ABC-001", body.get("rows").get(0).get("code").asText());
        assertEquals("Alice", body.get("rows").get(0).get("actress").get("name").asText());
        assertEquals("eligible", body.get("rows").get(0).get("actress").get("eligibility").asText());
        assertFalse(body.get("hasMore").asBoolean());
        verify(service).listRecent(0, 50);
    }

    @Test
    void getTitles_poolRequiresVolumeId() throws Exception {
        HttpResponse<String> res = get("/api/javdb/discovery/titles?source=pool");

        assertEquals(400, res.statusCode());
        verify(service, never()).listPool(anyString(), anyInt(), anyInt());
    }

    @Test
    void getTitles_poolPassesVolumeId() throws Exception {
        when(service.listPool(eq("pool-jav"), eq(2), eq(25))).thenReturn(
                new TitleDiscoveryService.TitlePage(List.of(), 2, 25, false, 0));

        HttpResponse<String> res = get("/api/javdb/discovery/titles?source=pool&volumeId=pool-jav&page=2&pageSize=25");

        assertEquals(200, res.statusCode());
        verify(service).listPool("pool-jav", 2, 25);
    }

    @Test
    void getTitles_rejectsUnknownSource() throws Exception {
        HttpResponse<String> res = get("/api/javdb/discovery/titles?source=nonsense");
        assertEquals(400, res.statusCode());
    }

    @Test
    void getTitles_collectionReturnsServicePage() throws Exception {
        var castMixed = List.of(
                new TitleDiscoveryService.ActressCredit(7L, "Alice",   "eligible"),
                new TitleDiscoveryService.ActressCredit(8L, "Bob",     "below_threshold"),
                new TitleDiscoveryService.ActressCredit(9L, "Various", "sentinel"));
        var row = new TitleDiscoveryService.CollectionRow(
                42L, "COL-001", "Cool Compilation",
                castMixed, "vol-a", "2026-04-27", null);
        when(service.listCollections(0, 50)).thenReturn(
                new TitleDiscoveryService.CollectionPage(List.of(row), 0, 50, false, 1));

        HttpResponse<String> res = get("/api/javdb/discovery/titles?source=collection");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        var castNode = body.get("rows").get(0).get("cast");
        assertEquals(3, castNode.size());
        assertEquals("eligible",        castNode.get(0).get("eligibility").asText());
        assertEquals("below_threshold", castNode.get(1).get("eligibility").asText());
        assertEquals("sentinel",        castNode.get(2).get("eligibility").asText());
    }

    @Test
    void getTitles_clampsPageSize() throws Exception {
        when(service.listRecent(eq(0), eq(200))).thenReturn(
                new TitleDiscoveryService.TitlePage(List.of(), 0, 200, false, 0));
        // 9999 should be clamped to 200.
        HttpResponse<String> res = get("/api/javdb/discovery/titles?source=recent&pageSize=9999");
        assertEquals(200, res.statusCode());
        verify(service).listRecent(0, 200);
    }

    // ── GET /api/javdb/discovery/titles/pools ──────────────────────────────

    @Test
    void getPools_returnsChips() throws Exception {
        when(service.listPools()).thenReturn(List.of(
                new TitleDiscoveryService.PoolChip("pool-jav", 7),
                new TitleDiscoveryService.PoolChip("pool-av", 0)));

        HttpResponse<String> res = get("/api/javdb/discovery/titles/pools");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(2, body.size());
        assertEquals("pool-jav", body.get(0).get("volumeId").asText());
        assertEquals(7, body.get(0).get("unenrichedCount").asInt());
        assertEquals(0, body.get(1).get("unenrichedCount").asInt());
    }

    // ── POST /api/javdb/discovery/titles/enqueue ───────────────────────────

    @Test
    void postEnqueue_passesPayloadToService() throws Exception {
        when(service.enqueue("recent", List.of(1L, 2L, 3L))).thenReturn(3);

        HttpResponse<String> res = post("/api/javdb/discovery/titles/enqueue",
                "{\"source\":\"recent\",\"titleIds\":[1,2,3]}");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(3, body.get("enqueued").asInt());
        verify(service).enqueue("recent", List.of(1L, 2L, 3L));
    }

    @Test
    void postEnqueue_acceptsCollectionSource() throws Exception {
        when(service.enqueue("collection", List.of(1L, 2L))).thenReturn(2);

        HttpResponse<String> res = post("/api/javdb/discovery/titles/enqueue",
                "{\"source\":\"collection\",\"titleIds\":[1,2]}");

        assertEquals(200, res.statusCode());
        verify(service).enqueue("collection", List.of(1L, 2L));
    }

    @Test
    void postEnqueue_returns400OnInvalidSource() throws Exception {
        when(service.enqueue(anyString(), anyList()))
                .thenThrow(new IllegalArgumentException("source must be 'recent', 'pool', or 'collection'"));

        HttpResponse<String> res = post("/api/javdb/discovery/titles/enqueue",
                "{\"source\":\"nonsense\",\"titleIds\":[1]}");

        assertEquals(400, res.statusCode());
    }

    @Test
    void postEnqueue_returns400OnMissingFields() throws Exception {
        HttpResponse<String> res = post("/api/javdb/discovery/titles/enqueue", "{}");
        assertEquals(400, res.statusCode());
        verify(service, never()).enqueue(anyString(), anyList());
    }
}
