package com.organizer3.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
                "Test", null, List.of(),
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
                "Test", null, List.of(),
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
