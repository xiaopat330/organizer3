package com.organizer3.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.ai.ActressNameLookup;
import com.organizer3.rating.RatingCurveRepository;
import com.organizer3.rating.RatingScoreCalculator;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import com.organizer3.media.ThumbnailService;
import com.organizer3.media.VideoProbe;
import com.organizer3.model.Video;
import com.organizer3.model.WatchHistory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebServerTest {

    private WebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        AppConfig.reset();
    }

    /** Creates an ActressBrowseService with a mocked Jdbi so avatar-URL lookups don't NPE. */
    private static ActressBrowseService browseService(
            ActressRepository actressRepo, TitleRepository titleRepo,
            CoverPath coverPath, LabelRepository labelRepo) {
        Jdbi jdbi = mock(Jdbi.class);
        when(jdbi.withHandle(any())).thenReturn(Map.of());
        RatingCurveRepository curveRepo = mock(RatingCurveRepository.class);
        when(curveRepo.find()).thenReturn(java.util.Optional.empty());
        when(titleRepo.findRatingDataByTitleIds(any())).thenReturn(Map.of());
        return new ActressBrowseService(actressRepo, titleRepo, coverPath,
                Map.of(), labelRepo, mock(ActressNameLookup.class), null, jdbi,
                curveRepo, new RatingScoreCalculator());
    }

    @Test
    void startsAndListensOnConfiguredPort() {
        server = new WebServer(0);
        server.start();

        int port = server.port();
        assertTrue(port > 0, "Server should bind to an actual port");
    }

    @Test
    void rootEndpointServesHtml() throws IOException, InterruptedException {
        server = new WebServer(0);
        server.start();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/"))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("JAV Helper"),
                "Root should serve the HTML home page");
    }

    @Test
    void stopShutsDownCleanly() {
        server = new WebServer(0);
        server.start();
        int port = server.port();

        server.stop();
        server = null;

        assertThrows(Exception.class, () ->
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/"))
                                .GET()
                                .build(), HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * Conventional volumes (structuredPartition present + "queue" unstructured partition)
     * must appear in the "volumes" list. Queue-type volumes (no structuredPartition) must
     * appear as "pool", not in "volumes". This guards against the regression where queue-type
     * volumes leaked into the volumes list and displayed pool content when clicked.
     */
    @Test
    void queuesVolumesEndpoint_separatesConventionalFromPoolVolumes() throws IOException, InterruptedException {
        VolumeConfig conventional = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        VolumeConfig pool         = new VolumeConfig("unsorted", "//pandora/jav_unsorted", "queue", "pandora", null);

        VolumeStructureDef conventionalDef = new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );
        VolumeStructureDef queueDef = new VolumeStructureDef(
                "queue",
                List.of(new PartitionDef("queue", "fresh")),
                null   // no structuredPartition — this is the distinguishing factor
        );

        AppConfig.initializeForTest(new OrganizerConfig(
                "Test", null, null, null, null, null, null, null, List.of(),
                List.of(conventional, pool),
                List.of(conventionalDef, queueDef),
                List.of(),
                null
        ));

        server = new WebServer(0, null, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/api/queues/volumes"))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());

        // Pool should be the queue-type volume
        assertTrue(body.has("pool"), "Response should include a pool entry");
        assertEquals("unsorted", body.get("pool").get("id").asText());
        assertEquals("//pandora/jav_unsorted", body.get("pool").get("smbPath").asText());

        // Volumes list should contain only the conventional volume
        JsonNode volumes = body.get("volumes");
        assertNotNull(volumes);
        assertEquals(1, volumes.size(), "Only conventional volumes should appear in volumes list");
        assertEquals("a", volumes.get(0).get("id").asText());
        assertEquals("//pandora/jav_A", volumes.get(0).get("smbPath").asText());
    }

    // ── Title browse API ─────────────────────────────────────────────────

    @Test
    void titlesEndpointReturnsTitleSummaries() throws IOException, InterruptedException {
        TitleRepository titleRepo = mock(TitleRepository.class);
        ActressRepository actressRepo = mock(ActressRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        Title title = Title.builder()
                .id(1L).code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("queue")
                        .path(Path.of("/queue/ABP-001"))
                        .lastSeenAt(LocalDate.now()).addedDate(LocalDate.of(2025, 3, 1))
                        .build()))
                .build();
        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(coverPath.find(any(Title.class))).thenReturn(Optional.empty());

        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, mock(TitleActressRepository.class), mock(WatchHistoryRepository.class), Map.of());
        server = new WebServer(0, browseService, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("ABP-001", body.get(0).get("code").asText());
    }

    @Test
    void titlesEndpointRespectsOffsetAndLimit() throws IOException, InterruptedException {
        TitleRepository titleRepo = mock(TitleRepository.class);
        ActressRepository actressRepo = mock(ActressRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        when(titleRepo.findRecent(10, 5)).thenReturn(List.of());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());

        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, mock(TitleActressRepository.class), mock(WatchHistoryRepository.class), Map.of());
        server = new WebServer(0, browseService, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles?offset=5&limit=10");
        assertEquals(200, response.statusCode());
        verify(titleRepo).findRecent(10, 5);
    }

    // ── Queue titles API ────────────────────────────────────────────────

    @Test
    void queueTitlesEndpointReturnsTitlesForVolume() throws IOException, InterruptedException {
        TitleRepository titleRepo = mock(TitleRepository.class);
        ActressRepository actressRepo = mock(ActressRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        Title queueTitle = Title.builder()
                .id(1L).code("SSIS-001").baseCode("SSIS-00001").label("SSIS").seqNum(1)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("queue")
                        .path(Path.of("/queue/SSIS-001"))
                        .lastSeenAt(LocalDate.now())
                        .build()))
                .build();
        when(titleRepo.findByVolumeAndPartition("a", "queue", 24, 0)).thenReturn(List.of(queueTitle));
        when(titleRepo.findByBaseCode("SSIS-00001")).thenReturn(List.of());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(coverPath.find(any(Title.class))).thenReturn(Optional.empty());

        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, mock(TitleActressRepository.class), mock(WatchHistoryRepository.class), Map.of());
        server = new WebServer(0, browseService, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/queues/a/titles");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("SSIS-001", body.get(0).get("code").asText());
    }

    // ── Actress browse API ──────────────────────────────────────────────

    @Test
    void actressesPrefixIndexEndpoint() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        Actress aya = Actress.builder().id(1L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).build();
        Actress mia = Actress.builder().id(2L).canonicalName("Mia Nanasawa")
                .tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.findAll()).thenReturn(List.of(aya, mia));

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null, null,
                mock(RatingCurveRepository.class), new RatingScoreCalculator());
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/index");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertTrue(body.size() >= 2);
    }

    @Test
    void actressesByPrefixEndpoint() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        Actress aya = Actress.builder().id(1L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).firstSeenAt(LocalDate.of(2025, 1, 1)).build();
        when(actressRepo.findByFirstNamePrefixPaged(eq("A"), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(aya));

        ActressBrowseService actressBrowse = browseService(actressRepo, titleRepo, coverPath, labelRepo);
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses?prefix=A");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("Aya Sazanami", body.get(0).get("canonicalName").asText());
    }

    @Test
    void actressesByTierEndpoint() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        Actress goddess = Actress.builder().id(1L).canonicalName("Yua Mikami")
                .tier(Actress.Tier.GODDESS).firstSeenAt(LocalDate.of(2024, 1, 1)).build();
        when(actressRepo.findByTierPaged(Actress.Tier.GODDESS, 24, 0)).thenReturn(List.of(goddess));

        ActressBrowseService actressBrowse = browseService(actressRepo, titleRepo, coverPath, labelRepo);
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses?tier=GODDESS");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals("Yua Mikami", body.get(0).get("canonicalName").asText());
    }

    @Test
    void actressesEndpointReturns400WhenNoPrefixOrTier() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null, null,
                mock(RatingCurveRepository.class), new RatingScoreCalculator());
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses");
        assertEquals(400, response.statusCode());
    }

    @Test
    void actressByIdEndpoint() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        Actress aya = Actress.builder().id(42L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).firstSeenAt(LocalDate.of(2025, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(aya));

        ActressBrowseService actressBrowse = browseService(actressRepo, titleRepo, coverPath, labelRepo);
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/42");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertEquals("Aya Sazanami", body.get("canonicalName").asText());
    }

    @Test
    void actressByIdReturns404WhenNotFound() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        when(actressRepo.findById(999L)).thenReturn(Optional.empty());

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null, null,
                mock(RatingCurveRepository.class), new RatingScoreCalculator());
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/999");
        assertEquals(404, response.statusCode());
    }

    @Test
    void actressByIdReturns400ForNonNumericId() throws IOException, InterruptedException {
        ActressRepository actressRepo = mock(ActressRepository.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        LabelRepository labelRepo = mock(LabelRepository.class);
        CoverPath coverPath = mock(CoverPath.class);

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null, null,
                mock(RatingCurveRepository.class), new RatingScoreCalculator());
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/abc");
        assertEquals(400, response.statusCode());
    }

    // ── Cover serving API ───────────────────────────────────────────────

    @Test
    void coversEndpointServesImage() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("covers-test");
        Path labelDir = tempDir.resolve("ABP");
        java.nio.file.Files.createDirectories(labelDir);
        Path imageFile = labelDir.resolve("ABP-00001.jpg");
        java.nio.file.Files.writeString(imageFile, "fake-jpg-data");

        server = new WebServer(0, null, null, tempDir, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/covers/ABP/ABP-00001.jpg");
        assertEquals(200, response.statusCode());
        assertEquals("fake-jpg-data", response.body());

        // Cleanup
        java.nio.file.Files.delete(imageFile);
        java.nio.file.Files.delete(labelDir);
        java.nio.file.Files.delete(tempDir);
    }

    @Test
    void coversEndpointReturns404ForMissingFile() throws IOException, InterruptedException {
        Path tempDir = java.nio.file.Files.createTempDirectory("covers-test");

        server = new WebServer(0, null, null, tempDir, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/covers/ABP/nonexistent.jpg");
        assertEquals(404, response.statusCode());

        java.nio.file.Files.delete(tempDir);
    }

    @Test
    void coversEndpointRejectsPathTraversal() throws IOException, InterruptedException {
        Path tempDir = java.nio.file.Files.createTempDirectory("covers-test");

        server = new WebServer(0, null, null, tempDir, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/covers/../etc/passwd");
        // Javalin may return 400 or 404 depending on how it handles the path
        assertTrue(response.statusCode() >= 400);

        java.nio.file.Files.delete(tempDir);
    }

    // ── Config API ──────────────────────────────────────────────────────

    @Test
    void configEndpointReturnsAppNameAndMaxBrowse() throws IOException, InterruptedException {
        AppConfig.initializeForTest(new OrganizerConfig(
                "TestApp", null, 100, null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), null));

        server = new WebServer(0, null, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/config");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertEquals("TestApp", body.get("appName").asText());
        assertEquals(100, body.get("maxBrowseTitles").asInt());
    }

    // ── ActressRoutes gap-fill ──────────────────────────────────────────

    @Test
    void actressesTierCountsEndpointReturnsMap() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.findTierCountsByPrefix("A")).thenReturn(Map.of("GODDESS", 2, "POPULAR", 5));

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/tier-counts?prefix=A");
        assertEquals(200, response.statusCode());
        assertEquals(2, mapper.readTree(response.body()).get("GODDESS").asInt());
    }

    @Test
    void actressesTierCountsReturns400WhenPrefixBlank() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/tier-counts");
        assertEquals(400, response.statusCode());
    }

    @Test
    void actressesRandomEndpointDelegates() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.findRandom(anyInt())).thenReturn(List.of());

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/random?limit=10");
        assertEquals(200, response.statusCode());
        verify(actressBrowse).findRandom(10);
    }

    @Test
    void actressesDashboardEndpointDelegates() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        // Route invoked — delegation is what matters; the null dashboard serializes to null.
        get("/api/actresses/dashboard");
        verify(actressBrowse).buildDashboard();
    }

    @Test
    void actressesSpotlightEndpointReturns204WhenNull() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.getSpotlight(null)).thenReturn(null);

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/spotlight");
        assertEquals(204, response.statusCode());
    }

    @Test
    void actressesSpotlightEndpointReturns400ForNonNumericExclude() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/spotlight?exclude=abc");
        assertEquals(400, response.statusCode());
    }

    @Test
    void actressAliasesPutReturns400ForNonNumericId() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/api/actresses/abc/aliases"))
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"aliases\":[]}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    @Test
    void actressTitlesEndpointDelegates() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.findTitlesByActress(eq(42L), eq(0), eq(24), any(), any(), any())).thenReturn(List.of());

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/42/titles");
        assertEquals(200, response.statusCode());
    }

    @Test
    void actressTagsEndpointDelegates() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.findTagsForActress(42L)).thenReturn(List.of("creampie"));

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/actresses/42/tags");
        assertEquals(200, response.statusCode());
        assertEquals(1, mapper.readTree(response.body()).size());
    }

    @Test
    void actressFavoritePostReturnsFlagState() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.toggleFavorite(42L)).thenReturn(Optional.of(
                new ActressBrowseService.FlagState(42L, true, false, false)));

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = post("/api/actresses/42/favorite");
        assertEquals(200, response.statusCode());
        assertTrue(mapper.readTree(response.body()).get("favorite").asBoolean());
    }

    @Test
    void actressFavoritePostReturns404WhenMissing() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.toggleFavorite(99L)).thenReturn(Optional.empty());

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = post("/api/actresses/99/favorite");
        assertEquals(404, response.statusCode());
    }

    @Test
    void actressBookmarkPostWithValueParamCallsSetBookmark() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.setBookmark(42L, true)).thenReturn(Optional.of(
                new ActressBrowseService.FlagState(42L, false, true, false)));

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = post("/api/actresses/42/bookmark?value=true");
        assertEquals(200, response.statusCode());
        verify(actressBrowse).setBookmark(42L, true);
    }

    @Test
    void actressVisitPostReturnsVisitStats() throws IOException, InterruptedException {
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        when(actressBrowse.recordVisit(42L)).thenReturn(Optional.of(
                new ActressBrowseService.VisitStats(5, "2026-04-20T10:00")));

        server = new WebServer(0, null, actressBrowse, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = post("/api/actresses/42/visit");
        assertEquals(200, response.statusCode());
        assertEquals(5, mapper.readTree(response.body()).get("visitCount").asInt());
    }

    // ── TitleRoutes gap-fill ────────────────────────────────────────────

    @Test
    void tagsEndpointReturns200() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/tags");
        assertEquals(200, response.statusCode());
    }

    @Test
    void labelsAutocompleteEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.labelAutocomplete("AB")).thenReturn(List.of("ABP", "ABS"));

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/labels/autocomplete?prefix=AB");
        assertEquals(200, response.statusCode());
        assertEquals(2, mapper.readTree(response.body()).size());
    }

    @Test
    void titlesLabelsEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.listLabels()).thenReturn(List.of());

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/labels");
        assertEquals(200, response.statusCode());
        verify(browse).listLabels();
    }

    @Test
    void titlesTopActressesEndpointReturnsEmptyForMissingLabels() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/top-actresses");
        assertEquals(200, response.statusCode());
        assertEquals(0, mapper.readTree(response.body()).size());
        verify(browse, never()).topActressesByLabels(any(), anyInt());
    }

    @Test
    void titlesTopActressesPassesLabelList() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.topActressesByLabels(anyList(), anyInt())).thenReturn(List.of());

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/top-actresses?labels=ABP,SSIS&limit=5");
        assertEquals(200, response.statusCode());
        verify(browse).topActressesByLabels(List.of("ABP", "SSIS"), 5);
    }

    @Test
    void titlesDashboardEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        get("/api/titles/dashboard");
        verify(browse).buildDashboard();
    }

    @Test
    void titlesSpotlightEndpointReturns204WhenNull() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.getSpotlight(null)).thenReturn(null);

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/spotlight");
        assertEquals(204, response.statusCode());
    }

    @Test
    void titlesRandomEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.findRandom(anyInt())).thenReturn(List.of());

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/random?limit=12");
        assertEquals(200, response.statusCode());
        verify(browse).findRandom(12);
    }

    @Test
    void poolTitlesEndpointDelegatesWithoutFilters() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.findByVolumePartition(eq("vol-a"), eq("pool"), anyInt(), anyInt())).thenReturn(List.of());

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/pool/vol-a/titles");
        assertEquals(200, response.statusCode());
        verify(browse).findByVolumePartition("vol-a", "pool", 0, 24);
    }

    @Test
    void poolTitlesEndpointUsesFilteredWhenCompanyProvided() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.findByVolumePartitionFiltered(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of());

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/pool/vol-a/titles?company=Prestige");
        assertEquals(200, response.statusCode());
        verify(browse).findByVolumePartitionFiltered("vol-a", "pool", "Prestige", List.of(), 0, 24);
    }

    @Test
    void collectionsTitlesEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.findByVolumePaged(eq("collections"), anyInt(), anyInt())).thenReturn(List.of());

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/collections/titles");
        assertEquals(200, response.statusCode());
        verify(browse).findByVolumePaged("collections", 0, 24);
    }

    @Test
    void companiesEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.listAllCompanies()).thenReturn(List.of("Prestige", "Moodyz"));

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/companies");
        assertEquals(200, response.statusCode());
        assertEquals(2, mapper.readTree(response.body()).size());
    }

    @Test
    void titleVisitPostReturnsStats() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.recordVisit("ABP-001")).thenReturn(Optional.of(
                new TitleBrowseService.VisitStats(3, "2026-04-20T10:00")));

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = post("/api/titles/ABP-001/visit");
        assertEquals(200, response.statusCode());
        assertEquals(3, mapper.readTree(response.body()).get("visitCount").asInt());
    }

    @Test
    void titleFavoritePostTogglesFavorite() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        Title t = Title.builder().id(1L).code("ABP-001").favorite(false).build();
        when(titleRepo.findByCode("ABP-001")).thenReturn(Optional.of(t));

        server = new WebServer(0, browse, null, null, null, null, null, null, titleRepo, null);
        server.start();

        HttpResponse<String> response = post("/api/titles/ABP-001/favorite");
        assertEquals(200, response.statusCode());
        verify(titleRepo).toggleFavorite(1L, true);
    }

    @Test
    void titleBookmarkPostRespectsValueParam() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        Title t = Title.builder().id(1L).code("ABP-001").build();
        when(titleRepo.findByCode("ABP-001")).thenReturn(Optional.of(t));

        server = new WebServer(0, browse, null, null, null, null, null, null, titleRepo, null);
        server.start();

        HttpResponse<String> response = post("/api/titles/ABP-001/bookmark?value=true");
        assertEquals(200, response.statusCode());
        verify(titleRepo).toggleBookmark(1L, true);
    }

    @Test
    void titleFavoritePostReturns404WhenMissing() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        when(titleRepo.findByCode("NOPE")).thenReturn(Optional.empty());

        server = new WebServer(0, browse, null, null, null, null, null, null, titleRepo, null);
        server.start();

        HttpResponse<String> response = post("/api/titles/NOPE/favorite");
        assertEquals(404, response.statusCode());
    }

    @Test
    void toolsDuplicatesEndpointDelegates() throws IOException, InterruptedException {
        TitleBrowseService browse = mock(TitleBrowseService.class);
        when(browse.findDuplicatesPaged(anyInt(), anyInt(), any()))
                .thenReturn(new TitleBrowseService.DuplicatePage(List.of(), 0));

        server = new WebServer(0, browse, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/tools/duplicates?limit=20");
        assertEquals(200, response.statusCode());
        assertEquals(0, mapper.readTree(response.body()).get("total").asInt());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── SearchRoutes ────────────────────────────────────────────────────

    @Test
    void searchEndpointReturnsEmptyGroupsForBlankQuery() throws IOException, InterruptedException {
        SearchService searchService = mock(SearchService.class);
        server = new WebServer(0, null, null, null, null, null, null, null, null, searchService);
        server.start();

        HttpResponse<String> response = get("/api/search?q=");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertEquals(0, body.get("actresses").size());
        assertEquals(0, body.get("titles").size());
        verifyNoInteractions(searchService);
    }

    @Test
    void searchEndpointDelegatesStartsWithAndIncludeAv() throws IOException, InterruptedException {
        SearchService searchService = mock(SearchService.class);
        when(searchService.search(anyString(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(Map.of("actresses", List.of(), "titles", List.of(),
                        "labels", List.of(), "companies", List.of(), "avActresses", List.of()));
        server = new WebServer(0, null, null, null, null, null, null, null, null, searchService);
        server.start();

        HttpResponse<String> response = get("/api/search?q=yua&matchMode=startsWith&includeAv=true");
        assertEquals(200, response.statusCode());
        verify(searchService).search("yua", true, true, false);
    }

    @Test
    void titlesByCodePrefixEndpointReturnsResults() throws IOException, InterruptedException {
        SearchService searchService = mock(SearchService.class);
        when(searchService.searchByCodePrefix("ABP", 11)).thenReturn(List.of(Map.of("code", "ABP-001")));
        server = new WebServer(0, null, null, null, null, null, null, null, null, searchService);
        server.start();

        HttpResponse<String> response = get("/api/titles/by-code-prefix?prefix=ABP&limit=11");
        assertEquals(200, response.statusCode());
        assertEquals(1, mapper.readTree(response.body()).size());
    }

    @Test
    void titlesByCodePrefixEndpointRejectsBlankPrefix() throws IOException, InterruptedException {
        SearchService searchService = mock(SearchService.class);
        server = new WebServer(0, null, null, null, null, null, null, null, null, searchService);
        server.start();

        HttpResponse<String> response = get("/api/titles/by-code-prefix?prefix=");
        assertEquals(200, response.statusCode());
        assertEquals(0, mapper.readTree(response.body()).size());
        verifyNoInteractions(searchService);
    }

    @Test
    void titleByCodeEndpointReturns404WhenMissing() throws IOException, InterruptedException {
        SearchService searchService = mock(SearchService.class);
        TitleRepository titleRepo = mock(TitleRepository.class);
        when(titleRepo.findByCode("ABP-999")).thenReturn(Optional.empty());
        server = new WebServer(0, null, null, null, null, null, null, null, titleRepo, searchService);
        server.start();

        HttpResponse<String> response = get("/api/titles/by-code/ABP-999");
        assertEquals(404, response.statusCode());
    }

    // ── WatchHistoryRoutes ──────────────────────────────────────────────

    @Test
    void watchHistoryPostEndpointRecordsAndReturnsEntry() throws IOException, InterruptedException {
        WatchHistoryRepository watchRepo = mock(WatchHistoryRepository.class);
        WatchHistory entry = WatchHistory.builder().id(42L).titleCode("ABP-123")
                .watchedAt(java.time.LocalDateTime.of(2026, 4, 20, 10, 0)).build();
        when(watchRepo.record(eq("ABP-123"), any())).thenReturn(entry);

        server = new WebServer(0, null, null, null, null, null, null, watchRepo, null, null);
        server.start();

        HttpResponse<String> response = post("/api/watch-history/ABP-123");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(42L, body.get("id").asLong());
        assertEquals("ABP-123", body.get("titleCode").asText());
    }

    @Test
    void watchHistoryGetAllEndpointReturnsList() throws IOException, InterruptedException {
        WatchHistoryRepository watchRepo = mock(WatchHistoryRepository.class);
        WatchHistory e1 = WatchHistory.builder().id(1L).titleCode("A")
                .watchedAt(java.time.LocalDateTime.now()).build();
        WatchHistory e2 = WatchHistory.builder().id(2L).titleCode("B")
                .watchedAt(java.time.LocalDateTime.now()).build();
        when(watchRepo.findAll(50)).thenReturn(List.of(e1, e2));

        server = new WebServer(0, null, null, null, null, null, null, watchRepo, null, null);
        server.start();

        HttpResponse<String> response = get("/api/watch-history");
        assertEquals(200, response.statusCode());
        assertEquals(2, mapper.readTree(response.body()).size());
    }

    @Test
    void watchHistoryGetByTitleEndpointFiltersByCode() throws IOException, InterruptedException {
        WatchHistoryRepository watchRepo = mock(WatchHistoryRepository.class);
        when(watchRepo.findByTitleCode("ABP-1")).thenReturn(List.of(
                WatchHistory.builder().id(7L).titleCode("ABP-1")
                        .watchedAt(java.time.LocalDateTime.now()).build()));

        server = new WebServer(0, null, null, null, null, null, null, watchRepo, null, null);
        server.start();

        HttpResponse<String> response = get("/api/watch-history/ABP-1");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(1, body.size());
        assertEquals("ABP-1", body.get(0).get("titleCode").asText());
    }

    // ── VideoRoutes ─────────────────────────────────────────────────────

    @Test
    void videosByTitleCodeEndpointDelegatesToStreamService() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);
        when(videoStream.findVideos("ABP-123", null)).thenReturn(List.of());

        server = new WebServer(0, null, null, null, videoStream, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/ABP-123/videos");
        assertEquals(200, response.statusCode());
        verify(videoStream).findVideos("ABP-123", null);
    }

    @Test
    void videosByTitleCodePassesVolumeIdWhenProvided() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);
        when(videoStream.findVideos("ABP-123", "vol-a")).thenReturn(List.of());

        server = new WebServer(0, null, null, null, videoStream, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/titles/ABP-123/videos?volumeId=vol-a");
        assertEquals(200, response.statusCode());
        verify(videoStream).findVideos("ABP-123", "vol-a");
    }

    @Test
    void videoStreamEndpointReturns400ForNonNumericId() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);

        server = new WebServer(0, null, null, null, videoStream, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/stream/not-a-number");
        assertEquals(400, response.statusCode());
    }

    @Test
    void videoStreamEndpointReturns404WhenVideoMissing() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);
        when(videoStream.findVideoById(99L)).thenReturn(Optional.empty());

        server = new WebServer(0, null, null, null, videoStream, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/stream/99");
        assertEquals(404, response.statusCode());
    }

    @Test
    void videoInfoEndpointProbesAndReturnsJson() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);
        VideoProbe videoProbe = mock(VideoProbe.class);
        Video video = Video.builder().id(1L).filename("test.mp4").build();
        when(videoStream.findVideoById(1L)).thenReturn(Optional.of(video));
        when(videoProbe.probe(1L, "test.mp4")).thenReturn(Map.of("duration", 3600));

        server = new WebServer(0, null, null, null, videoStream, null, videoProbe, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/videos/1/info");
        assertEquals(200, response.statusCode());
        assertEquals(3600, mapper.readTree(response.body()).get("duration").asInt());
    }

    @Test
    void videoThumbnailsEndpointReturns404WhenVideoMissing() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);
        ThumbnailService thumbs = mock(ThumbnailService.class);
        when(videoStream.findVideoById(99L)).thenReturn(Optional.empty());

        server = new WebServer(0, null, null, null, videoStream, thumbs, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/videos/99/thumbnails");
        assertEquals(404, response.statusCode());
    }

    @Test
    void videoThumbnailsEndpointReturnsStatus() throws IOException, InterruptedException {
        VideoStreamService videoStream = mock(VideoStreamService.class);
        ThumbnailService thumbs = mock(ThumbnailService.class);
        Video video = Video.builder().id(1L).filename("test.mp4").build();
        when(videoStream.findVideoById(1L)).thenReturn(Optional.of(video));
        when(videoStream.titleCodeForVideo(video)).thenReturn("ABP-123");
        when(thumbs.getThumbnailStatus("ABP-123", video)).thenReturn(Map.of("ready", true, "urls", List.of()));

        server = new WebServer(0, null, null, null, videoStream, thumbs, null, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/videos/1/thumbnails");
        assertEquals(200, response.statusCode());
        assertTrue(mapper.readTree(response.body()).get("ready").asBoolean());
    }

    /**
     * A second queue-type volume (e.g. "classic") must also end up as a pool candidate,
     * not in the volumes list.
     */
    @Test
    void queuesVolumesEndpoint_multipleQueueTypeVolumesDoNotLeakIntoVolumesList() throws IOException, InterruptedException {
        VolumeConfig conventional = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        VolumeConfig pool1        = new VolumeConfig("unsorted", "//pandora/jav_unsorted", "queue", "pandora", null);
        VolumeConfig pool2        = new VolumeConfig("classic", "//qnap2/classic", "queue", "qnap2", null);

        VolumeStructureDef conventionalDef = new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
        );
        VolumeStructureDef queueDef = new VolumeStructureDef(
                "queue",
                List.of(new PartitionDef("queue", "fresh")),
                null
        );

        AppConfig.initializeForTest(new OrganizerConfig(
                "Test", null, null, null, null, null, null, null, List.of(),
                List.of(conventional, pool1, pool2),
                List.of(conventionalDef, queueDef),
                List.of(),
                null
        ));

        server = new WebServer(0, null, null, null, null, null, null, null, null, null);
        server.start();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/api/queues/volumes"))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());

        JsonNode volumes = body.get("volumes");
        assertNotNull(volumes);
        assertEquals(1, volumes.size(), "Queue-type volumes must not appear in the volumes list");
        assertEquals("a", volumes.get(0).get("id").asText());
    }
}
