package com.organizer3.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {

    private WebServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
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
        assertTrue(response.body().contains("organizer3"),
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
}
