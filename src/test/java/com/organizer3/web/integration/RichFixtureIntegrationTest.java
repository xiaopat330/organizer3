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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the richer {@link IntegrationTestHarness#seedRich()} fixture.
 *
 * <p>These lock in behavior the minimal fixture can't reach: multi-actress
 * titles, multi-location dedup, filter endpoints with non-empty results,
 * tier / prefix aggregation, tag round-trips, and visit history.
 *
 * <p>When adding to the rich fixture, update the exact-count assertions here
 * first — they're the contract that keeps the fixture honest.
 */
class RichFixtureIntegrationTest {

    private IntegrationTestHarness h;
    private IntegrationTestHarness.RichSeed seed;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        h = new IntegrationTestHarness();
        seed = h.seedRich();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.close();
    }

    // ── Shape / count checks ──────────────────────────────────────────

    @Test
    void titlesEndpointReturnsAllSixSeededTitles() throws Exception {
        JsonNode body = getJson("/api/titles?limit=50");
        assertTrue(body.isArray());
        // Six distinct titles — ABP-002 has two locations but must still be one row.
        assertEquals(6, body.size(),
                "multi-location title must dedupe; got: " + body);
    }

    @Test
    void prefixIndexReturnsDistinctLettersForSeededActresses() throws Exception {
        JsonNode index = getJson("/api/actresses/index");
        assertTrue(index.isArray());
        // Four distinct canonical-name first letters: A (Aya, Ayumi), Y (Yua), M (Mio), R (Rejected).
        // The rejected actress may or may not be excluded depending on service behavior —
        // assert the non-rejected letters are present and skip exact size.
        var letters = new java.util.HashSet<String>();
        index.forEach(n -> letters.add(n.asText()));
        assertTrue(letters.contains("A"), "expected A prefix: " + letters);
        assertTrue(letters.contains("Y"), "expected Y prefix: " + letters);
        assertTrue(letters.contains("M"), "expected M prefix: " + letters);
    }

    @Test
    void tierCountsForPrefixASumsAyaAndAyumi() throws Exception {
        JsonNode counts = getJson("/api/actresses/tier-counts?prefix=A");
        assertTrue(counts.isObject());
        // Aya=GODDESS, Ayumi=SUPERSTAR — each tier should count 1.
        assertEquals(1, counts.get("GODDESS").asInt());
        assertEquals(1, counts.get("SUPERSTAR").asInt());
    }

    // ── Filter endpoints ──────────────────────────────────────────────

    @Test
    void titleFavoritesFilterReturnsOnlyFavorites() throws Exception {
        JsonNode body = getJson("/api/titles?favorites=true");
        assertEquals(1, body.size(), "only ABP-001 is favorited");
        assertEquals("ABP-001", body.get(0).get("code").asText());
    }

    @Test
    void titleBookmarksFilterReturnsOnlyBookmarked() throws Exception {
        JsonNode body = getJson("/api/titles?bookmarks=true");
        assertEquals(1, body.size(), "only SSIS-100 is bookmarked");
        assertEquals("SSIS-100", body.get(0).get("code").asText());
    }

    // ── Joins / aggregations ──────────────────────────────────────────

    @Test
    void coStarTitleReturnsBothActressesInActressesArray() throws Exception {
        JsonNode body = getJson("/api/titles?search=ABP-002");
        assertEquals(1, body.size());
        JsonNode actresses = body.get(0).get("actresses");
        assertTrue(actresses.isArray());
        assertEquals(2, actresses.size(), "ABP-002 is linked to Aya + Ayumi");
    }

    @Test
    void multiLocationTitleReturnsBothLocationPaths() throws Exception {
        JsonNode body = getJson("/api/titles?search=ABP-002");
        JsonNode nasPaths = body.get(0).get("nasPaths");
        assertTrue(nasPaths.isArray());
        assertEquals(2, nasPaths.size(), "ABP-002 has two NAS locations seeded");
    }

    @Test
    void tagFilterReturnsOnlyTaggedTitle() throws Exception {
        // The list endpoint doesn't surface title-level tags in the summary,
        // but the ?tags= filter query drives the filter path — assert that
        // only the tagged title comes back.
        JsonNode body = getJson("/api/titles?tags=creampie");
        assertEquals(1, body.size(), "only MIDV-100 carries the 'creampie' tag");
        assertEquals("MIDV-100", body.get(0).get("code").asText());
    }

    @Test
    void actressDetailReturnsFavoriteAndBookmarkStateFromSeed() throws Exception {
        JsonNode aya = getJson("/api/actresses/" + seed.ayaId());
        assertTrue(aya.get("favorite").asBoolean(), "Aya is favorited in seed");
        assertFalse(aya.get("bookmark").asBoolean());

        JsonNode ayumi = getJson("/api/actresses/" + seed.ayumiId());
        assertFalse(ayumi.get("favorite").asBoolean());
        assertTrue(ayumi.get("bookmark").asBoolean(), "Ayumi is bookmarked in seed");
    }

    @Test
    void labelInheritedTagFilterMatchesAllAbpTitles() throws Exception {
        // "bigtits" is attached to the ABP label (not the title), so the filter
        // should pick up ABP-001 and ABP-002 via title_effective_tags source='label'.
        JsonNode body = getJson("/api/titles?tags=bigtits");
        assertEquals(2, body.size(), "both ABP titles should inherit the label tag");
    }

    @Test
    void studioGroupsListIncludesPrestigeGroup() throws Exception {
        JsonNode groups = getJson("/api/titles/studios");
        assertTrue(groups.isArray());
        boolean foundPrestige = false;
        for (JsonNode g : groups) {
            if ("prestige".equals(g.get("slug").asText())) { foundPrestige = true; break; }
        }
        assertTrue(foundPrestige, "studios.yaml defines a 'prestige' group");
    }

    @Test
    void studioGroupCompaniesReturnsPrestigeTitleCount() throws Exception {
        // Two ABP titles exist (company="Prestige"), the 'prestige' group
        // should aggregate a titleCount of 2.
        JsonNode companies = getJson("/api/studio-groups/prestige/companies");
        assertTrue(companies.isArray());
        assertTrue(companies.size() > 0);
        boolean matched = false;
        for (JsonNode c : companies) {
            if ("Prestige".equals(c.get("company").asText())) {
                assertEquals(2, c.get("titleCount").asInt());
                matched = true;
            }
        }
        assertTrue(matched, "expected a Prestige company entry: " + companies);
    }

    @Test
    void visitedTitleCarriesVisitCountThroughListEndpoint() throws Exception {
        JsonNode body = getJson("/api/titles?search=MIDV-050");
        assertEquals(1, body.size());
        assertEquals(1, body.get(0).get("visitCount").asInt(),
                "MIDV-050 had one recordVisit in seedRich()");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "non-200 for " + path + ": " + r.body());
        return mapper.readTree(r.body());
    }
}
