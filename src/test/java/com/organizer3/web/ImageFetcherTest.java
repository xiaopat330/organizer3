package com.organizer3.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class ImageFetcherTest {

    private HttpServer server;
    private int port;
    private final ImageFetcher fetcher = new ImageFetcher();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(ImageFetcher.ImageFetchException.class, () -> fetcher.fetch("file:///etc/passwd"));
        assertThrows(ImageFetcher.ImageFetchException.class, () -> fetcher.fetch("ftp://host/x.jpg"));
    }

    @Test
    void rejectsNonImageContentType() {
        handle("/html", exchange -> {
            byte[] body = "<html/>".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThrows(ImageFetcher.ImageFetchException.class, () -> fetcher.fetch(url("/html")));
    }

    @Test
    void acceptsJpegImage() throws Exception {
        byte[] body = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03};
        handle("/j", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ImageFetcher.Fetched fetched = fetcher.fetch(url("/j"));

        assertArrayEquals(body, fetched.bytes());
        assertEquals("jpg", fetched.extension());
    }

    @Test
    void acceptsPngAndWebpContentTypes() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        handle("/p", ex -> {
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });
        handle("/w", ex -> {
            ex.getResponseHeaders().set("Content-Type", "image/webp");
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });

        assertEquals("png",  fetcher.fetch(url("/p")).extension());
        assertEquals("webp", fetcher.fetch(url("/w")).extension());
    }

    @Test
    void followsRedirectsUpToLimit() throws Exception {
        byte[] body = new byte[]{0};
        handle("/one", ex -> {
            ex.getResponseHeaders().set("Location", url("/two"));
            ex.sendResponseHeaders(302, -1); ex.close();
        });
        handle("/two", ex -> {
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });

        assertEquals("jpg", fetcher.fetch(url("/one")).extension());
    }

    @Test
    void rejectsRedirectToNonHttpScheme() {
        handle("/redir", ex -> {
            ex.getResponseHeaders().set("Location", "file:///etc/passwd");
            ex.sendResponseHeaders(302, -1); ex.close();
        });

        assertThrows(ImageFetcher.ImageFetchException.class, () -> fetcher.fetch(url("/redir")));
    }

    @Test
    void rejectsExoticImageSubtype() {
        handle("/svg", ex -> {
            byte[] body = "<svg/>".getBytes();
            ex.getResponseHeaders().set("Content-Type", "image/svg+xml");
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });

        assertThrows(ImageFetcher.ImageFetchException.class, () -> fetcher.fetch(url("/svg")));
    }

    @Test
    void rejects4xxResponse() {
        handle("/missing", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });

        assertThrows(ImageFetcher.ImageFetchException.class, () -> fetcher.fetch(url("/missing")));
    }
}
