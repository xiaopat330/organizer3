package com.organizer3.avstars.iafd;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production {@link IafdClient} backed by {@link java.net.http.HttpClient}.
 *
 * <p>Mimics a real browser User-Agent to avoid 403s. Does not follow redirects
 * beyond the default (JDK follows up to 5 redirects automatically with NORMAL policy).
 */
public class HttpIafdClient implements IafdClient {

    private static final String BASE_URL = "https://www.iafd.com";

    // Matches the UUID in a profile redirect URL: /person.rme/id=<uuid>
    private static final Pattern PROFILE_UUID =
            Pattern.compile("/person\\.rme/id=([0-9a-f\\-]{36})", Pattern.CASE_INSENSITIVE);

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
        String searchUrl = BASE_URL + "/ramesearch.asp?searchtype=comprehensive&searchstring=" + encoded;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IafdFetchException("HTTP " + resp.statusCode() + " for " + searchUrl);
            }

            // IAFD redirects directly to the profile page when there is a single match.
            // Detect this by checking whether the final URL is a person.rme profile URL.
            String finalUrl = resp.uri().toString();
            Matcher m = PROFILE_UUID.matcher(finalUrl);
            if (m.find()) {
                // We already have the profile HTML — synthesize a one-row search results
                // table so the IafdSearchParser can return a normal single-result list.
                String uuid = m.group(1);
                String perfName = extractTitleName(resp.body(), name);
                return buildSyntheticSearchHtml(uuid, perfName);
            }

            return resp.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IafdFetchException("Failed to fetch " + searchUrl, e);
        }
    }

    /** Extracts the performer name from the HTML {@code <title>} tag (format: "Name - …"). */
    private static String extractTitleName(String html, String fallback) {
        int start = html.indexOf("<title>");
        if (start < 0) return fallback;
        int end = html.indexOf("</title>", start);
        if (end < 0) return fallback;
        String title = html.substring(start + 7, end).trim();
        int dash = title.lastIndexOf(" - ");
        return dash > 0 ? title.substring(0, dash).trim() : fallback;
    }

    /** Builds minimal search-results HTML that {@link IafdSearchParser} can parse. */
    private static String buildSyntheticSearchHtml(String uuid, String name) {
        String safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><table id='tblFeatured'><tbody><tr>"
                + "<td><img src='' /></td>"
                + "<td><a href='/person.rme/id=" + uuid + "'>" + safeName + "</a></td>"
                + "<td></td><td></td><td></td><td></td>"
                + "</tr></tbody></table></html>";
    }

    @Override
    public String fetchProfile(String iafdId) {
        String url = BASE_URL + "/person.rme/id=" + iafdId;
        return fetchText(url);
    }

    @Override
    public byte[] fetchBytes(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(toUri(url))
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

    /**
     * Converts a raw URL string to a {@link URI}, percent-encoding any illegal characters
     * (e.g. spaces) in the path component. Parses scheme/authority/path/query manually
     * so the multi-arg {@link URI} constructor can handle encoding without deprecated APIs.
     */
    private static URI toUri(String rawUrl) {
        try {
            int schemeEnd = rawUrl.indexOf("://");
            if (schemeEnd < 0) return URI.create(rawUrl.replace(" ", "%20"));
            String scheme = rawUrl.substring(0, schemeEnd);
            String rest   = rawUrl.substring(schemeEnd + 3);
            int pathStart = rest.indexOf('/');
            if (pathStart < 0) return URI.create(rawUrl.replace(" ", "%20"));
            String authority   = rest.substring(0, pathStart);
            String pathAndQuery = rest.substring(pathStart);
            int qMark = pathAndQuery.indexOf('?');
            String path  = qMark < 0 ? pathAndQuery : pathAndQuery.substring(0, qMark);
            String query = qMark < 0 ? null : pathAndQuery.substring(qMark + 1);
            return new URI(scheme, authority, path, query, null);
        } catch (Exception e) {
            return URI.create(rawUrl.replace(" ", "%20"));
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
