package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.model.Title;
import com.organizer3.model.WatchHistory;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WatchHistoryRoutesTest {

    private WebServer server;
    private WatchHistoryRepository watchRepo;
    private TitleRepository titleRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        watchRepo = mock(WatchHistoryRepository.class);
        titleRepo = mock(TitleRepository.class);

        server = new WebServer(0);
        server.registerWatchHistory(new WatchHistoryRoutes(watchRepo, titleRepo));
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

    // ── POST /api/watch-history/{titleCode} ───────────────────────────────────

    @Test
    void record_200WhenTitleExists() throws Exception {
        Title title = Title.builder().id(1L).code("ABP-001").label("ABP").seqNum(1).build();
        when(titleRepo.findByCode("ABP-001")).thenReturn(Optional.of(title));
        WatchHistory entry = WatchHistory.builder()
                .id(42L).titleCode("ABP-001").watchedAt(LocalDateTime.of(2026, 5, 4, 12, 0)).build();
        when(watchRepo.record(eq("ABP-001"), any(LocalDateTime.class))).thenReturn(entry);

        var resp = post("/api/watch-history/ABP-001");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(42, body.get("id").asLong());
        assertEquals("ABP-001", body.get("titleCode").asText());
        verify(watchRepo).record(eq("ABP-001"), any(LocalDateTime.class));
    }

    @Test
    void record_404WhenTitleDoesNotExist() throws Exception {
        when(titleRepo.findByCode("FAKE-999")).thenReturn(Optional.empty());

        var resp = post("/api/watch-history/FAKE-999");
        assertEquals(404, resp.statusCode());

        verify(watchRepo, never()).record(any(), any());
    }
}
