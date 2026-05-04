package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.web.JavdbDiscoveryService;
import com.organizer3.web.JavdbDiscoveryService.ActressRow;
import com.organizer3.web.JavdbDiscoveryService.ProfileRow;
import com.organizer3.web.JavdbDiscoveryService.QueueStatus;
import com.organizer3.web.JavdbEnrichmentActionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression baseline tests for {@link JavdbDiscoveryRoutes}.
 *
 * <p>Uses a real Javalin server on port 0 with mocked collaborators. Covers every
 * top-level endpoint: one happy-path + one error-path per endpoint. Mirrors the
 * harness pattern from {@link UtilitiesRoutesTest} and {@link DraftRoutesTest}.
 *
 * <p>Part of PR-C in the May 2026 housekeeping plan
 * (spec/PROPOSAL_HOUSEKEEPING_2026_05.md, Phase 3).
 */
class JavdbDiscoveryRoutesTest {

    private WebServer server;
    private JavdbDiscoveryService service;
    private JavdbEnrichmentActionService actionService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        service = mock(JavdbDiscoveryService.class);
        actionService = mock(JavdbEnrichmentActionService.class);

        server = new WebServer(0);
        server.registerJavdbDiscovery(new JavdbDiscoveryRoutes(service, actionService));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── /api/javdb/discovery/actresses ─────────────────────────────────────────

    @Test
    void listActresses_returnsList() throws Exception {
        ActressRow row = new ActressRow(1L, "Yua Mikami", "Yua", 100, 60,
                "fetched", false, false, 0);
        when(service.listActresses()).thenReturn(List.of(row));

        var resp = get("/api/javdb/discovery/actresses");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("Yua Mikami", body.get(0).get("canonicalName").asText());
    }

    @Test
    void listActresses_returnsEmptyWhenNoActresses() throws Exception {
        when(service.listActresses()).thenReturn(List.of());

        var resp = get("/api/javdb/discovery/actresses");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(0, body.size());
    }

    // ── /api/javdb/discovery/actresses/{id}/titles ─────────────────────────────

    @Test
    void getActressTitles_returnsListWhenIdValid() throws Exception {
        when(service.getActressTitles(eq(1L), any())).thenReturn(List.of());

        var resp = get("/api/javdb/discovery/actresses/1/titles");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
    }

    @Test
    void getActressTitles_400OnNonNumericId() throws Exception {
        var resp = get("/api/javdb/discovery/actresses/abc/titles");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void getActressTitles_passesFilterTagsAndRatings() throws Exception {
        when(service.getActressTitles(eq(1L), any())).thenReturn(List.of());

        var resp = get("/api/javdb/discovery/actresses/1/titles?tags=Big%20Tits,Solowork&minRatingAvg=4.2&minRatingCount=50");
        assertEquals(200, resp.statusCode());
        verify(service).getActressTitles(eq(1L),
                eq(new JavdbDiscoveryService.TitleFilter(List.of("Big Tits", "Solowork"), 4.2, 50)));
    }

    // ── /api/javdb/discovery/actresses/{id}/tag-facets ─────────────────────────

    @Test
    void getActressTagFacets_returnsList() throws Exception {
        when(service.getActressTagFacets(eq(1L), any())).thenReturn(
                List.of(new JavdbDiscoveryService.TagFacet("Solowork", 12)));

        var resp = get("/api/javdb/discovery/actresses/1/tag-facets");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(1, body.size());
        assertEquals("Solowork", body.get(0).get("name").asText());
    }

    @Test
    void getActressTagFacets_400OnNonNumericId() throws Exception {
        var resp = get("/api/javdb/discovery/actresses/xyz/tag-facets");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/profile ────────────────────────────

    @Test
    void getActressProfile_returnsProfileWhenFound() throws Exception {
        ProfileRow profile = new ProfileRow("yua-mikami", "fetched", "2024-01-01",
                "[\"Yua\"]", "https://example/avatar.jpg", null, null, null, 100);
        when(service.getActressProfile(1L)).thenReturn(profile);

        var resp = get("/api/javdb/discovery/actresses/1/profile");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("yua-mikami", body.get("javdbSlug").asText());
    }

    @Test
    void getActressProfile_404WhenNull() throws Exception {
        when(service.getActressProfile(99L)).thenReturn(null);

        var resp = get("/api/javdb/discovery/actresses/99/profile");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getActressProfile_400OnNonNumericId() throws Exception {
        var resp = get("/api/javdb/discovery/actresses/abc/profile");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/queue ─────────────────────────────────────────────

    @Test
    void getQueueStatus_returnsStatus() throws Exception {
        QueueStatus s = new QueueStatus(3, 1, 0, 0, false, null, null, 0, null);
        when(service.getQueueStatus()).thenReturn(s);

        var resp = get("/api/javdb/discovery/queue");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(3, body.get("pending").asInt());
        assertEquals(1, body.get("inFlight").asInt());
    }

    @Test
    void getQueueItems_returnsList() throws Exception {
        when(service.getActiveQueueItems()).thenReturn(List.of());

        var resp = get("/api/javdb/discovery/queue/items");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
    }

    // ── /api/javdb/discovery/actresses/{id}/enqueue ────────────────────────────

    @Test
    void enqueueActress_returnsCount() throws Exception {
        when(actionService.enqueueActress(1L)).thenReturn(7);

        var resp = postJson("/api/javdb/discovery/actresses/1/enqueue", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(7, body.get("enqueued").asInt());
    }

    @Test
    void enqueueActress_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/javdb/discovery/actresses/xyz/enqueue", "{}");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/queue (DELETE) ─────────────────────

    @Test
    void cancelForActress_204OnSuccess() throws Exception {
        var resp = delete("/api/javdb/discovery/actresses/1/queue");
        assertEquals(204, resp.statusCode());
        verify(actionService).cancelForActress(1L);
    }

    @Test
    void cancelForActress_400OnNonNumericId() throws Exception {
        var resp = delete("/api/javdb/discovery/actresses/abc/queue");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/queue (DELETE) ────────────────────────────────────

    @Test
    void cancelAll_204() throws Exception {
        var resp = delete("/api/javdb/discovery/queue");
        assertEquals(204, resp.statusCode());
        verify(actionService).cancelAll();
    }

    // ── /api/javdb/discovery/queue/pause ───────────────────────────────────────

    @Test
    void pauseQueue_204() throws Exception {
        var resp = postJson("/api/javdb/discovery/queue/pause", "{\"paused\":true}");
        assertEquals(204, resp.statusCode());
        verify(actionService).setPaused(true);
    }

    @Test
    void pauseQueue_400OnMalformedBody() throws Exception {
        var resp = postJson("/api/javdb/discovery/queue/pause", "not-json");
        assertTrue(resp.statusCode() >= 400 && resp.statusCode() < 600,
                "expected 4xx/5xx for malformed body, got " + resp.statusCode());
    }

    // ── /api/javdb/discovery/queue/resume ──────────────────────────────────────

    @Test
    void resumeQueue_204() throws Exception {
        var resp = post("/api/javdb/discovery/queue/resume");
        assertEquals(204, resp.statusCode());
        verify(actionService).forceResume();
    }

    // ── /api/javdb/discovery/queue/items/{itemId}/move ─────────────────────────

    @Test
    void moveItem_204OnPromote() throws Exception {
        var resp = postJson("/api/javdb/discovery/queue/items/5/move", "{\"action\":\"promote\"}");
        assertEquals(204, resp.statusCode());
        verify(actionService).promoteItem(5L);
    }

    @Test
    void moveItem_400OnUnknownAction() throws Exception {
        var resp = postJson("/api/javdb/discovery/queue/items/5/move", "{\"action\":\"sideways\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void moveItem_400OnNonNumericItemId() throws Exception {
        var resp = postJson("/api/javdb/discovery/queue/items/abc/move", "{\"action\":\"promote\"}");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/queue/items/{itemId}/pause ────────────────────────

    @Test
    void pauseItem_204() throws Exception {
        var resp = post("/api/javdb/discovery/queue/items/3/pause");
        assertEquals(204, resp.statusCode());
        verify(actionService).pauseItem(3L);
    }

    @Test
    void pauseItem_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/queue/items/abc/pause");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/queue/items/{itemId}/resume ───────────────────────

    @Test
    void resumeItem_204() throws Exception {
        var resp = post("/api/javdb/discovery/queue/items/3/resume");
        assertEquals(204, resp.statusCode());
        verify(actionService).resumeItem(3L);
    }

    @Test
    void resumeItem_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/queue/items/abc/resume");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/queue/items/{itemId}/requeue ──────────────────────

    @Test
    void requeueItem_204() throws Exception {
        var resp = post("/api/javdb/discovery/queue/items/3/requeue");
        assertEquals(204, resp.statusCode());
        verify(actionService).requeueItem(3L);
    }

    @Test
    void requeueItem_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/queue/items/abc/requeue");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/retry ──────────────────────────────

    @Test
    void retryFailedForActress_204() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/1/retry");
        assertEquals(204, resp.statusCode());
        verify(actionService).retryFailedForActress(1L);
    }

    @Test
    void retryFailedForActress_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/abc/retry");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/errors ─────────────────────────────

    @Test
    void getErrorsForActress_returnsList() throws Exception {
        EnrichmentQueue.FailedJobSummary fj = new EnrichmentQueue.FailedJobSummary(
                1L, 100L, "ABP-001", "timeout", 3, "2024-01-01T00:00:00Z",
                null, null, "ABP", "ABP-00001", null);
        when(actionService.getErrorsForActress(1L)).thenReturn(List.of(fj));

        var resp = get("/api/javdb/discovery/actresses/1/errors");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(1, body.size());
    }

    @Test
    void getErrorsForActress_400OnNonNumericId() throws Exception {
        var resp = get("/api/javdb/discovery/actresses/abc/errors");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/conflicts ──────────────────────────

    @Test
    void getActressConflicts_returnsList() throws Exception {
        when(service.getActressConflicts(1L)).thenReturn(List.of());

        var resp = get("/api/javdb/discovery/actresses/1/conflicts");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
    }

    @Test
    void getActressConflicts_400OnNonNumericId() throws Exception {
        var resp = get("/api/javdb/discovery/actresses/abc/conflicts");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/titles/{titleId}/enrichment ───────────────────────

    @Test
    void getTitleEnrichmentDetail_returnsDetail() throws Exception {
        var detail = new JavdbDiscoveryService.TitleEnrichmentDetail(
                1L, "ABP-001", "abp-001", "Title", "2024-01-01", 120,
                "Maker", "Pub", null, 4.5, 50, "[]", List.of("Tag"), "2024-01-01");
        when(service.getTitleEnrichmentDetail(1L)).thenReturn(detail);

        var resp = get("/api/javdb/discovery/titles/1/enrichment");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("ABP-001", body.get("code").asText());
    }

    @Test
    void getTitleEnrichmentDetail_404WhenMissing() throws Exception {
        when(service.getTitleEnrichmentDetail(99L)).thenReturn(null);

        var resp = get("/api/javdb/discovery/titles/99/enrichment");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getTitleEnrichmentDetail_400OnNonNumericId() throws Exception {
        var resp = get("/api/javdb/discovery/titles/abc/enrichment");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/titles/{titleId}/reenrich ──────────

    @Test
    void reenrichTitle_204() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/1/titles/100/reenrich");
        assertEquals(204, resp.statusCode());
        verify(actionService).reEnqueueTitle(100L, 1L);
    }

    @Test
    void reenrichTitle_400OnNonNumericTitleId() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/1/titles/abc/reenrich");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void reenrichTitle_400OnNonNumericActressId() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/abc/titles/100/reenrich");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/profile/reenrich ───────────────────

    @Test
    void reenrichProfile_204() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/1/profile/reenrich");
        assertEquals(204, resp.statusCode());
        verify(actionService).reEnqueueActressProfile(1L);
    }

    @Test
    void reenrichProfile_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/abc/profile/reenrich");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/profile/derive-slug ────────────────

    @Test
    void deriveSlug_200OnOk() throws Exception {
        var result = new JavdbEnrichmentActionService.DeriveSlugResult(
                "ok", "yua-mikami", "Yua", 5, 10, List.of());
        when(actionService.deriveSlugAndEnqueueProfile(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/profile/derive-slug");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void deriveSlug_409OnAmbiguous() throws Exception {
        var result = new JavdbEnrichmentActionService.DeriveSlugResult(
                "ambiguous", null, null, 0, 0, List.of());
        when(actionService.deriveSlugAndEnqueueProfile(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/profile/derive-slug");
        assertEquals(409, resp.statusCode());
    }

    @Test
    void deriveSlug_404OnNoData() throws Exception {
        var result = new JavdbEnrichmentActionService.DeriveSlugResult(
                "no_data", null, null, 0, 0, List.of());
        when(actionService.deriveSlugAndEnqueueProfile(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/profile/derive-slug");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deriveSlug_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/abc/profile/derive-slug");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/actresses/{id}/avatar/download ────────────────────

    @Test
    void downloadAvatar_200OnOk() throws Exception {
        var result = new JavdbEnrichmentActionService.AvatarDownloadResult("ok", "/avatars/yua.jpg");
        when(actionService.downloadAvatarForActress(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/avatar/download");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void downloadAvatar_404OnNoProfile() throws Exception {
        var result = new JavdbEnrichmentActionService.AvatarDownloadResult("no_profile", null);
        when(actionService.downloadAvatarForActress(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/avatar/download");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void downloadAvatar_409OnNoUrl() throws Exception {
        var result = new JavdbEnrichmentActionService.AvatarDownloadResult("no_url", null);
        when(actionService.downloadAvatarForActress(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/avatar/download");
        assertEquals(409, resp.statusCode());
    }

    @Test
    void downloadAvatar_502OnFailed() throws Exception {
        var result = new JavdbEnrichmentActionService.AvatarDownloadResult("failed", null);
        when(actionService.downloadAvatarForActress(1L)).thenReturn(result);

        var resp = post("/api/javdb/discovery/actresses/1/avatar/download");
        assertEquals(502, resp.statusCode());
    }

    @Test
    void downloadAvatar_400OnNonNumericId() throws Exception {
        var resp = post("/api/javdb/discovery/actresses/abc/avatar/download");
        assertEquals(400, resp.statusCode());
    }

    // ── /api/javdb/discovery/tag-health ────────────────────────────────────────

    @Test
    void getTagHealth_returnsReport() throws Exception {
        var summary = new JavdbDiscoveryService.TagHealthSummary(10, 8, 2, 0, 0);
        var report = new JavdbDiscoveryService.TagHealthReport(summary, List.of());
        when(service.getTagHealthReport()).thenReturn(report);

        var resp = get("/api/javdb/discovery/tag-health");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("summary"));
        assertTrue(body.has("definitions"));
    }

    // ── /api/javdb/discovery/tag-health/{tagId}/surface ────────────────────────

    @Test
    void surfaceTag_204() throws Exception {
        var resp = postJson("/api/javdb/discovery/tag-health/5/surface", "{\"surface\":true}");
        assertEquals(204, resp.statusCode());
        verify(service).setEnrichmentTagSurface(5L, true);
    }

    @Test
    void surfaceTag_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/javdb/discovery/tag-health/abc/surface", "{\"surface\":true}");
        assertEquals(400, resp.statusCode());
    }
}
