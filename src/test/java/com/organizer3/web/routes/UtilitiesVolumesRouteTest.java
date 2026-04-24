package com.organizer3.web.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.utilities.volume.StaleLocationsService;
import com.organizer3.utilities.volume.VolumeStateService;
import io.javalin.Javalin;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UtilitiesVolumesRouteTest {

    private Connection connection;
    private Jdbi jdbi;
    private Javalin app;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final VolumeConfig VOLUME_A =
            new VolumeConfig("vol-a", "//nas/jav_a", "conventional", "nas", null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        VolumeRepository volumeRepo = mock(VolumeRepository.class);
        TitleRepository  titleRepo  = mock(TitleRepository.class);
        when(volumeRepo.findById(any())).thenReturn(Optional.empty());
        when(titleRepo.countByVolume(any())).thenReturn(0);

        AppConfig.initializeForTest(new OrganizerConfig(
                "Test", null, null, null, null, null, null, null,
                List.of(), List.of(VOLUME_A), List.of(), List.of(), null));

        VolumeStateService service = new VolumeStateService(
                volumeRepo, titleRepo, new StaleLocationsService(jdbi), jdbi);

        app = Javalin.create()
                .get("/api/utilities/volumes",     ctx -> ctx.json(service.list()))
                .get("/api/utilities/volumes/{id}", ctx ->
                        service.find(ctx.pathParam("id")).ifPresentOrElse(
                                ctx::json,
                                () -> { ctx.status(404); ctx.json(Map.of("error", "not found")); }));
        app.start(0);
        port = app.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        app.stop();
        AppConfig.reset();
        connection.close();
    }

    @Test
    void listIncludesQueueCountFieldWithZero() throws Exception {
        var body = getList("/api/utilities/volumes");
        assertEquals(1, body.size());
        assertTrue(body.get(0).containsKey("queueCount"), "queueCount field must be present");
        assertEquals(0, body.get(0).get("queueCount"));
    }

    @Test
    void listReflectsQueueTitles() throws Exception {
        insertTitle(1L, "ABP-001");
        insertTitle(2L, "ABP-002");
        insertLocation(1L, "vol-a", "queue");
        insertLocation(2L, "vol-a", "queue");

        var body = getList("/api/utilities/volumes");
        assertEquals(2, body.get(0).get("queueCount"));
    }

    @Test
    void singleVolumeEndpointIncludesQueueCount() throws Exception {
        insertTitle(1L, "ABP-001");
        insertLocation(1L, "vol-a", "queue");

        var res = get("/api/utilities/volumes/vol-a");
        assertEquals(200, res.statusCode());
        Map<String, Object> body = mapper.readValue(res.body(), new TypeReference<>() {});
        assertTrue(body.containsKey("queueCount"), "queueCount field must be present");
        assertEquals(1, body.get("queueCount"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> getList(String path) throws Exception {
        var res = get(path);
        assertEquals(200, res.statusCode());
        return mapper.readValue(res.body(), new TypeReference<>() {});
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void insertTitle(long id, String code) {
        jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (:id, :code, :code, 'ABP', 1)")
                .bind("id", id).bind("code", code).execute());
    }

    private void insertLocation(long titleId, String volumeId, String partitionId) {
        jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, added_date)
                VALUES (:titleId, :vol, :part, '/path', date('now'), date('now'))
                """)
                .bind("titleId", titleId).bind("vol", volumeId).bind("part", partitionId).execute());
    }
}
