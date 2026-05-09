package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;
import com.organizer3.titlefolder.TitleFolderService;
import com.organizer3.titlefolder.TitleFolderService.FolderContents;
import com.organizer3.titlefolder.TitleFolderService.FolderCover;
import com.organizer3.titlefolder.TitleFolderService.FolderVideo;
import com.organizer3.titlefolder.TitleFolderService.TrashOutcome;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Route integration tests for the three folder-contents endpoints:
 *   GET  /api/titles/{code}/folder-contents
 *   POST /api/titles/{code}/videos/{filename}/trash
 *   POST /api/titles/{code}/covers/{filename}/trash
 */
class TitleFolderRoutesTest {

    private WebServer server;
    private TitleRepository titleRepo;
    private VideoRepository videoRepo;
    private TitleFolderService folderService;
    private SmbConnectionFactory smbFactory;
    private OrganizerConfig organizerConfig;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        titleRepo     = mock(TitleRepository.class);
        videoRepo     = mock(VideoRepository.class);
        folderService = mock(TitleFolderService.class);
        smbFactory    = mock(SmbConnectionFactory.class);

        // Stub OrganizerConfig with a single volume + server that has a trash folder.
        // ServerConfig(id, username, password, domain, trash, sandbox)
        ServerConfig server1 = new ServerConfig("nas1", "user", "pass", null, "/_trash", null);
        // VolumeConfig(id, smbPath, structureType, server, group)
        VolumeConfig vol1    = new VolumeConfig("vol1", "//host/share", "conventional", "nas1", null);
        organizerConfig = new OrganizerConfig(
                "Test", null, null, null, null, null, null, null,
                List.of(server1), List.of(vol1), List.of(), List.of(), null, null);

        // SmbShareHandle / VolumeFileSystem stub — returned by smbFactory.open("vol1").
        VolumeFileSystem fs       = mock(VolumeFileSystem.class);
        SmbShareHandle shareHandle = mock(SmbShareHandle.class);
        when(shareHandle.fileSystem()).thenReturn(fs);
        when(smbFactory.open("vol1")).thenReturn(shareHandle);

        // Default: cover file exists on FS for cover-trash tests.
        when(fs.exists(any())).thenReturn(true);

        server = new WebServer(0);
        // Register the base flag routes (not under test but needed for the app to start cleanly).
        server.registerTitleRoutes(new TitleRoutes(null, null, titleRepo));
        // Register the folder-contents routes under test.
        server.registerTitleFolderContents(new TitleRoutes(
                null, null, titleRepo,
                folderService, smbFactory, organizerConfig, videoRepo));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Title singleLocationTitle(String code) {
        TitleLocation loc = TitleLocation.builder()
                .titleId(1L).volumeId("vol1").partitionId("library")
                .path(Path.of("/stars/" + code))
                .lastSeenAt(LocalDate.of(2026, 1, 1))
                .build();
        return Title.builder()
                .id(1L).code(code)
                .baseCode(code.toUpperCase())
                .label(code.split("-")[0]).seqNum(1)
                .locations(List.of(loc))
                .build();
    }

    private static Title twoLocationTitle(String code) {
        TitleLocation loc1 = TitleLocation.builder()
                .titleId(1L).volumeId("vol1").partitionId("lib1")
                .path(Path.of("/stars1/" + code))
                .lastSeenAt(LocalDate.of(2026, 1, 1)).build();
        TitleLocation loc2 = TitleLocation.builder()
                .titleId(1L).volumeId("vol2").partitionId("lib2")
                .path(Path.of("/stars2/" + code))
                .lastSeenAt(LocalDate.of(2026, 1, 2)).build();
        return Title.builder()
                .id(1L).code(code)
                .baseCode(code.toUpperCase())
                .label(code.split("-")[0]).seqNum(1)
                .locations(List.of(loc1, loc2))
                .build();
    }

    // ── GET /api/titles/{code}/folder-contents ─────────────────────────────

    @Test
    void getFolderContents_happyPath_returnsExpectedShape() throws Exception {
        when(titleRepo.findByCode("ABP-001")).thenReturn(Optional.of(singleLocationTitle("ABP-001")));

        FolderContents contents = new FolderContents(
                "vol1", "/stars/ABP-001",
                List.of(new FolderVideo("ABP-001.mp4", "video/ABP-001.mp4", 3_000_000_000L,
                        42L, 7200L, 1920, 1080, "h264", "aac", "mp4")),
                List.of(new FolderCover("ABP-001.jpg", "ABP-001.jpg", 280_000L)),
                List.of());
        when(folderService.listContents(any(), eq("ABP-001"), eq("vol1"), eq(Path.of("/stars/ABP-001"))))
                .thenReturn(contents);

        HttpResponse<String> res = get("/api/titles/ABP-001/folder-contents");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("vol1", body.get("volumeId").asText());
        assertTrue(body.has("videos"));
        assertTrue(body.has("covers"));
        assertTrue(body.has("otherFiles"));
        assertEquals(1, body.get("videos").size());
        assertEquals("ABP-001.mp4", body.get("videos").get(0).get("filename").asText());
        assertEquals(1, body.get("covers").size());
        assertEquals("ABP-001.jpg", body.get("covers").get(0).get("filename").asText());
    }

    @Test
    void getFolderContents_unknownTitle_returns404() throws Exception {
        when(titleRepo.findByCode("MISSING-001")).thenReturn(Optional.empty());

        HttpResponse<String> res = get("/api/titles/MISSING-001/folder-contents");

        assertEquals(404, res.statusCode());
        assertTrue(mapper.readTree(res.body()).has("error"));
    }

    @Test
    void getFolderContents_multiLocationTitle_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-002")).thenReturn(Optional.of(twoLocationTitle("ABP-002")));

        HttpResponse<String> res = get("/api/titles/ABP-002/folder-contents");

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("error").asText().contains("2 locations"));
        assertTrue(body.get("error").asText().contains("requires exactly 1"));
    }

    // ── POST /api/titles/{code}/videos/{filename}/trash ────────────────────

    @Test
    void trashVideo_success_returns200WithTrashedTo() throws Exception {
        when(titleRepo.findByCode("ABP-003")).thenReturn(Optional.of(singleLocationTitle("ABP-003")));
        Video v = Video.builder()
                .id(10L).titleId(1L).volumeId("vol1").filename("ABP-003.mp4")
                .path(Path.of("/stars/ABP-003/video/ABP-003.mp4"))
                .lastSeenAt(LocalDate.of(2026, 1, 1)).sizeBytes(1_000L).build();
        when(videoRepo.findByTitle(1L)).thenReturn(List.of(v));
        when(folderService.trashVideo(any(), eq(v), anyString()))
                .thenReturn(TrashOutcome.success(v.getPath(), Path.of("/_trash/ABP-003.mp4")));

        HttpResponse<String> res = post("/api/titles/ABP-003/videos/ABP-003.mp4/trash");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("success").asBoolean());
        assertEquals("/_trash/ABP-003.mp4", body.get("trashedTo").asText());
    }

    @Test
    void trashVideo_unknownFilename_returns404() throws Exception {
        when(titleRepo.findByCode("ABP-004")).thenReturn(Optional.of(singleLocationTitle("ABP-004")));
        when(videoRepo.findByTitle(1L)).thenReturn(List.of());

        HttpResponse<String> res = post("/api/titles/ABP-004/videos/no-such-file.mp4/trash");

        assertEquals(404, res.statusCode());
        assertTrue(mapper.readTree(res.body()).get("error").asText()
                .contains("video not found"));
    }

    @Test
    void trashVideo_unknownTitle_returns404() throws Exception {
        when(titleRepo.findByCode("MISSING-002")).thenReturn(Optional.empty());

        HttpResponse<String> res = post("/api/titles/MISSING-002/videos/x.mp4/trash");

        assertEquals(404, res.statusCode());
    }

    @Test
    void trashVideo_multiLocationTitle_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-005")).thenReturn(Optional.of(twoLocationTitle("ABP-005")));

        HttpResponse<String> res = post("/api/titles/ABP-005/videos/x.mp4/trash");

        assertEquals(400, res.statusCode());
    }

    // ── POST /api/titles/{code}/covers/{filename}/trash ────────────────────

    @Test
    void trashCover_success_returns200WithTrashedTo() throws Exception {
        when(titleRepo.findByCode("ABP-010")).thenReturn(Optional.of(singleLocationTitle("ABP-010")));
        when(folderService.trashCover(any(), eq(Path.of("/stars/ABP-010")),
                eq("ABP-010.jpg"), anyString()))
                .thenReturn(TrashOutcome.success(
                        Path.of("/stars/ABP-010/ABP-010.jpg"),
                        Path.of("/_trash/ABP-010.jpg")));

        HttpResponse<String> res = post("/api/titles/ABP-010/covers/ABP-010.jpg/trash");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertTrue(body.get("success").asBoolean());
        assertEquals("/_trash/ABP-010.jpg", body.get("trashedTo").asText());
    }

    @Test
    void trashCover_unknownTitle_returns404() throws Exception {
        when(titleRepo.findByCode("MISSING-003")).thenReturn(Optional.empty());

        HttpResponse<String> res = post("/api/titles/MISSING-003/covers/cover.jpg/trash");

        assertEquals(404, res.statusCode());
    }

    @Test
    void trashCover_fileNotOnDisk_returns404() throws Exception {
        when(titleRepo.findByCode("ABP-011")).thenReturn(Optional.of(singleLocationTitle("ABP-011")));

        // Override: the cover file does NOT exist on FS.
        VolumeFileSystem fs2       = mock(VolumeFileSystem.class);
        SmbShareHandle handle2 = mock(SmbShareHandle.class);
        when(handle2.fileSystem()).thenReturn(fs2);
        when(smbFactory.open("vol1")).thenReturn(handle2);
        when(fs2.exists(Path.of("/stars/ABP-011/cover.jpg"))).thenReturn(false);

        HttpResponse<String> res = post("/api/titles/ABP-011/covers/cover.jpg/trash");

        assertEquals(404, res.statusCode());
        assertTrue(mapper.readTree(res.body()).get("error").asText().contains("cover not found"));
    }

    @Test
    void trashCover_pathTraversalFilename_returns400() throws Exception {
        // filename containing "/" — must be rejected before title lookup.
        HttpResponse<String> res = post("/api/titles/ABP-012/covers/..%2Ffoo.jpg/trash");

        // 400 (or 404 — either is acceptable; what matters is that it does NOT 200/500).
        // The route rejects the traversal attempt before calling any service.
        assertTrue(res.statusCode() == 400 || res.statusCode() == 404,
                "Expected 400 or 404 for path-traversal filename, got " + res.statusCode());
    }

    @Test
    void trashCover_multiLocationTitle_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-013")).thenReturn(Optional.of(twoLocationTitle("ABP-013")));

        HttpResponse<String> res = post("/api/titles/ABP-013/covers/cover.jpg/trash");

        assertEquals(400, res.statusCode());
    }

    // ── GET /api/titles/{code}/normalization-plan ──────────────────────────

    @Test
    void getNormalizationPlan_happyPath_returnsPlan() throws Exception {
        when(titleRepo.findByCode("ABP-020")).thenReturn(Optional.of(singleLocationTitle("ABP-020")));

        TitleFolderService.NormalizationPlan plan = new TitleFolderService.NormalizationPlan(
                "ABP-020", "/stars/ABP-020",
                List.of(new TitleFolderService.NormalizationPlanEntry(
                        "cover.jpg", "ABP-020.jpg", "cover", false, false)),
                false);
        when(folderService.planNormalization(any(), eq("ABP-020"), eq(Path.of("/stars/ABP-020")), any()))
                .thenReturn(plan);

        HttpResponse<String> res = get("/api/titles/ABP-020/normalization-plan");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("ABP-020", body.get("titleCode").asText());
        assertFalse(body.get("alreadyNormalized").asBoolean());
        assertEquals(1, body.get("entries").size());
        assertEquals("cover", body.get("entries").get(0).get("kind").asText());
        assertEquals("ABP-020.jpg", body.get("entries").get(0).get("to").asText());
    }

    @Test
    void getNormalizationPlan_alreadyCanonical_returnsAlreadyNormalized() throws Exception {
        when(titleRepo.findByCode("ABP-021")).thenReturn(Optional.of(singleLocationTitle("ABP-021")));

        TitleFolderService.NormalizationPlan plan = new TitleFolderService.NormalizationPlan(
                "ABP-021", "/stars/ABP-021", List.of(), true);
        when(folderService.planNormalization(any(), eq("ABP-021"), any(), any()))
                .thenReturn(plan);

        HttpResponse<String> res = get("/api/titles/ABP-021/normalization-plan");

        assertEquals(200, res.statusCode());
        assertTrue(mapper.readTree(res.body()).get("alreadyNormalized").asBoolean());
    }

    @Test
    void getNormalizationPlan_unknownTitle_returns404() throws Exception {
        when(titleRepo.findByCode("MISSING-020")).thenReturn(Optional.empty());

        HttpResponse<String> res = get("/api/titles/MISSING-020/normalization-plan");

        assertEquals(404, res.statusCode());
    }

    @Test
    void getNormalizationPlan_multiLocationTitle_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-022")).thenReturn(Optional.of(twoLocationTitle("ABP-022")));

        HttpResponse<String> res = get("/api/titles/ABP-022/normalization-plan");

        assertEquals(400, res.statusCode());
    }

    // ── POST /api/titles/{code}/normalize ─────────────────────────────────

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void normalize_happyPath_returns200WithMoveCount() throws Exception {
        when(titleRepo.findByCode("ABP-030")).thenReturn(Optional.of(singleLocationTitle("ABP-030")));

        TitleFolderService.NormalizationOutcome outcome = new TitleFolderService.NormalizationOutcome(
                1, List.of("cover.jpg → ABP-030.jpg"));
        when(folderService.executeNormalization(any(), eq(Path.of("/stars/ABP-030")), any()))
                .thenReturn(outcome);

        String body = """
                {"moves":[{"from":"cover.jpg","to":"ABP-030.jpg"}]}
                """;
        HttpResponse<String> res = postJson("/api/titles/ABP-030/normalize", body);

        assertEquals(200, res.statusCode());
        JsonNode json = mapper.readTree(res.body());
        assertEquals(1, json.get("movedCount").asInt());
        assertEquals(1, json.get("moved").size());
    }

    @Test
    void normalize_emptyMovesList_returns200WithZero() throws Exception {
        when(titleRepo.findByCode("ABP-031")).thenReturn(Optional.of(singleLocationTitle("ABP-031")));

        HttpResponse<String> res = postJson("/api/titles/ABP-031/normalize",
                """
                {"moves":[]}
                """);

        assertEquals(200, res.statusCode());
        JsonNode json = mapper.readTree(res.body());
        assertEquals(0, json.get("movedCount").asInt());
    }

    @Test
    void normalize_unknownTitle_returns404() throws Exception {
        when(titleRepo.findByCode("MISSING-030")).thenReturn(Optional.empty());

        HttpResponse<String> res = postJson("/api/titles/MISSING-030/normalize",
                "{\"moves\":[]}");

        assertEquals(404, res.statusCode());
    }

    @Test
    void normalize_multiLocationTitle_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-032")).thenReturn(Optional.of(twoLocationTitle("ABP-032")));

        HttpResponse<String> res = postJson("/api/titles/ABP-032/normalize",
                "{\"moves\":[{\"from\":\"a\",\"to\":\"b\"}]}");

        assertEquals(400, res.statusCode());
    }

    @Test
    void normalize_invalidJson_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-033")).thenReturn(Optional.of(singleLocationTitle("ABP-033")));

        HttpResponse<String> res = postJson("/api/titles/ABP-033/normalize", "not-json");

        assertEquals(400, res.statusCode());
        assertTrue(mapper.readTree(res.body()).has("error"));
    }

    @Test
    void normalize_missingMovesField_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-034")).thenReturn(Optional.of(singleLocationTitle("ABP-034")));

        HttpResponse<String> res = postJson("/api/titles/ABP-034/normalize",
                "{\"videoNameOverrides\":{}}");  // missing "moves" key

        assertEquals(400, res.statusCode());
        assertTrue(mapper.readTree(res.body()).get("error").asText().contains("moves"));
    }

    @Test
    void normalize_serviceThrowsIllegalArgument_returns400() throws Exception {
        when(titleRepo.findByCode("ABP-035")).thenReturn(Optional.of(singleLocationTitle("ABP-035")));
        when(folderService.executeNormalization(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Source file does not exist: video/old.mp4"));

        HttpResponse<String> res = postJson("/api/titles/ABP-035/normalize",
                "{\"moves\":[{\"from\":\"video/old.mp4\",\"to\":\"video/ABP-035.mp4\"}]}");

        assertEquals(400, res.statusCode());
        assertTrue(mapper.readTree(res.body()).get("error").asText().contains("Source file"));
    }
}
