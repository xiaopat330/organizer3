package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.avstars.AvScreenshotWorker;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository.ActressProgress;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.media.StreamActivityTracker;
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

class AvScreenshotQueueRoutesTest {

    private WebServer server;
    private AvScreenshotQueueRepository queueRepo;
    private AvVideoRepository videoRepo;
    private AvScreenshotRepository screenshotRepo;
    private AvScreenshotWorker worker;
    private StreamActivityTracker streamTracker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        queueRepo     = mock(AvScreenshotQueueRepository.class);
        videoRepo     = mock(AvVideoRepository.class);
        screenshotRepo = mock(AvScreenshotRepository.class);
        worker        = mock(AvScreenshotWorker.class);
        streamTracker = mock(StreamActivityTracker.class);

        server = new WebServer(0);
        server.registerAvScreenshotQueue(
                new AvScreenshotQueueRoutes(queueRepo, videoRepo, screenshotRepo, worker, streamTracker));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    // --- enqueue ---

    @Test
    void enqueueReturnsCountsWhenSomeVideosNeedScreenshots() throws Exception {
        AvVideo v1 = avVideo(10L);
        AvVideo v2 = avVideo(11L);
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v1, v2));
        when(screenshotRepo.countByVideoId(10L)).thenReturn(0);
        when(screenshotRepo.countByVideoId(11L)).thenReturn(3); // already done
        when(queueRepo.enqueueIfAbsent(1L, 10L)).thenReturn(true);

        var resp = post("/api/av/actresses/1/screenshots/enqueue");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(1, body.get("enqueued").asInt());
        assertEquals(1, body.get("alreadyDone").asInt());
        assertEquals(0, body.get("alreadyQueued").asInt());
    }

    @Test
    void enqueueCountsAlreadyQueuedWhenInsertReturnsFalse() throws Exception {
        AvVideo v1 = avVideo(10L);
        when(videoRepo.findByActress(2L)).thenReturn(List.of(v1));
        when(screenshotRepo.countByVideoId(10L)).thenReturn(0);
        when(queueRepo.enqueueIfAbsent(2L, 10L)).thenReturn(false); // already queued

        var resp = post("/api/av/actresses/2/screenshots/enqueue");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(0, body.get("enqueued").asInt());
        assertEquals(0, body.get("alreadyDone").asInt());
        assertEquals(1, body.get("alreadyQueued").asInt());
    }

    // --- pause ---

    @Test
    void pauseReturnsPausedCount() throws Exception {
        when(queueRepo.pauseActress(1L)).thenReturn(3);

        var resp = post("/api/av/actresses/1/screenshots/pause");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(3, body.get("paused").asInt());
    }

    // --- resume ---

    @Test
    void resumeReturnsResumedCount() throws Exception {
        when(queueRepo.resumeActress(1L)).thenReturn(2);

        var resp = post("/api/av/actresses/1/screenshots/resume");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(2, body.get("resumed").asInt());
    }

    // --- delete (stop) ---

    @Test
    void deleteQueueReturnsRemovedCount() throws Exception {
        when(queueRepo.clearForActress(1L)).thenReturn(4);

        var resp = delete("/api/av/actresses/1/screenshots/queue");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(4, body.get("removed").asInt());
    }

    // --- progress ---

    @Test
    void progressReturnsAllFields() throws Exception {
        ActressProgress p = new ActressProgress(2, 1, 0, 10, 1, 14, 42L);
        when(queueRepo.progressForActress(1L)).thenReturn(p);

        var resp = get("/api/av/actresses/1/screenshots/progress");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertEquals(2,  body.get("pending").asInt());
        assertEquals(1,  body.get("inProgress").asInt());
        assertEquals(0,  body.get("paused").asInt());
        assertEquals(10, body.get("done").asInt());
        assertEquals(1,  body.get("failed").asInt());
        assertEquals(14, body.get("total").asInt());
        assertEquals(42, body.get("currentVideoId").asLong());
    }

    @Test
    void progressReturnsNullCurrentVideoIdWhenIdle() throws Exception {
        ActressProgress p = new ActressProgress(1, 0, 0, 0, 0, 1, null);
        when(queueRepo.progressForActress(5L)).thenReturn(p);

        var resp = get("/api/av/actresses/5/screenshots/progress");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("currentVideoId").isNull());
    }

    // --- worker/state ---

    @Test
    void workerStateReturnsAllFields() throws Exception {
        when(worker.isRunning()).thenReturn(true);
        when(streamTracker.isPlaying(30_000L)).thenReturn(false);
        when(queueRepo.globalDepth()).thenReturn(5);
        when(worker.getCurrentVideoId()).thenReturn(7L);
        when(worker.getCurrentActressId()).thenReturn(3L);

        var resp = get("/api/av/screenshot-queue/state");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("running").asBoolean());
        assertFalse(body.get("streamActive").asBoolean());
        assertEquals(5, body.get("queueDepth").asInt());
        assertEquals(7, body.get("currentVideoId").asLong());
        assertEquals(3, body.get("currentActressId").asLong());
    }

    @Test
    void workerStateShowsStreamActiveWhenPlaying() throws Exception {
        when(worker.isRunning()).thenReturn(true);
        when(streamTracker.isPlaying(30_000L)).thenReturn(true);
        when(queueRepo.globalDepth()).thenReturn(0);
        when(worker.getCurrentVideoId()).thenReturn(null);
        when(worker.getCurrentActressId()).thenReturn(null);

        var resp = get("/api/av/screenshot-queue/state");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("streamActive").asBoolean());
        assertTrue(body.get("currentVideoId").isNull());
        assertTrue(body.get("currentActressId").isNull());
    }

    // --- helpers ---

    private static AvVideo avVideo(long id) {
        return AvVideo.builder().id(id).build();
    }
}
