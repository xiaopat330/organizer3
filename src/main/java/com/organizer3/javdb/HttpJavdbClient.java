package com.organizer3.javdb;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Production {@link JavdbClient} backed by {@link java.net.http.HttpClient}.
 *
 * <p>Sends a browser User-Agent and the {@code age_check_done=1} cookie that javdb sets
 * after the age-gate click, so detail pages are served without a redirect to the gate.
 */
public class HttpJavdbClient implements JavdbClient {

    private static final String BASE_URL = "https://javdb.com";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient http;

    public HttpJavdbClient() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpJavdbClient(HttpClient http) {
        this.http = http;
    }

    @Override
    public String searchByCode(String code) {
        String encoded = URLEncoder.encode(code, StandardCharsets.UTF_8);
        return fetch(BASE_URL + "/search?q=" + encoded + "&f=all");
    }

    @Override
    public String fetchTitlePage(String slug) {
        return fetch(BASE_URL + "/v/" + slug);
    }

    @Override
    public String fetchActressPage(String slug) {
        return fetch(BASE_URL + "/actors/" + slug);
    }

    private String fetch(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .header("Cookie", "age_check_done=1; locale=en")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new JavdbFetchException("HTTP " + resp.statusCode() + " fetching " + url);
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new JavdbFetchException("Failed to fetch " + url, e);
        }
    }
}
