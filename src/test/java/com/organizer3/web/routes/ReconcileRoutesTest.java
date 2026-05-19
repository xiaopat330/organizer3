package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.sync.ReconcileService;
import com.organizer3.sync.ReconcileService.SweepRowResult;
import com.organizer3.sync.ReconcileService.TrustVolumeResult;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
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

/**
 * Route-layer tests for the two Sync Health Drilldown endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/reconcile/sweep-row?id=N}</li>
 *   <li>{@code POST /api/reconcile/trust-volume?titleId=N&trustVolumeId=X}</li>
 * </ul>
 *
 * Uses mock {@link ReconcileService} and {@link TaskRunner}; real {@link WebServer} on port 0
 * to exercise the full HTTP/JSON dispatch path.
 */
class ReconcileRoutesTest {

    private WebServer server;
    private ReconcileService reconcileService;
    private ReconcileReportRepository reportRepo;
    private TaskRunner taskRunner;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        reconcileService = mock(ReconcileService.class);
        reportRepo       = mock(ReconcileReportRepository.class);
        taskRunner       = mock(TaskRunner.class);

        server = new WebServer(0);
        server.registerReconcile(new ReconcileRoutes(reconcileService, reportRepo, () -> taskRunner));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // =========================================================================
    // POST /api/reconcile/sweep-row
    // =========================================================================

    @Test
    void sweepRow_200_deletedPastGrace() throws Exception {
        when(reconcileService.sweepRow(42L))
                .thenReturn(new SweepRowResult.Deleted(7L, "vol-a", "/queue/ABP-001"));

        HttpResponse<String> res = post("/api/reconcile/sweep-row?id=42");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("deleted").asBoolean());
        assertEquals(7L, body.get("titleId").asLong());
        assertEquals("vol-a", body.get("volumeId").asText());
        assertEquals("/queue/ABP-001", body.get("path").asText());
    }

    @Test
    void sweepRow_404_rowNotFound() throws Exception {
        when(reconcileService.sweepRow(99L))
                .thenReturn(new SweepRowResult.NotFound());

        HttpResponse<String> res = post("/api/reconcile/sweep-row?id=99");

        assertEquals(404, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("error").asText().contains("99"));
    }

    @Test
    void sweepRow_409_rowInGraceWithStaleDays() throws Exception {
        // Row is stale but inside the grace window: staleDays=14, graceDays=90
        when(reconcileService.sweepRow(5L))
                .thenReturn(new SweepRowResult.InGrace(14, 90));

        HttpResponse<String> res = post("/api/reconcile/sweep-row?id=5");

        assertEquals(409, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("row not past grace", body.get("error").asText());
        assertEquals(14, body.get("staleDays").asInt());
        assertEquals(90, body.get("graceDays").asInt());
    }

    @Test
    void sweepRow_409_rowNotStaleAtAll_staleDaysNull() throws Exception {
        // Row has no stale_since at all: staleDays=null
        when(reconcileService.sweepRow(3L))
                .thenReturn(new SweepRowResult.InGrace(null, 90));

        HttpResponse<String> res = post("/api/reconcile/sweep-row?id=3");

        assertEquals(409, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("row not past grace", body.get("error").asText());
        assertTrue(body.get("staleDays").isNull(), "staleDays must be JSON null when stale_since is absent");
        assertEquals(90, body.get("graceDays").asInt());
    }

    @Test
    void sweepRow_400_missingIdParam() throws Exception {
        HttpResponse<String> res = post("/api/reconcile/sweep-row");
        assertEquals(400, res.statusCode());
        verifyNoInteractions(reconcileService);
    }

    // =========================================================================
    // POST /api/reconcile/trust-volume
    // =========================================================================

    @Test
    void trustVolume_202_dispatched() throws Exception {
        when(reconcileService.resolveTrustVolume(10L, "vol-b"))
                .thenReturn(new TrustVolumeResult.Ok("vol-a", "queue"));
        TaskRun fakeRun = mockTaskRun("volume.sync", "run-xyz");
        when(taskRunner.start(eq("volume.sync"), any())).thenReturn(fakeRun);

        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=10&trustVolumeId=vol-b");

        assertEquals(202, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("volume.sync", body.get("taskId").asText());
        assertEquals("run-xyz",     body.get("runId").asText());
        assertEquals("vol-a",       body.get("otherVolumeId").asText());
        assertEquals("queue",       body.get("otherPartitionId").asText());
    }

    @Test
    void trustVolume_404_titleNotFound() throws Exception {
        when(reconcileService.resolveTrustVolume(999L, "vol-a"))
                .thenReturn(new TrustVolumeResult.TitleNotFound());

        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=999&trustVolumeId=vol-a");

        assertEquals(404, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("error").asText().contains("999"));
    }

    @Test
    void trustVolume_409_onlyOneLocation() throws Exception {
        when(reconcileService.resolveTrustVolume(1L, "vol-a"))
                .thenReturn(new TrustVolumeResult.InsufficientLocations(1));

        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=1&trustVolumeId=vol-a");

        assertEquals(409, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.has("liveVolumeCount"));
        assertEquals(1, body.get("liveVolumeCount").asInt());
    }

    @Test
    void trustVolume_409_trustVolumeNotInLocations() throws Exception {
        when(reconcileService.resolveTrustVolume(2L, "vol-z"))
                .thenReturn(new TrustVolumeResult.TrustVolumeNotInLocations());

        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=2&trustVolumeId=vol-z");

        assertEquals(409, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("error").asText().contains("vol-z"));
    }

    @Test
    void trustVolume_409_moreThanTwoVolumes() throws Exception {
        when(reconcileService.resolveTrustVolume(3L, "vol-a"))
                .thenReturn(new TrustVolumeResult.TooManyVolumes(3));

        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=3&trustVolumeId=vol-a");

        assertEquals(409, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(3, body.get("volumeCount").asInt());
        assertTrue(body.get("error").asText().contains("3"));
    }

    @Test
    void trustVolume_409_taskInFlight() throws Exception {
        when(reconcileService.resolveTrustVolume(10L, "vol-b"))
                .thenReturn(new TrustVolumeResult.Ok("vol-a", "queue"));
        when(taskRunner.start(eq("volume.sync"), any()))
                .thenThrow(new TaskRunner.TaskInFlightException("volume.sync", "run-existing"));

        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=10&trustVolumeId=vol-b");

        assertEquals(409, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("task in flight", body.get("error").asText());
        assertEquals("volume.sync",    body.get("runningTaskId").asText());
        assertEquals("run-existing",   body.get("runningRunId").asText());
    }

    @Test
    void trustVolume_400_missingParams() throws Exception {
        HttpResponse<String> res = post("/api/reconcile/trust-volume?titleId=1");
        assertEquals(400, res.statusCode());
        verifyNoInteractions(reconcileService);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    /** Creates a mock TaskRun (package-private constructor) via reflection on the real class. */
    private static TaskRun mockTaskRun(String taskId, String runId) throws Exception {
        // TaskRun has a package-private constructor TaskRun(String taskId).
        // Use reflection to create one and inject the runId via the runId field.
        var ctor = TaskRun.class.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        TaskRun run = ctor.newInstance(taskId);
        // runId is final/UUID — mock the whole thing instead.
        TaskRun mock = mock(TaskRun.class);
        when(mock.taskId()).thenReturn(taskId);
        when(mock.runId()).thenReturn(runId);
        return mock;
    }
}
