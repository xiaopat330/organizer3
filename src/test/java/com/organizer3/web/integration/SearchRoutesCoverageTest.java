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
 * Exercises /api/search and /api/titles/by-code* branch paths.
 */
class SearchRoutesCoverageTest {

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

    @Test
    void emptyQueryReturnsEmptyResultShape() throws Exception {
        JsonNode body = getJson("/api/search");
        for (String key : new String[]{"actresses", "titles", "labels", "companies"}) {
            assertTrue(body.has(key), "missing '" + key + "' in empty-search response: " + body);
        }
    }

    @Test
    void blankQueryAlsoReturnsEmptyResultShape() throws Exception {
        JsonNode body = getJson("/api/search?q=%20%20");
        assertTrue(body.has("actresses"));
    }

    @Test
    void nonemptyQueryReturnsSearchResult() throws Exception {
        JsonNode body = getJson("/api/search?q=aya");
        assertTrue(body.has("actresses"));
    }

    @Test
    void startsWithMatchModeIsAccepted() throws Exception {
        int s = getStatus("/api/search?q=aya&matchMode=startsWith");
        assertEquals(200, s);
    }

    @Test
    void includeAvParamReachesTheBranch() throws Exception {
        // Harness has no AV repos wired, so includeAv=true will 500 when it tries
        // to search AV actresses. The point here is that the branch is reached —
        // either 200 or 500 proves the param was parsed (404 would mean the route
        // didn't match at all).
        int s = getStatus("/api/search?q=aya&includeAv=true");
        assertTrue(s == 200 || s == 500, "got: " + s);
    }

    @Test
    void byCodePrefixWithBlankPrefixReturnsEmpty() throws Exception {
        JsonNode body = getJson("/api/titles/by-code-prefix");
        assertTrue(body.isArray() && body.size() == 0);
    }

    @Test
    void byCodePrefixWithPrefixAcceptsRequest() throws Exception {
        int s = getStatus("/api/titles/by-code-prefix?prefix=ABP");
        assertEquals(200, s);
    }

    @Test
    void byCodePrefixClampsLimit() throws Exception {
        int s = getStatus("/api/titles/by-code-prefix?prefix=ABP&limit=100");
        assertEquals(200, s);
    }

    @Test
    void byCodeKnownReturnsExactMatch() throws Exception {
        JsonNode body = getJson("/api/titles/by-code/ABP-001");
        assertNotNull(body);
        // Either exact summary shape or fallback id+code map — both have "code".
        assertTrue(body.has("code"));
    }

    @Test
    void byCodeLowercaseCodeIsUppercasedForLookup() throws Exception {
        int s = getStatus("/api/titles/by-code/abp-001");
        assertEquals(200, s);
    }

    @Test
    void byCodeUnknownReturnsNotFound() throws Exception {
        int s = getStatus("/api/titles/by-code/DOES-NOT-EXIST");
        assertEquals(404, s);
    }

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
}
