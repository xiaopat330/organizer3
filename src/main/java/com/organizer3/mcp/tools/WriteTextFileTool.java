package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a UTF-8 text file to an allowed path on the mounted volume.
 *
 * <p>Allowed top-level prefixes (volume-relative): {@code /attention/}, {@code /queue/},
 * {@code /_sandbox/}. Allowed extensions: {@code .md}, {@code .txt}, {@code .json},
 * {@code .yaml}, {@code .log}. Default {@code overwrite:false} refuses on existing path.
 *
 * <p>The {@code volumeId} input must match the currently mounted volume; it is logged for
 * audit purposes but session state is authoritative.
 *
 * <p>All calls (successful, refused, failed) are appended to the curation log.
 */
@Slf4j
public class WriteTextFileTool implements Tool {

    private static final Set<String> ALLOWED_PREFIXES = Set.of("attention", "queue", "_sandbox");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".md", ".txt", ".json", ".yaml", ".log");

    private final SessionContext session;
    private final CurationLog curationLog;

    public WriteTextFileTool(SessionContext session, CurationLog curationLog) {
        this.session     = session;
        this.curationLog = curationLog;
    }

    @Override public String name() { return "write_text_file"; }

    @Override
    public String description() {
        return "Writes a UTF-8 text file to the mounted volume. "
             + "Allowed top-level prefixes: /attention/, /queue/, /_sandbox/. "
             + "Allowed extensions: .md, .txt, .json, .yaml, .log. "
             + "Default overwrite:false refuses on existing path.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Volume identifier — must match the mounted volume.")
                .prop("path",     "string",  "Volume-relative path, e.g. /attention/Foo/NOTES.md")
                .prop("content",  "string",  "UTF-8 text content to write.")
                .prop("overwrite","boolean", "If true, overwrite an existing file. Default false.", false)
                .require("volumeId", "path", "content")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg = Schemas.requireString(args, "volumeId");
        String pathArg     = Schemas.requireString(args, "path");
        String content     = Schemas.requireString(args, "content");
        boolean overwrite  = Schemas.optBoolean(args, "overwrite", false);

        Map<String, Object> inputs = Map.of(
                "volumeId",    volumeIdArg,
                "path",        pathArg,
                "overwrite",   overwrite,
                "contentBytes", content.getBytes(StandardCharsets.UTF_8).length
        );

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        // ── Volume check ────────────────────────────────────────────────────
        if (mountedVolumeId == null || !mountedVolumeId.equals(volumeIdArg)) {
            return refused(volumeIdArg, inputs,
                    "volumeId mismatch: mounted=" + mountedVolumeId + " requested=" + volumeIdArg);
        }
        if (fs == null) {
            return refused(volumeIdArg, inputs, "no volume mounted");
        }

        // ── Path validation ─────────────────────────────────────────────────
        if (pathArg.contains("..")) {
            return refused(volumeIdArg, inputs, "path contains '..' traversal sequence");
        }

        Path filePath = Path.of(pathArg);
        if (filePath.getNameCount() < 2) {
            return refused(volumeIdArg, inputs, "path must be at least 2 segments deep (not a volume root)");
        }

        String topLevel = filePath.getName(0).toString();
        if (!ALLOWED_PREFIXES.contains(topLevel)) {
            return refused(volumeIdArg, inputs,
                    "top-level folder '" + topLevel + "' is not allowed; must be one of " + ALLOWED_PREFIXES);
        }

        String filename  = filePath.getFileName().toString();
        String extension = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.')).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return refused(volumeIdArg, inputs,
                    "extension '" + extension + "' is not allowed; must be one of " + ALLOWED_EXTENSIONS);
        }

        // ── Overwrite guard ─────────────────────────────────────────────────
        if (!overwrite && fs.exists(filePath)) {
            return refused(volumeIdArg, inputs,
                    "file already exists and overwrite=false: " + pathArg);
        }

        // ── Execute ─────────────────────────────────────────────────────────
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                fs.createDirectories(parent);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            fs.writeFile(filePath, bytes);

            log.info("write_text_file volume={} path={} bytes={}", volumeIdArg, pathArg, bytes.length);
            CurationLogRecord record = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null, null,
                    Map.of("path", pathArg, "bytes", bytes.length),
                    "ok", List.of());
            curationLog.append(volumeIdArg, record);

            return new Result(pathArg, bytes.length, "ok", null);

        } catch (IOException e) {
            log.warn("write_text_file failed volume={} path={}: {}", volumeIdArg, pathArg, e.getMessage());
            CurationLogRecord record = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null, null, null,
                    "failed", List.of(e.getMessage()));
            curationLog.append(volumeIdArg, record);
            return new Result(pathArg, 0, "failed", e.getMessage());
        }
    }

    private Result refused(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("write_text_file refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null, null, null,
                "failed", List.of("refused: " + reason));
        curationLog.append(logVolume, record);
        return new Result(
                inputs.getOrDefault("path", "").toString(),
                0, "refused", reason);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    public record Result(String path, int bytes, String status, String error) {}
}
