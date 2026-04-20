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
 * Exercises the {@code /api/actresses/*} query-param branches and
 * path-param validation paths that neither the contract tests nor the
 * rich-fixture tests hit.
 */
class ActressRoutesCoverageTest {

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

    // ── Query-param branch coverage on /api/actresses ─────────────────

    @Test
    void listByIdsReturnsOnlyRequestedActresses() throws Exception {
        String ids = seed.ayaId() + "," + seed.yuaId();
        JsonNode body = getJson("/api/actresses?ids=" + ids);
        assertTrue(body.isArray());
        assertEquals(2, body.size());
    }

    @Test
    void searchTooShortReturnsEmptyArray() throws Exception {
        JsonNode body = getJson("/api/actresses?search=a");
        assertTrue(body.isArray() && body.size() == 0,
                "single-char search must return empty");
    }

    @Test
    void searchByNameReturnsMatchingActress() throws Exception {
        JsonNode body = getJson("/api/actresses?search=aya");
        assertTrue(body.isArray() && body.size() >= 1);
    }

    @Test
    void listByPrefixReturnsActressesWithThatLetter() throws Exception {
        JsonNode body = getJson("/api/actresses?prefix=A");
        assertTrue(body.isArray() && body.size() >= 1);
    }

    @Test
    void listByTierReturnsOnlyThatTier() throws Exception {
        JsonNode body = getJson("/api/actresses?tier=GODDESS");
        assertTrue(body.isArray() && body.size() >= 1);
    }

    @Test
    void invalidTierReturnsBadRequest() throws Exception {
        int status = getStatus("/api/actresses?tier=NOT_A_TIER");
        assertEquals(400, status);
    }

    @Test
    void listAllTrueReturnsActresses() throws Exception {
        JsonNode body = getJson("/api/actresses?all=true");
        assertTrue(body.isArray() && body.size() >= 5);  // seedRich has 5
    }

    @Test
    void favoritesFilterReturnsFavoriteActresses() throws Exception {
        JsonNode body = getJson("/api/actresses?favorites=true");
        assertTrue(body.isArray());
        // Aya + Yua are favorited in seedRich
        assertEquals(2, body.size());
    }

    @Test
    void bookmarksFilterReturnsBookmarkedActresses() throws Exception {
        JsonNode body = getJson("/api/actresses?bookmarks=true");
        assertTrue(body.isArray());
        assertEquals(1, body.size());  // Ayumi is the only bookmarked actress
    }

    @Test
    void noQueryParamsReturnsBadRequest() throws Exception {
        int status = getStatus("/api/actresses");
        assertEquals(400, status);
    }

    // ── Path-param validation ─────────────────────────────────────────

    @Test
    void nonNumericIdReturnsBadRequest() throws Exception {
        assertEquals(400, getStatus("/api/actresses/notanumber"));
    }

    @Test
    void nonexistentIdReturnsNotFound() throws Exception {
        assertEquals(404, getStatus("/api/actresses/9999999"));
    }

    // ── tier-counts error path ────────────────────────────────────────

    @Test
    void tierCountsMissingPrefixReturnsBadRequest() throws Exception {
        assertEquals(400, getStatus("/api/actresses/tier-counts"));
    }

    // ── Spotlight endpoint ────────────────────────────────────────────

    @Test
    void spotlightReturnsOneActress() throws Exception {
        int status = getStatus("/api/actresses/spotlight");
        // 200 with a body OR 204 when nothing to spotlight — both are valid.
        assertTrue(status == 200 || status == 204, "got: " + status);
    }

    @Test
    void spotlightWithInvalidExcludeReturnsBadRequest() throws Exception {
        assertEquals(400, getStatus("/api/actresses/spotlight?exclude=notanumber"));
    }

    // ── /titles for an actress ────────────────────────────────────────

    @Test
    void actressTitlesReturnsArrayForKnownActress() throws Exception {
        JsonNode body = getJson("/api/actresses/" + seed.ayaId() + "/titles");
        assertTrue(body.isArray() && body.size() >= 1);  // Aya has ABP-001 and ABP-002
    }

    @Test
    void actressTitlesNonNumericIdReturnsBadRequest() throws Exception {
        assertEquals(400, getStatus("/api/actresses/xx/titles"));
    }

    @Test
    void actressTitlesWithTagsFilter() throws Exception {
        JsonNode body = getJson("/api/actresses/" + seed.ayaId() + "/titles?tags=bigtits");
        assertTrue(body.isArray());
    }

    // ── /tags for an actress ──────────────────────────────────────────

    @Test
    void actressTagsReturnsArray() throws Exception {
        JsonNode body = getJson("/api/actresses/" + seed.ayaId() + "/tags");
        assertTrue(body.isArray());
    }

    @Test
    void actressTagsNonNumericIdReturnsBadRequest() throws Exception {
        assertEquals(400, getStatus("/api/actresses/xx/tags"));
    }

    // ── POST endpoints: favorite/bookmark/reject/visit ────────────────

    @Test
    void postFavoriteNonNumericIdReturnsBadRequest() throws Exception {
        assertEquals(400, post("/api/actresses/xx/favorite").statusCode());
    }

    @Test
    void postFavoriteUnknownIdReturnsNotFound() throws Exception {
        assertEquals(404, post("/api/actresses/9999999/favorite").statusCode());
    }

    @Test
    void postBookmarkTogglesState() throws Exception {
        var r = post("/api/actresses/" + seed.ayaId() + "/bookmark");
        assertEquals(200, r.statusCode());
        JsonNode state = mapper.readTree(r.body());
        assertTrue(state.has("bookmark"));
    }

    @Test
    void postBookmarkWithExplicitValueSetsState() throws Exception {
        var r = post("/api/actresses/" + seed.ayaId() + "/bookmark?value=true");
        assertEquals(200, r.statusCode());
        assertTrue(mapper.readTree(r.body()).get("bookmark").asBoolean());
    }

    @Test
    void postRejectTogglesState() throws Exception {
        var r = post("/api/actresses/" + seed.ayaId() + "/reject");
        assertEquals(200, r.statusCode());
        assertTrue(mapper.readTree(r.body()).has("rejected"));
    }

    @Test
    void postRejectUnknownIdReturnsNotFound() throws Exception {
        assertEquals(404, post("/api/actresses/9999999/reject").statusCode());
    }

    @Test
    void postVisitUnknownIdReturnsNotFound() throws Exception {
        assertEquals(404, post("/api/actresses/9999999/visit").statusCode());
    }

    // ── PUT aliases ───────────────────────────────────────────────────

    @Test
    void putAliasesWithInvalidBodyReturnsBadRequest() throws Exception {
        var r = putRaw("/api/actresses/" + seed.ayaId() + "/aliases", "not json");
        assertEquals(400, r.statusCode());
    }

    @Test
    void putAliasesNonNumericIdReturnsBadRequest() throws Exception {
        var r = putRaw("/api/actresses/xx/aliases", "{\"aliases\":[]}");
        assertEquals(400, r.statusCode());
    }

    @Test
    void putAliasesSucceedsWithEmptyList() throws Exception {
        var r = putRaw("/api/actresses/" + seed.ayaId() + "/aliases", "{\"aliases\":[]}");
        assertEquals(200, r.statusCode());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        var r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "non-200 for " + path + ": " + r.body());
        return mapper.readTree(r.body());
    }

    private int getStatus(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putRaw(String path, String body) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(h.baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
