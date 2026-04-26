package com.organizer3.javdb;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production {@link JavdbClient} backed by {@link java.net.http.HttpClient}.
 *
 * <p>Sends a browser User-Agent and the {@code age_check_done=1} cookie so detail
 * pages are served without an age-gate redirect.
 *
 * <p>A simple minimum-gap rate limiter enforces at most {@code ratePerSec} requests
 * per second across all callers (the runner and any ad-hoc UI lookups share this).
 *
 * <p>Throws {@link JavdbRateLimitException} on HTTP 429 and
 * {@link JavdbNotFoundException} on HTTP 404 so callers can handle them distinctly.
 */
public class HttpJavdbClient implements JavdbClient {

    private static final String BASE_URL = "https://javdb.com";

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient http;
    private final String userAgent;

    // Minimum-gap rate limiter: tracks the earliest nanoTime at which the next request is allowed.
    private final AtomicLong nextAllowedNanos = new AtomicLong(0);
    private final long intervalNanos;

    public HttpJavdbClient(JavdbConfig config) {
        this(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            config
        );
    }

    HttpJavdbClient(HttpClient http, JavdbConfig config) {
        this.http = http;
        this.userAgent = config.userAgent() != null ? config.userAgent() : DEFAULT_USER_AGENT;
        double ratePerSec = config.rateLimitPerSecOrDefault();
        this.intervalNanos = (long) (1_000_000_000.0 / ratePerSec);
    }

    /** For tests only — no rate limiting, default UA. */
    HttpJavdbClient(HttpClient http) {
        this.http = http;
        this.userAgent = DEFAULT_USER_AGENT;
        this.intervalNanos = 0;
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

    private void acquireRateLimit() {
        if (intervalNanos <= 0) return;
        long now = System.nanoTime();
        long next;
        while (true) {
            next = nextAllowedNanos.get();
            long delay = next - now;
            if (delay <= 0) {
                // Slot is free — try to claim it
                if (nextAllowedNanos.compareAndSet(next, now + intervalNanos)) return;
                // Another thread beat us; re-read
                now = System.nanoTime();
            } else {
                try { Thread.sleep(delay / 1_000_000, (int)(delay % 1_000_000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                now = System.nanoTime();
            }
        }
    }

    private String fetch(String url) {
        acquireRateLimit();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .header("Cookie", "age_check_done=1; locale=en")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status == 429) throw new JavdbRateLimitException(url);
            if (status == 404) throw new JavdbNotFoundException(url);
            if (status < 200 || status >= 300) throw new JavdbFetchException("HTTP " + status + " fetching " + url);
            return resp.body();
        } catch (IOException e) {
            throw new JavdbFetchException("Failed to fetch " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JavdbFetchException("Interrupted fetching " + url, e);
        }
    }
}
