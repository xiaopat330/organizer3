package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.HealthStatus;
import com.organizer3.translation.TranslationRequest;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.TranslationServiceStats;
import com.organizer3.translation.TranslationStrategy;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import com.organizer3.web.WebServer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranslationRoutesTest {

    private WebServer server;
    private TranslationService service;
    private TranslationStrategyRepository strategyRepo;
    private TranslationCacheRepository cacheRepo;
    private TranslationQueueRepository queueRepo;
    private Connection connection;
    private Jdbi jdbi;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        service      = mock(TranslationService.class);
        strategyRepo = mock(TranslationStrategyRepository.class);
        cacheRepo    = mock(TranslationCacheRepository.class);
        queueRepo    = mock(TranslationQueueRepository.class);

        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        server = new WebServer(0);
        server.registerTranslation(new TranslationRoutes(service, strategyRepo, cacheRepo, queueRepo, jdbi));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (connection != null) connection.close();
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

    private HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── GET /api/translation/stats ────────────────────────────────────────────

    @Test
    void getStats_returnsEnrichedStats() throws Exception {
        when(service.stats()).thenReturn(new TranslationServiceStats(
                100L, 80L, 20L, 3, 1, 95, 2, 0, 28L, 5L));
        when(cacheRepo.recentThroughputCount(any(Duration.class))).thenReturn(42L);

        HttpResponse<String> res = get("/api/translation/stats");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertEquals(100, node.get("cacheTotal").asInt());
        assertEquals(80, node.get("cacheSuccessful").asInt());
        assertEquals(42, node.get("throughputLastHour").asInt());
        assertTrue(node.has("topN"));
        assertEquals(0, node.get("topN").size());
    }

    @Test
    void getStats_500WhenServiceThrows() throws Exception {
        when(service.stats()).thenThrow(new RuntimeException("db down"));

        HttpResponse<String> res = get("/api/translation/stats");
        assertEquals(500, res.statusCode());
    }

    // ── GET /api/translation/strategies ──────────────────────────────────────

    @Test
    void getStrategies_returnsListFromRepo() throws Exception {
        TranslationStrategy s = new TranslationStrategy(
                1L, "label_basic", "gemma4:e4b", "Translate: {jp}", null, true, null);
        when(strategyRepo.findAllActive()).thenReturn(List.of(s));

        HttpResponse<String> res = get("/api/translation/strategies");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("label_basic", node.get(0).get("name").asText());
    }

    // ── GET /api/translation/recent-failures ─────────────────────────────────

    @Test
    void getRecentFailures_returnsRows() throws Exception {
        when(cacheRepo.findRecentFailures(20)).thenReturn(List.of());

        HttpResponse<String> res = get("/api/translation/recent-failures");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertTrue(node.isArray());
    }

    @Test
    void getRecentFailures_invalidLimit_returns400() throws Exception {
        HttpResponse<String> res = get("/api/translation/recent-failures?limit=abc");
        assertEquals(400, res.statusCode());
    }

    @Test
    void getRecentFailures_limitOutOfRange_returns400() throws Exception {
        HttpResponse<String> res = get("/api/translation/recent-failures?limit=0");
        assertEquals(400, res.statusCode());
    }

    // ── POST /api/translation/manual ─────────────────────────────────────────

    @Test
    void manualTranslate_successReturnsEnglishText() throws Exception {
        when(service.requestTranslationSync(any(TranslationRequest.class))).thenReturn("Creampie");

        HttpResponse<String> res = post("/api/translation/manual",
                Map.of("sourceText", "中出し"));
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertTrue(node.get("success").asBoolean());
        assertEquals("Creampie", node.get("englishText").asText());
    }

    @Test
    void manualTranslate_nullResultReturns422() throws Exception {
        when(service.requestTranslationSync(any(TranslationRequest.class))).thenReturn(null);

        HttpResponse<String> res = post("/api/translation/manual",
                Map.of("sourceText", "中出し"));
        assertEquals(422, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertFalse(node.get("success").asBoolean());
    }

    @Test
    void manualTranslate_missingSourceTextReturns400() throws Exception {
        HttpResponse<String> res = post("/api/translation/manual", Map.of());
        assertEquals(400, res.statusCode());
    }

    @Test
    void manualTranslate_blankSourceTextReturns400() throws Exception {
        HttpResponse<String> res = post("/api/translation/manual",
                Map.of("sourceText", "   "));
        assertEquals(400, res.statusCode());
    }

    @Test
    void manualTranslate_passesContextHint() throws Exception {
        when(service.requestTranslationSync(any(TranslationRequest.class))).thenReturn("OK");

        HttpResponse<String> res = post("/api/translation/manual",
                Map.of("sourceText", "テスト", "contextHint", "prose"));
        assertEquals(200, res.statusCode());

        // Verify the contextHint was forwarded
        verify(service).requestTranslationSync(argThat(req ->
                "prose".equals(req.contextHint()) && "テスト".equals(req.sourceText())));
    }

    // ── POST /api/translation/bulk ────────────────────────────────────────────

    @Test
    void bulkSubmit_enqueuedCorrectly() throws Exception {
        when(service.requestTranslation(any(TranslationRequest.class))).thenReturn(1L);

        HttpResponse<String> res = post("/api/translation/bulk", Map.of(
                "items", List.of(
                        Map.of("sourceText", "美少女", "contextHint", "label_basic"),
                        Map.of("sourceText", "中出し", "callbackKind", "title_javdb_enrichment.title_original_en", "callbackId", 42)
                )
        ));
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertEquals(2, node.get("enqueued").asInt());
        assertEquals(0, node.get("skipped").asInt());
    }

    @Test
    void bulkSubmit_blankItemsSkipped() throws Exception {
        HttpResponse<String> res = post("/api/translation/bulk", Map.of(
                "items", List.of(Map.of("sourceText", "  "))
        ));
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertEquals(0, node.get("enqueued").asInt());
        assertEquals(1, node.get("skipped").asInt());
    }

    @Test
    void bulkSubmit_noItemsReturns400() throws Exception {
        HttpResponse<String> res = post("/api/translation/bulk", Map.of("items", List.of()));
        assertEquals(400, res.statusCode());
    }

    @Test
    void bulkSubmit_missingItemsReturns400() throws Exception {
        HttpResponse<String> res = post("/api/translation/bulk", Map.of());
        assertEquals(400, res.statusCode());
    }

    // ── GET /api/translation/health ───────────────────────────────────────────

    @Test
    void getHealth_returnsHealthyStatus() throws Exception {
        when(service.getHealth()).thenReturn(HealthStatus.healthy(5000L));

        HttpResponse<String> res = get("/api/translation/health");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertTrue(node.get("ollamaReachable").asBoolean());
        assertTrue(node.get("tier1ModelPresent").asBoolean());
        assertTrue(node.get("overall").asBoolean());
        assertEquals("OK", node.get("message").asText());
    }

    @Test
    void getHealth_returnsUnhealthyStatus() throws Exception {
        when(service.getHealth()).thenReturn(HealthStatus.ollamaDown());

        HttpResponse<String> res = get("/api/translation/health");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertFalse(node.get("ollamaReachable").asBoolean());
        assertFalse(node.get("overall").asBoolean());
    }

    // ── GET /api/translation/bulk-candidates ─────────────────────────────────

    @Test
    void getBulkCandidates_emptyWhenNoEnrichmentRows() throws Exception {
        HttpResponse<String> res = get("/api/translation/bulk-candidates");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertTrue(node.isArray());
        assertEquals(0, node.size());
    }

    @Test
    void getBulkCandidates_returnsTitleIdAndOriginal() throws Exception {
        // Insert a title row and an enrichment row with title_original but no title_original_en
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles (id, code, title_original) VALUES (1, 'ABP-001', '美少女')");
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, title_original) "
                    + "VALUES (1, 'abp-001', '2026-01-01T00:00:00.000Z', '美少女')");
        });

        HttpResponse<String> res = get("/api/translation/bulk-candidates");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals(1, node.get(0).get("titleId").asInt());
        assertEquals("美少女", node.get(0).get("titleOriginal").asText());
    }

    @Test
    void getBulkCandidates_excludesRowsWithExistingTranslation() throws Exception {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles (id, code, title_original) VALUES (2, 'ABP-002', 'テスト')");
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, title_original, title_original_en) "
                    + "VALUES (2, 'abp-002', '2026-01-01T00:00:00.000Z', 'テスト', 'Test')");
        });

        HttpResponse<String> res = get("/api/translation/bulk-candidates");
        assertEquals(200, res.statusCode());

        JsonNode node = mapper.readTree(res.body());
        // Row already has translation — should be excluded
        assertEquals(0, node.size());
    }
}
