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
 * Contract tests run against the {@link IntegrationTestHarness#seedRich()}
 * fixture. Where {@link ApiContractTest} verifies field *presence* on minimal
 * single-row payloads, this suite exercises the shape of *populated* nested
 * arrays (e.g. an {@code actresses[]} element's fields) — something a single
 * actress/title dataset can't reach.
 */
class ApiContractRichTest {

    private IntegrationTestHarness h;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        h = new IntegrationTestHarness();
        h.seedRich();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.close();
    }

    // ── Populated nested arrays ───────────────────────────────────────

    @Test
    void titleActressesElementHasIdNameTierFields() throws Exception {
        // ABP-002 is the co-star title — actresses[] has two entries to inspect.
        JsonNode t = getJson("/api/titles?search=ABP-002").get(0);
        JsonNode actresses = t.get("actresses");
        assertTrue(actresses.isArray() && actresses.size() == 2);

        JsonNode first = actresses.get(0);
        // Nested actress elements use short "name", not "canonicalName" (unlike /api/actresses/{id}).
        requireFields(first, List.of("id", "name", "tier"));
        assertTrue(first.get("id").isNumber());
    }

    @Test
    void titleLocationEntriesElementHasVolumeAndPathFields() throws Exception {
        JsonNode t = getJson("/api/titles?search=ABP-002").get(0);
        JsonNode entries = t.get("locationEntries");
        assertTrue(entries.isArray() && entries.size() >= 2,
                "ABP-002 has two seeded locations");
        JsonNode first = entries.get(0);
        requireFields(first, List.of("volumeId", "nasPath"));
    }

    @Test
    void titleNasPathsElementsAreAbsoluteSmbStrings() throws Exception {
        JsonNode t = getJson("/api/titles?search=ABP-002").get(0);
        JsonNode nasPaths = t.get("nasPaths");
        assertTrue(nasPaths.isArray() && nasPaths.size() == 2);
        for (JsonNode p : nasPaths) {
            assertTrue(p.isTextual(), "nasPaths element must be a string");
            assertTrue(p.asText().startsWith("//"),
                    "nasPaths element should be SMB-style, got: " + p.asText());
        }
    }

    // ── Tier-counts value shapes ──────────────────────────────────────

    @Test
    void tierCountsValuesAreAllIntegers() throws Exception {
        JsonNode counts = getJson("/api/actresses/tier-counts?prefix=A");
        assertTrue(counts.isObject() && counts.size() > 0);
        counts.fields().forEachRemaining(e ->
                assertTrue(e.getValue().isNumber(),
                        "tier-counts value for '" + e.getKey() + "' must be numeric"));
    }

    // ── Studio group payload shape ────────────────────────────────────

    @Test
    void studioGroupsElementHasNameSlugCompaniesFields() throws Exception {
        JsonNode groups = getJson("/api/titles/studios");
        assertTrue(groups.isArray() && groups.size() > 0);
        JsonNode first = groups.get(0);
        requireFields(first, List.of("name", "slug", "companies"));
        assertTrue(first.get("companies").isArray());
    }

    @Test
    void studioGroupCompanyCountElementHasCompanyAndTitleCountFields() throws Exception {
        JsonNode companies = getJson("/api/studio-groups/prestige/companies");
        assertTrue(companies.isArray() && companies.size() > 0);
        JsonNode first = companies.get(0);
        requireFields(first, List.of("company", "titleCount"));
        assertTrue(first.get("titleCount").isNumber());
    }

    // ── Prefix index element type ─────────────────────────────────────

    @Test
    void prefixIndexElementsAreSingleCharacterStrings() throws Exception {
        JsonNode index = getJson("/api/actresses/index");
        assertTrue(index.isArray() && index.size() > 0);
        for (JsonNode n : index) {
            assertTrue(n.isTextual());
            assertEquals(1, n.asText().length(),
                    "prefix index entries must be single letters, got: " + n.asText());
        }
    }

    // ── Actress detail — populated folder paths + cover urls ──────────

    @Test
    void actressFolderPathsElementsAreStrings() throws Exception {
        // Aya is the first actress saved by seedRich() → id=1.
        JsonNode aya = getJson("/api/actresses/1");
        JsonNode paths = aya.get("folderPaths");
        assertTrue(paths.isArray());
        // May be empty if path resolution skips unknown volumes; if populated,
        // every element must be a string.
        for (JsonNode p : paths) assertTrue(p.isTextual());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void requireFields(JsonNode node, List<String> fields) {
        for (String f : fields) {
            assertTrue(node.has(f), "Missing '" + f + "' in: " + node);
        }
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "non-200 for " + path + ": " + r.body());
        return mapper.readTree(r.body());
    }
}
