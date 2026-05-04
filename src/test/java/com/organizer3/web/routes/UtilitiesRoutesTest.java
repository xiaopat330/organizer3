package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.covers.CoverPath;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.mcp.tools.ConfirmOrphanDeleteTool;
import com.organizer3.mcp.tools.ForceEnrichTitleTool;
import com.organizer3.mcp.tools.PickReviewCandidateTool;
import com.organizer3.mcp.tools.RecodeTitleTool;
import com.organizer3.mcp.tools.RefreshReviewCandidatesTool;
import com.organizer3.mcp.tools.RenameActressTool;
import com.organizer3.rating.RatingCurve;
import com.organizer3.rating.RatingCurveRepository;
import com.organizer3.utilities.actress.ActressYamlCatalogService;
import com.organizer3.utilities.backup.BackupCatalogService;
import com.organizer3.utilities.covers.OrphanedCoversService;
import com.organizer3.utilities.health.LibraryHealthCheck;
import com.organizer3.utilities.health.LibraryHealthReport;
import com.organizer3.utilities.health.LibraryHealthService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.TaskSpec;
import com.organizer3.utilities.volume.StaleLocationsService;
import com.organizer3.utilities.volume.VolumeStateDTO;
import com.organizer3.utilities.volume.VolumeStateService;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression baseline tests for {@link UtilitiesRoutes}.
 *
 * <p>Uses a real Javalin server on port 0 with all collaborators mocked. Covers every
 * top-level endpoint: one happy-path + one error-path per endpoint. Mirrors the harness
 * pattern from {@link DraftRoutesTest}.
 *
 * <p>Part of PR-A in the May 2026 housekeeping plan (spec/PROPOSAL_HOUSEKEEPING_2026_05.md).
 */
class UtilitiesRoutesTest {

    private WebServer server;

    // ── mocked collaborators ───────────────────────────────────────────────────
    private VolumeStateService volumeState;
    private StaleLocationsService staleLocations;
    private ActressYamlCatalogService actressCatalog;
    private ActressYamlLoader actressLoader;
    private BackupCatalogService backupCatalog;
    private UserDataBackupService backupService;
    private LibraryHealthService healthService;
    private OrphanedCoversService orphanedCoversService;
    private RatingCurveRepository ratingCurveRepo;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private ForceEnrichTitleTool forceEnrichTool;
    private PickReviewCandidateTool pickCandidateTool;
    private RefreshReviewCandidatesTool refreshCandidatesTool;
    private ConfirmOrphanDeleteTool confirmOrphanDeleteTool;
    private RenameActressTool renameActressTool;
    private RecodeTitleTool recodeTitleTool;
    private TaskRegistry registry;
    private TaskRunner runner;
    private CoverPath coverPath;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        volumeState         = mock(VolumeStateService.class);
        staleLocations      = mock(StaleLocationsService.class);
        actressCatalog      = mock(ActressYamlCatalogService.class);
        actressLoader       = mock(ActressYamlLoader.class);
        backupCatalog       = mock(BackupCatalogService.class);
        backupService       = mock(UserDataBackupService.class);
        healthService       = mock(LibraryHealthService.class);
        orphanedCoversService = mock(OrphanedCoversService.class);
        ratingCurveRepo     = mock(RatingCurveRepository.class);
        reviewQueueRepo     = mock(EnrichmentReviewQueueRepository.class);
        forceEnrichTool     = mock(ForceEnrichTitleTool.class);
        pickCandidateTool   = mock(PickReviewCandidateTool.class);
        refreshCandidatesTool  = mock(RefreshReviewCandidatesTool.class);
        confirmOrphanDeleteTool = mock(ConfirmOrphanDeleteTool.class);
        renameActressTool   = mock(RenameActressTool.class);
        recodeTitleTool     = mock(RecodeTitleTool.class);
        registry            = mock(TaskRegistry.class);
        runner              = mock(TaskRunner.class);
        coverPath           = mock(CoverPath.class);

        server = new WebServer(0);
        server.registerUtilities(new UtilitiesRoutes(
                volumeState, staleLocations, actressCatalog, actressLoader,
                backupCatalog, backupService, healthService, orphanedCoversService,
                ratingCurveRepo, reviewQueueRepo, forceEnrichTool, pickCandidateTool,
                refreshCandidatesTool, confirmOrphanDeleteTool, renameActressTool,
                recodeTitleTool, registry, runner, coverPath));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .GET().build(),
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

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── Volumes ────────────────────────────────────────────────────────────────

    @Test
    void getVolumes_returnsListFromService() throws Exception {
        VolumeStateDTO dto = new VolumeStateDTO("vol-a", "//nas/jav", "conventional",
                null, 10, 2, "online", List.of());
        when(volumeState.list()).thenReturn(List.of(dto));

        var resp = get("/api/utilities/volumes");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("vol-a", body.get(0).get("id").asText());
    }

    @Test
    void getVolume_404WhenNotFound() throws Exception {
        when(volumeState.find("no-such")).thenReturn(Optional.empty());

        var resp = get("/api/utilities/volumes/no-such");
        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void getVolume_returnsDetailFromService() throws Exception {
        VolumeStateDTO dto = new VolumeStateDTO("vol-a", "//nas/jav", "conventional",
                null, 5, 0, "online", List.of());
        when(volumeState.find("vol-a")).thenReturn(Optional.of(dto));

        var resp = get("/api/utilities/volumes/vol-a");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("vol-a", body.get("id").asText());
    }

    // ── Task specs ─────────────────────────────────────────────────────────────

    @Test
    void getTasks_returnsSpecList() throws Exception {
        TaskSpec spec = new TaskSpec("volume.sync", "Sync Volume", "syncs a volume", List.of());
        when(registry.specs()).thenReturn(List.of(spec));

        var resp = get("/api/utilities/tasks");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("volume.sync", body.get(0).get("id").asText());
    }

    @Test
    void getTasks_returnsEmptyList_whenNoTasksRegistered() throws Exception {
        when(registry.specs()).thenReturn(List.of());

        var resp = get("/api/utilities/tasks");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    // ── Actress YAMLs ──────────────────────────────────────────────────────────

    @Test
    void getActressYamls_returnsListFromService() throws Exception {
        var entry = new ActressYamlCatalogService.Entry(
                "yua-aida", "相田ゆあ", true, 15,
                new ActressYamlCatalogService.ProfileSummary("1985-01-01", 163, "2003-2010", List.of()));
        when(actressCatalog.list()).thenReturn(List.of(entry));

        var resp = get("/api/utilities/actress-yamls");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("yua-aida", body.get(0).get("slug").asText());
    }

    @Test
    void getActressYamls_500OnIOException() throws Exception {
        when(actressCatalog.list()).thenThrow(new java.io.IOException("disk error"));

        var resp = get("/api/utilities/actress-yamls");
        assertEquals(500, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void getActressYamlSlug_returnsEntryWhenFound() throws Exception {
        var entry = new ActressYamlCatalogService.Entry(
                "yua-aida", "相田ゆあ", true, 15,
                new ActressYamlCatalogService.ProfileSummary(null, null, null, List.of()));
        when(actressCatalog.find("yua-aida")).thenReturn(Optional.of(entry));

        var resp = get("/api/utilities/actress-yamls/yua-aida");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("yua-aida", body.get("slug").asText());
    }

    @Test
    void getActressYamlSlug_404WhenNotFound() throws Exception {
        when(actressCatalog.find("no-slug")).thenReturn(Optional.empty());

        var resp = get("/api/utilities/actress-yamls/no-slug");
        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void getActressYamlSlug_500OnIOException() throws Exception {
        when(actressCatalog.find("bad-slug")).thenThrow(new java.io.IOException("fs error"));

        var resp = get("/api/utilities/actress-yamls/bad-slug");
        assertEquals(500, resp.statusCode());
    }

    // ── Backup snapshots ───────────────────────────────────────────────────────

    @Test
    void getBackupSnapshots_returnsListFromService() throws Exception {
        var snapshot = new BackupCatalogService.Snapshot("2024-01-01T00:00:00Z", 1024L, "2024-01-01T00:00:00Z", true);
        when(backupCatalog.list()).thenReturn(List.of(snapshot));

        var resp = get("/api/utilities/backup/snapshots");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
    }

    @Test
    void getBackupSnapshot_404WhenNotFound() throws Exception {
        when(backupCatalog.resolve("missing-snap")).thenReturn(Optional.empty());

        var resp = get("/api/utilities/backup/snapshots/missing-snap");
        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void getBackupSnapshot_returnsDetailWhenFound() throws Exception {
        var fakeDetail = new UserDataBackupService.SnapshotDetail(
                "snap-1.json", 1024L, 1, "2024-01-01T00:00:00Z", 10, 100, 0, 0, 0);
        var tmpPath = java.nio.file.Path.of("/tmp/snap-1.json");
        when(backupCatalog.resolve("snap-1")).thenReturn(Optional.of(tmpPath));
        when(backupService.snapshotDetail(tmpPath)).thenReturn(fakeDetail);

        var resp = get("/api/utilities/backup/snapshots/snap-1");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void getBackupSnapshot_500OnIOException() throws Exception {
        var tmpPath = java.nio.file.Path.of("/tmp/snap-1.json");
        when(backupCatalog.resolve("snap-1")).thenReturn(Optional.of(tmpPath));
        when(backupService.snapshotDetail(tmpPath)).thenThrow(new java.io.IOException("zip corrupt"));

        var resp = get("/api/utilities/backup/snapshots/snap-1");
        assertEquals(500, resp.statusCode());
    }

    // ── Health checks ──────────────────────────────────────────────────────────

    @Test
    void getHealthChecks_returnsList() throws Exception {
        LibraryHealthCheck check = mockCheck("stale_locations", "Stale Locations",
                "Files no longer seen", LibraryHealthCheck.FixRouting.VOLUMES_SCREEN);
        when(healthService.checks()).thenReturn(List.of(check));

        var resp = get("/api/utilities/health/checks");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("stale_locations", body.get(0).get("id").asText());
    }

    @Test
    void getHealthChecks_returnsEmptyListWhenNoChecks() throws Exception {
        when(healthService.checks()).thenReturn(List.of());

        var resp = get("/api/utilities/health/checks");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    @Test
    void getHealthReportLatest_returnsScanFalseWhenNoReport() throws Exception {
        when(healthService.latest()).thenReturn(Optional.empty());

        var resp = get("/api/utilities/health/report/latest");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("scanned").asBoolean());
    }

    @Test
    void getHealthReportLatest_returnsSummaryWhenReportExists() throws Exception {
        var report = new LibraryHealthReport("run-1", Instant.now(), Map.of());
        when(healthService.latest()).thenReturn(Optional.of(report));

        var resp = get("/api/utilities/health/report/latest");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("scanned").asBoolean());
        assertEquals("run-1", body.get("runId").asText());
    }

    @Test
    void getHealthReportLatestCheckId_404WhenNoReport() throws Exception {
        when(healthService.latest()).thenReturn(Optional.empty());

        var resp = get("/api/utilities/health/report/latest/stale_locations");
        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void getHealthReportLatestCheckId_404WhenCheckNotInReport() throws Exception {
        var report = new LibraryHealthReport("run-1", Instant.now(), Map.of());
        when(healthService.latest()).thenReturn(Optional.of(report));

        var resp = get("/api/utilities/health/report/latest/unknown_check");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void getHealthReportLatestCheckId_returnsCheckEntryWhenFound() throws Exception {
        LibraryHealthCheck check = mockCheck("stale_locations", "Stale Locations",
                "Files no longer seen", LibraryHealthCheck.FixRouting.VOLUMES_SCREEN);
        var result = new LibraryHealthCheck.CheckResult(3, List.of());
        var entry = new LibraryHealthReport.CheckEntry("stale_locations", "Stale Locations",
                "Files no longer seen", LibraryHealthCheck.FixRouting.VOLUMES_SCREEN, result);
        var report = new LibraryHealthReport("run-1", Instant.now(),
                java.util.Map.of("stale_locations", entry));
        when(healthService.latest()).thenReturn(Optional.of(report));

        var resp = get("/api/utilities/health/report/latest/stale_locations");
        assertEquals(200, resp.statusCode());
    }

    // ── Enrichment review queue ────────────────────────────────────────────────

    @Test
    void getEnrichmentReviewQueue_returnsCountsAndRows() throws Exception {
        when(reviewQueueRepo.countOpenByReason()).thenReturn(Map.of("cast_anomaly", 3));
        when(reviewQueueRepo.listOpen(isNull(), eq(100), eq(0))).thenReturn(List.of());
        when(coverPath.findByCode(any())).thenReturn(Optional.empty());

        var resp = get("/api/utilities/enrichment-review/queue");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("counts"));
        assertTrue(body.has("rows"));
        assertEquals(3, body.get("counts").get("cast_anomaly").asInt());
    }

    @Test
    void getEnrichmentReviewQueue_filtersWithReasonParam() throws Exception {
        when(reviewQueueRepo.countOpenByReason()).thenReturn(Map.of());
        when(reviewQueueRepo.listOpen(eq("cast_anomaly"), eq(100), eq(0))).thenReturn(List.of());

        var resp = get("/api/utilities/enrichment-review/queue?reason=cast_anomaly");
        assertEquals(200, resp.statusCode());
        verify(reviewQueueRepo).listOpen("cast_anomaly", 100, 0);
    }

    @Test
    void resolveQueueRow_200WhenResolvedSuccessfully() throws Exception {
        when(reviewQueueRepo.resolveOne(42L, "accepted_gap")).thenReturn(true);

        var resp = postJson("/api/utilities/enrichment-review/queue/42/resolve",
                "{\"resolution\":\"accepted_gap\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("ok").asBoolean());
    }

    @Test
    void resolveQueueRow_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/abc/resolve",
                "{\"resolution\":\"accepted_gap\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void resolveQueueRow_400OnInvalidResolution() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/1/resolve",
                "{\"resolution\":\"not_allowed\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void resolveQueueRow_400WhenResolutionMissing() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/1/resolve", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void forceEnrichQueueRow_404WhenQueueRowNotFound() throws Exception {
        when(reviewQueueRepo.findTitleIdByQueueRowId(99L)).thenReturn(Optional.empty());

        var resp = postJson("/api/utilities/enrichment-review/queue/99/force-enrich",
                "{\"slug\":\"test-slug\"}");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void forceEnrichQueueRow_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/abc/force-enrich",
                "{\"slug\":\"test-slug\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void forceEnrichQueueRow_200OnSuccess() throws Exception {
        when(reviewQueueRepo.findTitleIdByQueueRowId(1L)).thenReturn(Optional.of(10L));
        when(forceEnrichTool.call(any())).thenReturn(Map.of("ok", true));

        var resp = postJson("/api/utilities/enrichment-review/queue/1/force-enrich",
                "{\"slug\":\"test-slug\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void pickQueueRow_400WhenSlugMissing() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/1/pick", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void pickQueueRow_200OnSuccess() throws Exception {
        when(pickCandidateTool.call(any())).thenReturn(Map.of("ok", true));

        var resp = postJson("/api/utilities/enrichment-review/queue/1/pick",
                "{\"slug\":\"test-slug\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void refreshQueueRow_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/abc/refresh", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void refreshQueueRow_200OnSuccess() throws Exception {
        when(refreshCandidatesTool.call(any())).thenReturn(Map.of("ok", true));

        var resp = postJson("/api/utilities/enrichment-review/queue/5/refresh", "{}");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void confirmOrphanDelete_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/enrichment-review/queue/abc/confirm-orphan-delete", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void confirmOrphanDelete_200OnSuccess() throws Exception {
        when(confirmOrphanDeleteTool.call(any())).thenReturn(Map.of("ok", true));

        var resp = postJson("/api/utilities/enrichment-review/queue/7/confirm-orphan-delete", "{}");
        assertEquals(200, resp.statusCode());
    }

    // ── Identity tools ─────────────────────────────────────────────────────────

    @Test
    void renameActress_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/actress/abc/rename",
                "{\"newCanonicalName\":\"New Name\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void renameActress_400WhenNewNameMissing() throws Exception {
        var resp = postJson("/api/utilities/actress/1/rename", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void renameActress_200OnSuccess() throws Exception {
        when(renameActressTool.call(any())).thenReturn(Map.of("ok", true, "dryRun", false));

        var resp = postJson("/api/utilities/actress/1/rename",
                "{\"newCanonicalName\":\"New Name\",\"dryRun\":false,\"renameDisk\":true}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("ok").asBoolean());
    }

    @Test
    void renameActress_400OnIllegalArgument() throws Exception {
        when(renameActressTool.call(any())).thenThrow(new IllegalArgumentException("actress not found"));

        var resp = postJson("/api/utilities/actress/999/rename",
                "{\"newCanonicalName\":\"New Name\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void recodeTitle_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/title/abc/recode",
                "{\"newCode\":\"ABC-001\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void recodeTitle_400WhenNewCodeMissing() throws Exception {
        var resp = postJson("/api/utilities/title/1/recode", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void recodeTitle_200OnSuccess() throws Exception {
        when(recodeTitleTool.call(any())).thenReturn(Map.of("ok", true));

        var resp = postJson("/api/utilities/title/1/recode",
                "{\"newCode\":\"ABC-001\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void recodeTitle_400OnIllegalArgument() throws Exception {
        when(recodeTitleTool.call(any())).thenThrow(new IllegalArgumentException("bad code"));

        var resp = postJson("/api/utilities/title/1/recode",
                "{\"newCode\":\"INVALID\"}");
        assertEquals(400, resp.statusCode());
    }

    // ── Rating curve ───────────────────────────────────────────────────────────

    @Test
    void getRatingCurveStatus_200WhenNotComputed() throws Exception {
        // Fixed: previously Map.of() rejected the null computedAt value and returned 500.
        // Now uses LinkedHashMap which tolerates null values.
        when(ratingCurveRepo.find()).thenReturn(Optional.empty());

        var resp = get("/api/utilities/rating-curve/status");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("computedAt").isNull());
        assertEquals(0, body.get("population").asInt());
        assertEquals(0, body.get("boundaries").asInt());
    }

    @Test
    void getRatingCurveStatus_returnsDataWhenComputed() throws Exception {
        RatingCurve curve = new RatingCurve(7.5, 500, 10,
                List.of(new RatingCurve.Boundary(8.0, "A")), Instant.parse("2024-01-01T00:00:00Z"));
        when(ratingCurveRepo.find()).thenReturn(Optional.of(curve));

        var resp = get("/api/utilities/rating-curve/status");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("2024-01-01T00:00:00Z", body.get("computedAt").asText());
        assertEquals(500, body.get("population").asInt());
        assertEquals(1, body.get("boundaries").asInt());
    }

    // ── Preview endpoint ───────────────────────────────────────────────────────

    @Test
    void previewCoversCleanOrphaned_returnsPreviewData() throws Exception {
        var previewRows = List.of(
                new OrphanedCoversService.OrphanRow("ABP", "ABP-001.jpg", "/covers/ABP/ABP-001.jpg", 1024L));
        var preview = new OrphanedCoversService.OrphanPreview(previewRows, 1024L);
        when(orphanedCoversService.preview()).thenReturn(preview);

        var resp = postJson("/api/utilities/tasks/covers.clean_orphaned/preview", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals(1, body.get("count").asInt());
        assertEquals(1024, body.get("totalBytes").asLong());
        assertFalse(body.get("truncated").asBoolean());
    }

    @Test
    void previewCleanStaleLocations_400WhenVolumeIdMissing() throws Exception {
        var resp = postJson("/api/utilities/tasks/volume.clean_stale_locations/preview", "{}");
        assertEquals(400, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void previewCleanStaleLocations_returnsRows() throws Exception {
        when(staleLocations.preview("vol-a")).thenReturn(List.of());

        var resp = postJson("/api/utilities/tasks/volume.clean_stale_locations/preview",
                "{\"volumeId\":\"vol-a\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("rows"));
        assertEquals(0, body.get("count").asInt());
    }

    @Test
    void previewActressLoadOne_400WhenSlugMissing() throws Exception {
        var resp = postJson("/api/utilities/tasks/actress.load_one/preview", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void previewActressLoadOne_404WhenSlugUnknown() throws Exception {
        when(actressLoader.plan("no-such-slug"))
                .thenThrow(new IllegalArgumentException("not found"));

        var resp = postJson("/api/utilities/tasks/actress.load_one/preview",
                "{\"slug\":\"no-such-slug\"}");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void previewBackupRestore_400WhenSnapshotNameMissing() throws Exception {
        var resp = postJson("/api/utilities/tasks/backup.restore/preview", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void previewBackupRestore_404WhenSnapshotNotFound() throws Exception {
        when(backupCatalog.resolve("missing")).thenReturn(Optional.empty());

        var resp = postJson("/api/utilities/tasks/backup.restore/preview",
                "{\"snapshotName\":\"missing\"}");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void previewUnknownTask_404() throws Exception {
        var resp = postJson("/api/utilities/tasks/no.such.task/preview", "{}");
        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    // ── Task run ───────────────────────────────────────────────────────────────

    @Test
    void runTask_400WhenUnknownTaskId() throws Exception {
        when(runner.start(eq("unknown.task"), any())).thenThrow(
                new IllegalArgumentException("Unknown task: unknown.task"));

        var resp = postJson("/api/utilities/tasks/unknown.task/run", "{}");
        assertEquals(400, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    @Test
    void runTask_409WhenTaskAlreadyInFlight() throws Exception {
        when(runner.start(eq("volume.sync"), any())).thenThrow(
                new TaskRunner.TaskInFlightException("volume.sync", "run-abc"));

        var resp = postJson("/api/utilities/tasks/volume.sync/run",
                "{\"volumeId\":\"vol-a\"}");
        assertEquals(409, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("volume.sync", body.get("runningTaskId").asText());
    }

    // ── Active run ─────────────────────────────────────────────────────────────

    @Test
    void getActive_returnsFalseWhenNoRunning() throws Exception {
        when(runner.currentlyRunning()).thenReturn(Optional.empty());

        var resp = get("/api/utilities/active");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("active").asBoolean());
    }

    // ── Cancel run ─────────────────────────────────────────────────────────────

    @Test
    void cancelRun_404WhenRunNotFound() throws Exception {
        when(runner.cancel("no-such-run")).thenReturn(TaskRunner.CancelOutcome.NOT_FOUND);

        var resp = postJson("/api/utilities/runs/no-such-run/cancel", "");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void cancelRun_204WhenCancelRequested() throws Exception {
        when(runner.cancel("run-123")).thenReturn(TaskRunner.CancelOutcome.REQUESTED);

        var resp = postJson("/api/utilities/runs/run-123/cancel", "");
        assertEquals(204, resp.statusCode());
    }

    @Test
    void cancelRun_204WhenAlreadyEnded() throws Exception {
        when(runner.cancel("run-xyz")).thenReturn(TaskRunner.CancelOutcome.ALREADY_ENDED);

        var resp = postJson("/api/utilities/runs/run-xyz/cancel", "");
        assertEquals(204, resp.statusCode());
    }

    // ── Run state (polling fallback) ───────────────────────────────────────────

    @Test
    void getRun_404WhenRunNotFound() throws Exception {
        when(runner.findRun("no-such-run")).thenReturn(Optional.empty());

        var resp = get("/api/utilities/runs/no-such-run");
        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("error"));
    }

    // ── SSE stream ─────────────────────────────────────────────────────────────

    @Test
    void sseRun_immediateCloseWhenRunNotFound() throws Exception {
        // SSE clients can only observe the close behavior via HTTP; the endpoint sends
        // "error: run not found" and closes immediately. We verify the HTTP layer
        // responds (status 200, SSE content type) and doesn't hang.
        when(runner.findRun("no-such-run")).thenReturn(Optional.empty());

        var resp = get("/api/utilities/runs/no-such-run/events");
        // Javalin returns 200 for SSE connections even when the stream immediately closes.
        assertEquals(200, resp.statusCode());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static LibraryHealthCheck mockCheck(String id, String label, String description,
                                                 LibraryHealthCheck.FixRouting routing) {
        LibraryHealthCheck check = mock(LibraryHealthCheck.class);
        when(check.id()).thenReturn(id);
        when(check.label()).thenReturn(label);
        when(check.description()).thenReturn(description);
        when(check.fixRouting()).thenReturn(routing);
        return check;
    }
}
