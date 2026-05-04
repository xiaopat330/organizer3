package com.organizer3.web.routes;

import com.organizer3.avstars.web.AvBrowseService;
import com.organizer3.utilities.avstars.AvStarsCatalogService;
import com.organizer3.utilities.avstars.IafdResolverService;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Placeholder test for {@link AvStarsRoutes}.
 * Currently covers the bad-id path on the write endpoint; additional coverage deferred.
 */
class AvStarsRoutesTest {

    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        AvStarsCatalogService catalog = mock(AvStarsCatalogService.class);
        AvBrowseService browse = mock(AvBrowseService.class);
        IafdResolverService resolver = mock(IafdResolverService.class);

        server = new WebServer(0);
        server.registerAvStars(new AvStarsRoutes(catalog, browse, resolver));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── /api/utilities/avstars/actresses/{id}/iafd/search ─────────────────────

    @Test
    void iafdSearch_400OnNonNumericId() throws Exception {
        var resp = postJson("/api/utilities/avstars/actresses/abc/iafd/search", "{}");
        assertEquals(400, resp.statusCode());
    }
}
