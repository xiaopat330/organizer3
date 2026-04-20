package com.organizer3.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Contract tests: lock down the JSON field names the frontend relies on.
 *
 * <p>These guard against silent breakage when a service rename or refactor
 * changes a response field's name or type. Uses the full
 * {@link IntegrationTestHarness} (real serialization) so renames surface
 * here before they hit the browser.
 */
class ApiContractTest {

    private IntegrationTestHarness h;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        h = new IntegrationTestHarness();
        h.seedMinimal();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.close();
    }

    // ── Title response contract ───────────────────────────────────────

    @Test
    void titleSummaryHasRequiredFieldsForFrontend() throws IOException, InterruptedException {
        JsonNode title = mapper.readTree(get("/api/titles").body()).get(0);

        // Fields consumed by cards.js / title-detail.js / search.js
        requireFields(title, List.of(
                "code", "baseCode", "label",
                "actressId", "actressName", "actressTier",
                "addedDate", "coverUrl",
                "locations", "nasPaths", "locationEntries",
                "actresses", "tags",
                "favorite", "bookmark",
                "visitCount"
        ));
    }

    @Test
    void titleLocationsIsArray() throws IOException, InterruptedException {
        JsonNode title = mapper.readTree(get("/api/titles").body()).get(0);
        assertTrue(title.get("locations").isArray(), "locations must be an array");
        assertTrue(title.get("nasPaths").isArray(), "nasPaths must be an array");
        assertTrue(title.get("actresses").isArray(), "actresses must be an array");
        assertTrue(title.get("tags").isArray(), "tags must be an array");
    }

    @Test
    void titleFavoriteAndBookmarkAreBooleans() throws IOException, InterruptedException {
        JsonNode title = mapper.readTree(get("/api/titles").body()).get(0);
        assertTrue(title.get("favorite").isBoolean());
        assertTrue(title.get("bookmark").isBoolean());
    }

    // ── Actress response contract ─────────────────────────────────────

    @Test
    void actressSummaryHasRequiredFieldsForFrontend() throws IOException, InterruptedException {
        JsonNode actress = mapper.readTree(get("/api/actresses/1").body());

        requireFields(actress, List.of(
                "id", "canonicalName", "tier",
                "titleCount",
                "favorite", "bookmark", "rejected",
                "coverUrls", "folderPaths"
        ));
    }

    @Test
    void actressArrayShapesAreStable() throws IOException, InterruptedException {
        JsonNode actress = mapper.readTree(get("/api/actresses/1").body());
        assertTrue(actress.get("coverUrls").isArray());
        assertTrue(actress.get("folderPaths").isArray());
    }

    @Test
    void actressFlagStateResponseHasIdFavoriteBookmarkRejected() throws IOException, InterruptedException {
        JsonNode state = mapper.readTree(post("/api/actresses/1/favorite").body());

        // These four exact field names are consumed by the bookmark/favorite
        // card-update logic in cards.js.
        requireFields(state, List.of("id", "favorite", "bookmark", "rejected"));
    }

    @Test
    void visitStatsResponseHasVisitCountAndLastVisitedAt() throws IOException, InterruptedException {
        JsonNode stats = mapper.readTree(post("/api/actresses/1/visit").body());

        requireFields(stats, List.of("visitCount", "lastVisitedAt"));
        assertTrue(stats.get("visitCount").isNumber());
    }

    // ── Prefix index / tier counts ────────────────────────────────────

    @Test
    void actressesIndexReturnsArrayOfStrings() throws IOException, InterruptedException {
        JsonNode index = mapper.readTree(get("/api/actresses/index").body());
        assertTrue(index.isArray(), "index must be an array of single-letter strings");
    }

    @Test
    void tierCountsReturnsTierNameToIntegerMap() throws IOException, InterruptedException {
        JsonNode counts = mapper.readTree(get("/api/actresses/tier-counts?prefix=A").body());
        assertTrue(counts.isObject(), "tier-counts must be an object keyed by tier name");
        if (counts.fields().hasNext()) {
            var entry = counts.fields().next();
            assertTrue(entry.getValue().isNumber(), "each tier count value must be a number");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void requireFields(JsonNode node, List<String> fields) {
        for (String field : fields) {
            assertTrue(node.has(field),
                    "Missing field '" + field + "' in response: " + node);
        }
    }

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
