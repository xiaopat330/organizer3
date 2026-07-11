package com.organizer3.web.routes;

import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Endpoint test for {@link SmbRoutes}: {@code POST /api/smb/reset} must invoke
 * {@link SmbConnectionFactory#invalidateAll()} and return {@code {"reset":true}}.
 * Uses a real Javalin on port 0 with a mocked factory (mirrors {@link JavdbDiscoveryRoutesTest}).
 */
class SmbRoutesTest {

    private WebServer server;
    private SmbConnectionFactory factory;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        factory = mock(SmbConnectionFactory.class);
        server = new WebServer(0);
        server.registerSmb(new SmbRoutes(factory));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void postReset_invokesInvalidateAll_andReturnsResetTrue() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/api/smb/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"reset\":true"), "body was: " + resp.body());
        verify(factory, times(1)).invalidateAll();
    }
}
