package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftActress;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftEnrichment;
import com.organizer3.javdb.draft.DraftNotFoundException;
import com.organizer3.javdb.draft.DraftPatchService;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPromotionService;
import com.organizer3.javdb.draft.DraftTitle;
import com.organizer3.javdb.draft.DraftTitleActress;
import com.organizer3.javdb.draft.DraftTitleActressesRepository;
import com.organizer3.javdb.draft.DraftTitleEnrichmentRepository;
import com.organizer3.javdb.draft.DraftTitleRepository;
import com.organizer3.javdb.draft.OptimisticLockException;
import com.organizer3.javdb.draft.PreFlightFailedException;
import com.organizer3.javdb.draft.PreFlightResult;
import com.organizer3.javdb.draft.PromotionException;
import com.organizer3.web.ImageFetcher;
import com.organizer3.web.WebServer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link DraftRoutes}.
 *
 * <p>Uses a real Javalin server on port 0, a real in-memory SQLite
 * {@link DraftTitleRepository} and {@link DraftCoverScratchStore}, and a
 * mock {@link DraftPopulator} and {@link ImageFetcher}.
 */
class DraftRoutesTest {

    @TempDir
    Path dataDir;

    private WebServer server;
    private DraftPopulator                  populator;
    private ImageFetcher                    imageFetcher;
    private DraftTitleRepository            draftTitleRepo;
    private DraftTitleEnrichmentRepository  draftEnrichRepo;
    private DraftTitleActressesRepository   draftTitleActressesRepo;
    private DraftActressRepository          draftActressRepo;
    private DraftCoverScratchStore          coverStore;
    private DraftPromotionService           promotionService;
    private DraftPatchService               patchService;

    private Connection connection;
    private Jdbi jdbi;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   http   = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        // In-memory SQLite
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        // Seed canonical titles row required by draft_titles FK.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)"));

        draftTitleRepo          = new DraftTitleRepository(jdbi);
        draftEnrichRepo         = new DraftTitleEnrichmentRepository(jdbi);
        draftTitleActressesRepo = new DraftTitleActressesRepository(jdbi);
        draftActressRepo        = new DraftActressRepository(jdbi);
        coverStore              = new DraftCoverScratchStore(dataDir);
        populator               = mock(DraftPopulator.class);
        imageFetcher            = mock(ImageFetcher.class);
        promotionService        = mock(DraftPromotionService.class);
        patchService            = new DraftPatchService(jdbi, draftTitleRepo, draftActressRepo,
                                                        draftTitleActressesRepo);

        // Seed a sentinel actress (needed for PATCH sentinel tests).
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) " +
                "VALUES (99, 'Various', 'S', '2024-01-01', 1)"));

        server = new WebServer(0);
        server.registerDraftRoutes(new DraftRoutes(
                populator, draftTitleRepo, draftEnrichRepo,
                draftTitleActressesRepo, draftActressRepo,
                coverStore, imageFetcher, promotionService, patchService,
                new ObjectMapper(), jdbi));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (connection != null) connection.close();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithQuery(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long insertDraft(long titleId, String code) {
        DraftTitle draft = DraftTitle.builder()
                .titleId(titleId)
                .code(code)
                .createdAt("2024-01-01T00:00:00Z")
                .updatedAt("2024-01-01T00:00:00Z")
                .build();
        return draftTitleRepo.insert(draft);
    }

    private void insertEnrichment(long draftId, String coverUrl) {
        DraftEnrichment enrichment = DraftEnrichment.builder()
                .draftTitleId(draftId)
                .javdbSlug("tst-1")
                .coverUrl(coverUrl)
                .updatedAt("2024-01-01T00:00:00Z")
                .build();
        draftEnrichRepo.upsert(draftId, enrichment);
    }

    // ── POST /api/drafts/:titleId/populate ────────────────────────────────────

    @Test
    void populate_201OnCreated() throws Exception {
        when(populator.populate(1L))
                .thenReturn(new DraftPopulator.PopulateResult(DraftPopulator.Status.CREATED, 42L));

        var resp = post("/api/drafts/1/populate");
        assertEquals(201, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(42, body.get("draftTitleId").asInt());
    }

    @Test
    void populate_409OnAlreadyExists() throws Exception {
        when(populator.populate(1L))
                .thenReturn(new DraftPopulator.PopulateResult(DraftPopulator.Status.ALREADY_EXISTS, null));

        var resp = post("/api/drafts/1/populate");
        assertEquals(409, resp.statusCode());
    }

    @Test
    void populate_404OnTitleNotFound() throws Exception {
        when(populator.populate(99L))
                .thenReturn(new DraftPopulator.PopulateResult(DraftPopulator.Status.TITLE_NOT_FOUND, null));

        var resp = post("/api/drafts/99/populate");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void populate_422OnJavdbNotFound() throws Exception {
        when(populator.populate(1L))
                .thenReturn(new DraftPopulator.PopulateResult(DraftPopulator.Status.JAVDB_NOT_FOUND, null));

        var resp = post("/api/drafts/1/populate");
        assertEquals(422, resp.statusCode());
    }

    @Test
    void populate_502OnJavdbError() throws Exception {
        when(populator.populate(1L))
                .thenReturn(new DraftPopulator.PopulateResult(DraftPopulator.Status.JAVDB_ERROR, null));

        var resp = post("/api/drafts/1/populate");
        assertEquals(502, resp.statusCode());
    }

    @Test
    void populate_400OnNonNumericTitleId() throws Exception {
        var resp = post("/api/drafts/abc/populate");
        assertEquals(400, resp.statusCode());
    }

    // ── GET /api/drafts/:titleId/cover ────────────────────────────────────────

    @Test
    void getCover_returnsBytesWhenPresent() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        byte[] imgBytes = {1, 2, 3, 4};
        coverStore.write(draftId, imgBytes);

        var resp = getBytes("/api/drafts/1/cover");
        assertEquals(200, resp.statusCode());
        assertEquals("image/jpeg", resp.headers().firstValue("Content-Type").orElse(""));
        assertArrayEquals(imgBytes, resp.body());
    }

    @Test
    void getCover_404WhenNoDraft() throws Exception {
        var resp = getBytes("/api/drafts/999/cover");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getCover_404WhenCoverNotStored() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = getBytes("/api/drafts/1/cover");
        assertEquals(404, resp.statusCode());
    }

    // ── POST /api/drafts/:titleId/cover/refetch ───────────────────────────────

    @Test
    void refetchCover_200OnSuccess() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        insertEnrichment(draftId, "https://example.com/new.jpg");
        byte[] newBytes = {5, 6, 7};
        when(imageFetcher.fetch("https://example.com/new.jpg"))
                .thenReturn(new ImageFetcher.Fetched(newBytes, "jpg"));

        var resp = post("/api/drafts/1/cover/refetch");
        assertEquals(200, resp.statusCode());

        // Cover should now be stored.
        assertTrue(coverStore.exists(draftId));
        assertArrayEquals(newBytes, coverStore.read(draftId).orElseThrow());
    }

    @Test
    void refetchCover_422WhenNoCoverUrlOnFile() throws Exception {
        // Draft exists but no enrichment row (no cover_url stored) → 422.
        insertDraft(1L, "TST-1");
        var resp = post("/api/drafts/1/cover/refetch");
        assertEquals(422, resp.statusCode());
    }

    @Test
    void refetchCover_422WhenEnrichmentHasNullCoverUrl() throws Exception {
        // Enrichment row exists but cover_url is null → 422.
        long draftId = insertDraft(1L, "TST-1");
        insertEnrichment(draftId, null);
        var resp = post("/api/drafts/1/cover/refetch");
        assertEquals(422, resp.statusCode());
    }

    @Test
    void refetchCover_404WhenNoDraft() throws Exception {
        var resp = post("/api/drafts/999/cover/refetch");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void refetchCover_502WhenFetchFails() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        insertEnrichment(draftId, "https://example.com/x.jpg");
        when(imageFetcher.fetch(anyString())).thenThrow(new RuntimeException("network error"));

        var resp = post("/api/drafts/1/cover/refetch");
        assertEquals(502, resp.statusCode());
    }

    // ── DELETE /api/drafts/:titleId/cover ─────────────────────────────────────

    @Test
    void deleteCover_204RemovesFile() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        coverStore.write(draftId, new byte[]{9});

        var resp = delete("/api/drafts/1/cover");
        assertEquals(204, resp.statusCode());
        assertFalse(coverStore.exists(draftId));
    }

    @Test
    void deleteCover_204WhenNoCoverFile() throws Exception {
        insertDraft(1L, "TST-1");
        // No cover written — delete should still succeed (no-op).
        var resp = delete("/api/drafts/1/cover");
        assertEquals(204, resp.statusCode());
    }

    @Test
    void deleteCover_404WhenNoDraft() throws Exception {
        var resp = delete("/api/drafts/999/cover");
        assertEquals(404, resp.statusCode());
    }

    // ── POST /api/drafts/:titleId/validate ────────────────────────────────────

    @Test
    void validate_200OkWhenPreflightSucceeds() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        when(promotionService.preflight(draftId, null)).thenReturn(PreFlightResult.success());

        var resp = postJson("/api/drafts/1/validate", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("ok").asBoolean());
        assertTrue(body.get("errors").isEmpty());
    }

    @Test
    void validate_200WithErrorsWhenPreflightFails() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        when(promotionService.preflight(draftId, null))
                .thenReturn(PreFlightResult.failure("CAST_MODE_VIOLATION"));

        var resp = postJson("/api/drafts/1/validate", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("ok").asBoolean());
        assertEquals("CAST_MODE_VIOLATION", body.get("errors").get(0).asText());
    }

    @Test
    void validate_404WhenNoDraft() throws Exception {
        var resp = postJson("/api/drafts/999/validate", "{}");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void validate_400WhenTitleIdInvalid() throws Exception {
        var resp = postJson("/api/drafts/abc/validate", "{}");
        assertEquals(400, resp.statusCode());
    }

    // ── POST /api/drafts/:titleId/promote ─────────────────────────────────────

    @Test
    void promote_200OnSuccess() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        when(promotionService.promote(draftId, "token123")).thenReturn(1L);

        var resp = postJson("/api/drafts/1/promote",
                "{\"expectedUpdatedAt\":\"token123\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(1, body.get("titleId").asInt());
    }

    @Test
    void promote_404WhenNoDraft() throws Exception {
        var resp = postJson("/api/drafts/999/promote", "{\"expectedUpdatedAt\":\"t\"}");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void promote_409OnOptimisticLockConflict() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        when(promotionService.promote(draftId, "old-token"))
                .thenThrow(new OptimisticLockException("conflict"));

        var resp = postJson("/api/drafts/1/promote", "{\"expectedUpdatedAt\":\"old-token\"}");
        assertEquals(409, resp.statusCode());
    }

    @Test
    void promote_422OnPreflightFailure() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        when(promotionService.promote(draftId, null))
                .thenThrow(new PreFlightFailedException(java.util.List.of("UPSTREAM_CHANGED")));

        var resp = postJson("/api/drafts/1/promote", "{}");
        assertEquals(422, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("ok").asBoolean());
    }

    @Test
    void promote_500OnPromotionException() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        when(promotionService.promote(draftId, null))
                .thenThrow(new PromotionException("cover_copy_failed", "disk full"));

        var resp = postJson("/api/drafts/1/promote", "{}");
        assertEquals(500, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("cover_copy_failed", body.get("error").asText());
    }

    @Test
    void promote_400OnInvalidBody() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = postJson("/api/drafts/1/promote", "not-json");
        assertEquals(400, resp.statusCode());
    }

    // ── GET /api/drafts (list all active drafts) ──────────────────────────────

    @Test
    void listDrafts_emptyWhenNoDrafts() throws Exception {
        var resp = get("/api/drafts");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    @Test
    void listDrafts_returnsDraftRows() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = get("/api/drafts");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals(1, body.get(0).get("titleId").asInt());
        assertEquals("TST-1", body.get(0).get("code").asText());
        assertNotNull(body.get(0).get("updatedAt").asText());
    }

    // ── GET /api/drafts/:titleId ──────────────────────────────────────────────

    @Test
    void getDraft_404WhenNoDraft() throws Exception {
        var resp = get("/api/drafts/999");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getDraft_400OnNonNumericTitleId() throws Exception {
        var resp = get("/api/drafts/abc");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void getDraft_returnsAggregateWhenDraftExists() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        insertEnrichment(draftId, "https://example.com/cover.jpg");

        // Insert a cast slot.
        DraftActress actress = DraftActress.builder()
                .javdbSlug("aaa1").stageName("天海 麗")
                .createdAt("2024-01-01T00:00:00Z").updatedAt("2024-01-01T00:00:00Z")
                .build();
        draftActressRepo.upsertBySlug(actress);
        draftTitleActressesRepo.replaceForDraft(draftId, java.util.List.of(
                new DraftTitleActress(draftId, "aaa1", "unresolved")));

        var resp = get("/api/drafts/1");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(1,       body.get("titleId").asInt());
        assertEquals("TST-1", body.get("code").asText());
        assertFalse(body.get("upstreamChanged").asBoolean());
        assertNotNull(body.get("updatedAt").asText());

        // Enrichment sub-object.
        assertTrue(body.has("enrichment"));
        assertEquals("https://example.com/cover.jpg", body.get("enrichment").get("coverUrl").asText());

        // Cast array.
        assertTrue(body.has("cast"));
        assertEquals(1, body.get("cast").size());
        JsonNode slot = body.get("cast").get(0);
        assertEquals("aaa1",       slot.get("javdbSlug").asText());
        assertEquals("unresolved", slot.get("resolution").asText());
        assertEquals("天海 麗",     slot.get("stageName").asText());

        // No scratch cover written yet.
        assertFalse(body.get("coverScratchPresent").asBoolean());
    }

    @Test
    void getDraft_coverScratchPresentTrue_whenCoverWritten() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        coverStore.write(draftId, new byte[]{1, 2, 3});

        var resp = get("/api/drafts/1");
        assertEquals(200, resp.statusCode());
        assertTrue(mapper.readTree(resp.body()).get("coverScratchPresent").asBoolean());
    }

    // ── PATCH /api/drafts/:titleId ────────────────────────────────────────────

    @Test
    void patchDraft_404WhenNoDraft() throws Exception {
        var resp = patchJson("/api/drafts/999",
                "{\"expectedUpdatedAt\":null,\"castResolutions\":[],\"newActresses\":[]}");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void patchDraft_400OnInvalidBody() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = patchJson("/api/drafts/1", "not-json");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void patchDraft_400OnMissingJavdbSlug() throws Exception {
        insertDraft(1L, "TST-1");
        // castResolution without javdbSlug.
        var resp = patchJson("/api/drafts/1",
                "{\"castResolutions\":[{\"resolution\":\"skip\"}]}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void patchDraft_400OnValidationFailure_pickMissingLink() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        DraftActress a = DraftActress.builder()
                .javdbSlug("sl1").stageName("X")
                .createdAt("2024-01-01T00:00:00Z").updatedAt("2024-01-01T00:00:00Z")
                .build();
        draftActressRepo.upsertBySlug(a);
        draftTitleActressesRepo.replaceForDraft(draftId, java.util.List.of(
                new DraftTitleActress(draftId, "sl1", "unresolved")));

        var resp = patchJson("/api/drafts/1",
                "{\"castResolutions\":[{\"javdbSlug\":\"sl1\",\"resolution\":\"pick\"}]}");
        assertEquals(400, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("errors"));
    }

    @Test
    void patchDraft_409OnOptimisticLockConflict() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = patchJson("/api/drafts/1",
                "{\"expectedUpdatedAt\":\"stale-token\",\"castResolutions\":[]}");
        assertEquals(409, resp.statusCode());
    }

    @Test
    void patchDraft_200ReturnsNewToken() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        DraftActress a = DraftActress.builder()
                .javdbSlug("p1").stageName("P")
                .createdAt("2024-01-01T00:00:00Z").updatedAt("2024-01-01T00:00:00Z")
                .build();
        draftActressRepo.upsertBySlug(a);
        draftTitleActressesRepo.replaceForDraft(draftId, java.util.List.of(
                new DraftTitleActress(draftId, "p1", "unresolved")));

        // Seed a real actress for pick.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (42, 'Pick Me', 'S', '2024-01-01')"));

        var resp = patchJson("/api/drafts/1",
                "{\"expectedUpdatedAt\":\"2024-01-01T00:00:00Z\"," +
                "\"castResolutions\":[{\"javdbSlug\":\"p1\",\"resolution\":\"pick\",\"linkToExistingId\":42}]}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("updatedAt"));
        // Token must have changed.
        assertNotEquals("2024-01-01T00:00:00Z", body.get("updatedAt").asText());
    }

    // ── DELETE /api/drafts/:titleId ───────────────────────────────────────────

    @Test
    void deleteDraft_204DropsDraftAndCover() throws Exception {
        long draftId = insertDraft(1L, "TST-1");
        coverStore.write(draftId, new byte[]{5, 6, 7});

        var resp = delete("/api/drafts/1");
        assertEquals(204, resp.statusCode());

        // Draft gone.
        assertTrue(draftTitleRepo.findByTitleId(1L).isEmpty());
        // Cover gone.
        assertFalse(coverStore.exists(draftId));
    }

    @Test
    void deleteDraft_204WhenNoCover() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = delete("/api/drafts/1");
        assertEquals(204, resp.statusCode());
        assertTrue(draftTitleRepo.findByTitleId(1L).isEmpty());
    }

    @Test
    void deleteDraft_404WhenNoDraft() throws Exception {
        var resp = delete("/api/drafts/999");
        assertEquals(404, resp.statusCode());
    }

    // ── Helper for JSON body POST ─────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(String path, String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
