package com.organizer3.web;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * Fetches image bytes from user-supplied URLs with a conservative set of guardrails:
 *
 * <ul>
 *   <li>Scheme allowlist — only {@code http} and {@code https}.</li>
 *   <li>Size cap — stops reading after {@link #MAX_BYTES} bytes and raises an error.</li>
 *   <li>Timeouts — separate connect and read timeouts.</li>
 *   <li>Content-Type check — the response must declare {@code image/*}.</li>
 *   <li>Redirect handling — follows at most {@link #MAX_REDIRECTS} hops; each hop
 *       re-validates the scheme before continuing.</li>
 *   <li>DMM hotlink protection — sends a {@code Referer} header for DMM image hosts.</li>
 * </ul>
 *
 * <p>No strict host allowlist is enforced; the tool is single-user and image CDNs churn.
 */
@Slf4j
public class ImageFetcher {

    public static final int MAX_BYTES = 20 * 1024 * 1024;        // 20 MB
    public static final int MAX_REDIRECTS = 5;
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration READ_TIMEOUT    = Duration.ofSeconds(30);

    /** One fetched image: bytes + the extension implied by the Content-Type. */
    public record Fetched(byte[] bytes, String extension) {}

    /** Thrown for any failure reason; exposes a concise user-facing message. */
    public static class ImageFetchException extends IOException {
        public ImageFetchException(String message) { super(message); }
    }

    /**
     * Fetch the image at {@code urlStr} and return its bytes and file extension.
     *
     * @throws ImageFetchException on any validation or transport error.
     */
    public Fetched fetch(String urlStr) throws ImageFetchException {
        String current = urlStr;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = validate(current);
            try {
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
                conn.setReadTimeout((int) READ_TIMEOUT.toMillis());
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "organizer3-title-editor/1.0");
                conn.setRequestProperty("Referer", pickReferer(url));

                int status = conn.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.isBlank()) {
                        throw new ImageFetchException("Redirect without Location header");
                    }
                    current = URI.create(url.toString()).resolve(location).toString();
                    continue;
                }
                if (status != 200) {
                    conn.disconnect();
                    throw new ImageFetchException("Unexpected HTTP status: " + status);
                }

                String contentType = conn.getContentType();
                String ext = extensionFor(contentType);
                if (ext == null) {
                    conn.disconnect();
                    throw new ImageFetchException("Content-Type is not an image: " + contentType);
                }

                try (InputStream in = conn.getInputStream()) {
                    byte[] bytes = readCapped(in);
                    return new Fetched(bytes, ext);
                } finally {
                    conn.disconnect();
                }
            } catch (ImageFetchException e) {
                throw e;
            } catch (IOException e) {
                throw new ImageFetchException("Fetch failed: " + e.getMessage());
            }
        }
        throw new ImageFetchException("Too many redirects (> " + MAX_REDIRECTS + ")");
    }

    private static URL validate(String urlStr) throws ImageFetchException {
        URI uri;
        try {
            uri = URI.create(urlStr);
        } catch (IllegalArgumentException e) {
            throw new ImageFetchException("Invalid URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ImageFetchException("Only http/https URLs are allowed");
        }
        try {
            return uri.toURL();
        } catch (Exception e) {
            throw new ImageFetchException("Invalid URL: " + e.getMessage());
        }
    }

    private static String pickReferer(URL url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        if (host.contains("dmm.co.jp") || host.contains("dmm.com") || host.contains("awsimgsrc.dmm")) {
            return "https://www.dmm.co.jp/";
        }
        return url.getProtocol() + "://" + url.getHost() + "/";
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase();
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi).trim();
        if (!ct.startsWith("image/")) return null;
        String sub = ct.substring("image/".length());
        return switch (sub) {
            case "jpeg", "jpg" -> "jpg";
            case "png"         -> "png";
            case "webp"        -> "webp";
            case "gif"         -> "gif";
            default            -> null;  // reject exotic formats (avif, heic, svg) — not supported by CoverPath probe order
        };
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > MAX_BYTES) {
                throw new ImageFetchException("Image exceeds " + MAX_BYTES + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
