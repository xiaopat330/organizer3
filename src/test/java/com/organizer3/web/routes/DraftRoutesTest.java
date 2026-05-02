package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftTitle;
import com.organizer3.javdb.draft.DraftTitleRepository;
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
    private DraftPopulator     populator;
    private ImageFetcher       imageFetcher;
    private DraftTitleRepository draftTitleRepo;
    private DraftCoverScratchStore coverStore;

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

        draftTitleRepo = new DraftTitleRepository(jdbi);
        coverStore     = new DraftCoverScratchStore(dataDir);
        populator      = mock(DraftPopulator.class);
        imageFetcher   = mock(ImageFetcher.class);

        server = new WebServer(0);
        server.registerDraftRoutes(new DraftRoutes(populator, draftTitleRepo, coverStore, imageFetcher));
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

    // ── Helper: insert a draft title row directly ──────────────────────────────

    private long insertDraft(long titleId, String code) {
        DraftTitle draft = DraftTitle.builder()
                .titleId(titleId)
                .code(code)
                .createdAt("2024-01-01T00:00:00Z")
                .updatedAt("2024-01-01T00:00:00Z")
                .build();
        return draftTitleRepo.insert(draft);
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
        byte[] newBytes = {5, 6, 7};
        when(imageFetcher.fetch("https://example.com/new.jpg"))
                .thenReturn(new ImageFetcher.Fetched(newBytes, "jpg"));

        var resp = post("/api/drafts/1/cover/refetch?coverUrl=https://example.com/new.jpg");
        assertEquals(200, resp.statusCode());

        // Cover should now be stored.
        assertTrue(coverStore.exists(draftId));
        assertArrayEquals(newBytes, coverStore.read(draftId).orElseThrow());
    }

    @Test
    void refetchCover_400WhenNoCoverUrl() throws Exception {
        insertDraft(1L, "TST-1");
        var resp = post("/api/drafts/1/cover/refetch");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void refetchCover_404WhenNoDraft() throws Exception {
        var resp = post("/api/drafts/999/cover/refetch?coverUrl=https://example.com/x.jpg");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void refetchCover_502WhenFetchFails() throws Exception {
        insertDraft(1L, "TST-1");
        when(imageFetcher.fetch(anyString())).thenThrow(new RuntimeException("network error"));

        var resp = post("/api/drafts/1/cover/refetch?coverUrl=https://example.com/x.jpg");
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
}
