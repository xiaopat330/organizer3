package com.organizer3.avstars.iafd;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Production {@link IafdClient} backed by {@link java.net.http.HttpClient}.
 *
 * <p>Mimics a real browser User-Agent to avoid 403s. Does not follow redirects
 * beyond the default (JDK follows up to 5 redirects automatically with NORMAL policy).
 */
public class HttpIafdClient implements IafdClient {

    private static final String BASE_URL = "https://www.iafd.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient http;

    public HttpIafdClient() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** Package-private — allows tests to inject a custom client if needed. */
    HttpIafdClient(HttpClient http) {
        this.http = http;
    }

    @Override
    public String fetchSearch(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = BASE_URL + "/ramesearch.asp?searchtype=comprehensive&searchstring=" + encoded;
        return fetchText(url);
    }

    @Override
    public String fetchProfile(String iafdId) {
        String url = BASE_URL + "/person.rme/id=" + iafdId;
        return fetchText(url);
    }

    @Override
    public byte[] fetchBytes(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IafdFetchException("HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IafdFetchException("Failed to fetch " + url, e);
        }
    }

    private String fetchText(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IafdFetchException("HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IafdFetchException("Failed to fetch " + url, e);
        }
    }
}
