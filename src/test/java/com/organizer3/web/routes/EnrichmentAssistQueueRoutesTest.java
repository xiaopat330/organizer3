package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrichmentAssistQueueRoutesTest {

    private WebServer server;
    private OllamaModelOrchestrator orchestrator;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        orchestrator = mock(OllamaModelOrchestrator.class);

        server = new WebServer(0);
        server.registerEnrichmentAssistQueue(new EnrichmentAssistQueueRoutes(orchestrator));
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

    @Test
    void getQueue_returnsInFlightAndQueued() throws Exception {
        when(orchestrator.getQueueDepths())
                .thenReturn(new OllamaModelOrchestrator.QueueDepths(1, 3));

        HttpResponse<String> res = get("/api/enrichment/assist/queue");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(1, body.get("inFlight").asInt());
        assertEquals(3, body.get("queued").asInt());
    }

    @Test
    void getQueue_returnsZerosWhenIdle() throws Exception {
        when(orchestrator.getQueueDepths())
                .thenReturn(new OllamaModelOrchestrator.QueueDepths(0, 0));

        HttpResponse<String> res = get("/api/enrichment/assist/queue");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(0, body.get("inFlight").asInt());
        assertEquals(0, body.get("queued").asInt());
    }
}
