package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.enrichment.ai.EnrichmentAssistSweeper;
import com.organizer3.enrichment.ai.EnrichmentAutoApplier;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.utilities.task.TaskRunner;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiAssistDashboardRoutesTest {

    private WebServer server;
    private OllamaModelOrchestrator orchestrator;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private Jdbi jdbi;
    private Connection connection;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);

        // Seed one title
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)"));

        orchestrator = mock(OllamaModelOrchestrator.class);
        when(orchestrator.getQueueDepths())
                .thenReturn(new OllamaModelOrchestrator.QueueDepths(2, 5));

        TaskRunner taskRunner = mock(TaskRunner.class);
        EnrichmentAutoApplier autoApplier = mock(EnrichmentAutoApplier.class);
        EnrichmentAssistSweeper sweeper = mock(EnrichmentAssistSweeper.class);

        server = new WebServer(0);
        server.registerAiAssistDashboard(
                new AiAssistDashboardRoutes(orchestrator, reviewQueueRepo, taskRunner, autoApplier, sweeper));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (connection != null) connection.close();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── /dashboard ─────────────────────────────────────────────────────────────

    @Test
    void dashboard_returnsAllCountFields() throws Exception {
        // One open awaiting-AI row
        reviewQueueRepo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");

        HttpResponse<String> res = get("/api/enrichment/assist/dashboard");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(1, body.get("awaitingAi").asInt(),         "awaiting AI");
        assertEquals(2, body.get("inFlight").asInt(),           "orchestrator inFlight");
        assertEquals(5, body.get("orchestratorQueued").asInt(), "orchestrator queued");
        assertEquals(0, body.get("processedTotal").asInt(),     "none processed yet");
        assertEquals(0, body.get("autoApplied").asInt(),        "none auto-applied");
        assertTrue(body.has("outcomeCounts"), "outcomeCounts key must be present");
    }

    // ── /queue-preview ─────────────────────────────────────────────────────────

    @Test
    void queuePreview_returnsAwaitingRows() throws Exception {
        reviewQueueRepo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");

        HttpResponse<String> res = get("/api/enrichment/assist/queue-preview");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        JsonNode row = body.get(0);
        assertEquals(1, row.get("titleId").asLong());
        assertEquals("T-1", row.get("code").asText());
        assertTrue(row.has("reviewQueueId"));
        assertTrue(row.has("createdAt"));
    }

    @Test
    void queuePreview_defaultLimitAndClamp() throws Exception {
        // limit=0 should be clamped to 1
        HttpResponse<String> res = get("/api/enrichment/assist/queue-preview?limit=0");
        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
    }

    // ── /recent ────────────────────────────────────────────────────────────────

    @Test
    void recent_returnsProcessedRows() throws Exception {
        reviewQueueRepo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long rowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE enrichment_review_queue
                SET ai_suggestion_slug='s1', ai_suggestion_confidence='agreed',
                    ai_suggestion_reason='test', ai_suggestion_at='2026-05-20T10:00:00Z'
                WHERE id = :id
                """).bind("id", rowId).execute());

        HttpResponse<String> res = get("/api/enrichment/assist/recent");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        JsonNode row = body.get(0);
        assertEquals("T-1",    row.get("code").asText());
        assertEquals("agreed", row.get("outcome").asText());
        assertEquals("s1",     row.get("slug").asText());
        assertFalse(row.get("autoApplied").asBoolean());
        assertEquals("2026-05-20T10:00:00Z", row.get("at").asText());
    }

    @Test
    void recent_sinceFilter_worksViaQueryParam() throws Exception {
        reviewQueueRepo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long rowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE enrichment_review_queue
                SET ai_suggestion_slug='s1', ai_suggestion_confidence='agreed',
                    ai_suggestion_reason='test', ai_suggestion_at='2026-05-20T10:00:00Z'
                WHERE id = :id
                """).bind("id", rowId).execute());

        // since is after the row's at → should return empty
        HttpResponse<String> res = get("/api/enrichment/assist/recent?since=2026-05-20T10:00:00Z");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size(), "row equal to since must be excluded (exclusive)");
    }
}
