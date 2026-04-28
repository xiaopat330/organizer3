package com.organizer3.javdb.enrichment;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Downloads actress avatar images from the public CDN and stores them locally
 * under {@code <dataDir>/actress-avatars/{slug}.{ext}}.
 *
 * <p>Idempotent: a download is skipped if the target file already exists. Failures
 * are logged and the call returns null — the caller should treat this as
 * "no local avatar" rather than fail the surrounding enrichment job.
 *
 * <p>See {@code spec/PROPOSAL_ACTRESS_AVATARS.md}.
 */
@Slf4j
public class ActressAvatarStore {

    private static final String SUBDIR = "actress-avatars";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    private final Path dataDir;
    private final HttpClient http;

    public ActressAvatarStore(Path dataDir, HttpClient http) {
        this.dataDir = dataDir;
        this.http = http;
    }

    public ActressAvatarStore(Path dataDir) {
        this(dataDir, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /**
     * Downloads {@code avatarUrl} and writes it to {@code actress-avatars/{slug}.{ext}}.
     * Returns the path relative to {@code dataDir}, or null if the download failed.
     * If a file already exists at the target, returns its relative path without re-downloading.
     */
    public String download(String slug, String avatarUrl) {
        if (slug == null || slug.isBlank() || avatarUrl == null || avatarUrl.isBlank()) {
            return null;
        }
        try {
            // Fast path: if any file matching {slug}.* already exists, reuse it.
            Path dir = dataDir.resolve(SUBDIR);
            Files.createDirectories(dir);
            String existing = findExisting(dir, slug);
            if (existing != null) return SUBDIR + "/" + existing;

            HttpRequest req = HttpRequest.newBuilder(URI.create(avatarUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://javdb.com/")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                log.warn("avatar download failed for slug={} url={} status={}", slug, avatarUrl, resp.statusCode());
                return null;
            }
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            String ext = extensionFor(contentType, avatarUrl);
            String filename = slug + "." + ext;
            Path target = dir.resolve(filename);

            // Write to a tmp file then atomically move to avoid leaving a partial file
            // if the process is interrupted mid-write.
            Path tmp = dir.resolve(filename + ".tmp");
            Files.write(tmp, resp.body());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return SUBDIR + "/" + filename;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("avatar download error for slug={} url={}: {}", slug, avatarUrl, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("avatar download unexpected error for slug={} url={}: {}", slug, avatarUrl, e.getMessage());
            return null;
        }
    }

    private static String findExisting(Path dir, String slug) {
        try {
            try (var stream = Files.list(dir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith(slug + ".") && !n.endsWith(".tmp"))
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Picks an image extension from the response Content-Type, falling back to the URL path. */
    static String extensionFor(String contentType, String url) {
        if (contentType != null) {
            String t = contentType.toLowerCase();
            if (t.contains("image/jpeg") || t.contains("image/jpg")) return "jpg";
            if (t.contains("image/png"))  return "png";
            if (t.contains("image/webp")) return "webp";
            if (t.contains("image/gif"))  return "gif";
        }
        if (url != null) {
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase();
                if (ext.equals("jpeg")) return "jpg";
                if (ext.matches("[a-z0-9]{2,5}")) return ext;
            }
        }
        return "jpg";
    }
}
