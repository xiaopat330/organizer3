package com.organizer3.organize;

import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes a title folder or an actress folder into the volume's {@code /attention/}
 * partition and writes a {@code REASON.txt} sidecar explaining why the item is there.
 *
 * <p>Per {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §4, the attention partition is the
 * human-intervention queue. The sidecar travels with the folder, so if the operator
 * relocates it via the NAS UI later, the context isn't lost.
 *
 * <p>Sidecar format (machine-readable header, blank line, human-readable body):
 * <pre>
 * reason: actress-letter-mismatch
 * volume: a
 * expected-letter: A
 * actual-actress: Nami Aino
 * moved-at: 2026-04-17T04:12:00Z
 *
 * Title was filed under ... (explanation)
 * </pre>
 */
@Slf4j
public class AttentionRouter {

    private static final Path ATTENTION_ROOT = Path.of("/attention");

    private final VolumeFileSystem fs;
    private final String volumeId;
    private final Clock clock;

    public AttentionRouter(VolumeFileSystem fs, String volumeId, Clock clock) {
        if (fs == null) throw new IllegalArgumentException("fs is required");
        if (volumeId == null || volumeId.isBlank()) throw new IllegalArgumentException("volumeId is required");
        this.fs = fs;
        this.volumeId = volumeId;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Moves {@code source} to {@code /attention/<source-basename>/} and drops a
     * {@code REASON.txt} sidecar inside. The source folder must exist; the target
     * must not.
     *
     * @param source       folder to route (path on this volume)
     * @param reasonCode   short identifier, e.g. {@code "actress-letter-mismatch"}
     * @param headers      additional key/value pairs for the sidecar's machine block
     *                     (never null — pass empty map if none)
     * @param body         human-readable explanation paragraph (no trailing newline)
     * @return where the folder ended up + where the sidecar was written
     */
    public Result route(Path source, String reasonCode,
                        Map<String, String> headers, String body) throws IOException {
        Path name = source == null ? null : source.getFileName();
        if (name == null) throw new IllegalArgumentException("source must be an absolute path with a file name");
        return route(source, name.toString(), reasonCode, headers, body);
    }

    /**
     * Moves {@code source} to {@code /attention/<targetName>/} and drops a
     * {@code REASON.txt} sidecar inside. Use this overload when the attention folder
     * should be named differently from the source (e.g., renaming to the canonical
     * actress name).
     *
     * @param source       folder to route (path on this volume)
     * @param targetName   basename to use for the destination under {@code /attention/}
     * @param reasonCode   short identifier, e.g. {@code "actress-folder-old-name"}
     * @param headers      additional key/value pairs for the sidecar's machine block
     * @param body         human-readable explanation paragraph (no trailing newline)
     */
    public Result route(Path source, String targetName, String reasonCode,
                        Map<String, String> headers, String body) throws IOException {
        if (source == null || !source.isAbsolute()) {
            throw new IllegalArgumentException("source must be an absolute path");
        }
        if (!fs.exists(source) || !fs.isDirectory(source)) {
            throw new IllegalArgumentException("source does not exist or is not a directory: " + source);
        }
        if (targetName == null || targetName.isBlank()) {
            throw new IllegalArgumentException("targetName must not be blank");
        }

        Path target = ATTENTION_ROOT.resolve(targetName);
        if (fs.exists(target)) {
            throw new IOException("attention target already exists: " + target);
        }

        fs.createDirectories(ATTENTION_ROOT);
        fs.move(source, target);
        log.info("FS mutation [AttentionRouter.route]: routed to attention — volume={} reason={} from={} to={}",
                volumeId, reasonCode, source, target);

        Path sidecar = target.resolve("REASON.txt");
        try {
            byte[] body_ = buildSidecar(reasonCode, source, headers, body).getBytes(StandardCharsets.UTF_8);
            fs.writeFile(sidecar, body_);
        } catch (IOException e) {
            log.warn("attention sidecar write failed (best-effort) for {}: {}", sidecar, e.getMessage());
        }

        return new Result(source.toString(), target.toString(), sidecar.toString());
    }

    private String buildSidecar(String reasonCode, Path originalPath,
                                 Map<String, String> headers, String body) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("reason",       reasonCode);
        merged.put("volume",       volumeId);
        merged.put("originalPath", originalPath.toString());
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || "reason".equals(e.getKey())
                        || "volume".equals(e.getKey()) || "originalPath".equals(e.getKey())
                        || "moved-at".equals(e.getKey())) continue;
                merged.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        merged.put("moved-at", DateTimeFormatter.ISO_INSTANT.format(clock.instant()));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append('\n');
        if (body != null && !body.isBlank()) {
            sb.append(body.strip()).append('\n');
        }
        return sb.toString();
    }

    public record Result(String originalPath, String attentionPath, String sidecarPath) {}
}
