package com.organizer3.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.model.Actress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that exercise real SQL + real services + real HTTP.
 *
 * <p>Each test seeds an in-memory SQLite with a minimal fixture, hits an API,
 * and asserts on the JSON response. Catches bugs the mocked-service unit
 * tests can't: SQL syntax errors, schema drift, row mapper bugs,
 * JSON-column round-trips, service wiring.
 */
class WebIntegrationTest {

    private IntegrationTestHarness h;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        h = new IntegrationTestHarness();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.close();
    }

    @Test
    void recentTitlesEndpointReturnsSeededTitle() throws IOException, InterruptedException {
        h.seedMinimal();

        HttpResponse<String> response = get("/api/titles");
        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("ABP-001", body.get(0).get("code").asText());
        assertEquals("Aya Sazanami", body.get(0).get("actressName").asText());
    }

    @Test
    void titleByCodePagedSearchReturnsMatch() throws IOException, InterruptedException {
        h.seedMinimal();

        HttpResponse<String> response = get("/api/titles?search=ABP-001");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(1, body.size());
        assertEquals("ABP-001", body.get(0).get("code").asText());
    }

    @Test
    void actressByIdReturnsCanonicalNameAndTier() throws IOException, InterruptedException {
        var seed = h.seedMinimal();

        HttpResponse<String> response = get("/api/actresses/" + seed.actressId());
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals("Aya Sazanami", body.get("canonicalName").asText());
        assertEquals("GODDESS", body.get("tier").asText());
    }

    @Test
    void actressTitlesEndpointReturnsSeededTitle() throws IOException, InterruptedException {
        var seed = h.seedMinimal();

        HttpResponse<String> response = get("/api/actresses/" + seed.actressId() + "/titles");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("ABP-001", body.get(0).get("code").asText());
    }

    @Test
    void visitPostIncrementsActressVisitCount() throws IOException, InterruptedException {
        var seed = h.seedMinimal();
        String visitUrl = "/api/actresses/" + seed.actressId() + "/visit";

        HttpResponse<String> r1 = post(visitUrl);
        assertEquals(200, r1.statusCode());
        assertEquals(1, mapper.readTree(r1.body()).get("visitCount").asInt());

        HttpResponse<String> r2 = post(visitUrl);
        assertEquals(2, mapper.readTree(r2.body()).get("visitCount").asInt());
    }

    @Test
    void titleVisitPostIncrementsVisitCount() throws IOException, InterruptedException {
        var seed = h.seedMinimal();
        String visitUrl = "/api/titles/" + seed.titleCode() + "/visit";

        HttpResponse<String> r1 = post(visitUrl);
        assertEquals(200, r1.statusCode());
        assertEquals(1, mapper.readTree(r1.body()).get("visitCount").asInt());
    }

    @Test
    void favoriteToggleFlowRoundTrips() throws IOException, InterruptedException {
        var seed = h.seedMinimal();
        String favUrl = "/api/actresses/" + seed.actressId() + "/favorite";

        HttpResponse<String> on = post(favUrl);
        assertEquals(200, on.statusCode());
        assertTrue(mapper.readTree(on.body()).get("favorite").asBoolean());

        HttpResponse<String> off = post(favUrl);
        assertFalse(mapper.readTree(off.body()).get("favorite").asBoolean());
    }

    @Test
    void inlineBuilderFixtureSeedsTwoActressesWithCoStarTitle() throws IOException, InterruptedException {
        // Demonstrates per-test composition via FixtureBuilder — no seedMinimal/seedRich needed
        // when a test wants its own specific shape. Cheaper than extending the canned fixtures
        // for one-off scenarios.
        var f = h.fixture()
                .label("ABP", "Prestige")
                .actress("star1", a -> a.canonical("Hikaru Konno").tier(Actress.Tier.SUPERSTAR))
                .actress("star2", a -> a.canonical("Rika Aimi").tier(Actress.Tier.POPULAR))
                .title("duo", t -> t.code("ABP-500").label("ABP").seqNum(500).actress("star1"))
                .coStar("duo", "star2")
                .location("duo", "vol-a", "stars", "/stars/ABP-500", LocalDate.of(2024, 5, 1))
                .build();

        JsonNode body = mapper.readTree(get("/api/titles").body());
        assertEquals(1, body.size());
        assertEquals("ABP-500", body.get(0).get("code").asText());
        assertEquals(2, body.get(0).get("actresses").size(), "co-star count from builder");
        assertNotEquals(0L, f.actressId("star1"));
    }

    @Test
    void emptyDatabaseFavoritesReturnsEmptyArray() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/api/titles?favorites=true");
        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
