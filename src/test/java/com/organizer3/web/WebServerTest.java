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
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
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
        VolumeConfig conventional = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora");
        VolumeConfig pool         = new VolumeConfig("unsorted", "//pandora/jav_unsorted", "queue", "pandora");

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
                "Test", null, null, null, List.of(),
                List.of(conventional, pool),
                List.of(conventionalDef, queueDef),
                List.of()
        ));

        server = new WebServer(0, null, null, null);
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

        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, mock(TitleActressRepository.class));
        server = new WebServer(0, browseService, null, null);
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

        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, mock(TitleActressRepository.class));
        server = new WebServer(0, browseService, null, null);
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

        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, mock(TitleActressRepository.class));
        server = new WebServer(0, browseService, null, null);
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
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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
        when(titleRepo.findByActress(1L)).thenReturn(List.of());

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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
        when(actressRepo.findByTier(Actress.Tier.GODDESS)).thenReturn(List.of(goddess));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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
        when(titleRepo.findByActress(42L)).thenReturn(List.of());

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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
                actressRepo, titleRepo, coverPath, Map.of(), labelRepo, mock(ActressNameLookup.class), null);
        server = new WebServer(0, null, actressBrowse, null);
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

        server = new WebServer(0, null, null, tempDir);
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

        server = new WebServer(0, null, null, tempDir);
        server.start();

        HttpResponse<String> response = get("/covers/ABP/nonexistent.jpg");
        assertEquals(404, response.statusCode());

        java.nio.file.Files.delete(tempDir);
    }

    @Test
    void coversEndpointRejectsPathTraversal() throws IOException, InterruptedException {
        Path tempDir = java.nio.file.Files.createTempDirectory("covers-test");

        server = new WebServer(0, null, null, tempDir);
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
                "TestApp", 100, null, null, List.of(), List.of(), List.of(), List.of()));

        server = new WebServer(0, null, null, null);
        server.start();

        HttpResponse<String> response = get("/api/config");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertEquals("TestApp", body.get("appName").asText());
        assertEquals(100, body.get("maxBrowseTitles").asInt());
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

    /**
     * A second queue-type volume (e.g. "classic") must also end up as a pool candidate,
     * not in the volumes list.
     */
    @Test
    void queuesVolumesEndpoint_multipleQueueTypeVolumesDoNotLeakIntoVolumesList() throws IOException, InterruptedException {
        VolumeConfig conventional = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora");
        VolumeConfig pool1        = new VolumeConfig("unsorted", "//pandora/jav_unsorted", "queue", "pandora");
        VolumeConfig pool2        = new VolumeConfig("classic", "//qnap2/classic", "queue", "qnap2");

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
                "Test", null, null, null, List.of(),
                List.of(conventional, pool1, pool2),
                List.of(conventionalDef, queueDef),
                List.of()
        ));

        server = new WebServer(0, null, null, null);
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
