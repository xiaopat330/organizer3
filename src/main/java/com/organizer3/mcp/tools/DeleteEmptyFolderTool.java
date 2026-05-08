package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes a folder from the mounted volume after verifying it is empty (modulo sidecars).
 *
 * <p>Refusal conditions:
 * <ul>
 *   <li>Folder does not exist or is not a directory.</li>
 *   <li>Folder is a protected tier root ({@code /stars}, {@code /stars/<tier>}, {@code /queue},
 *       {@code /attention}, {@code /_attention}, {@code /_sandbox}, or the volume root).</li>
 *   <li>Folder is non-empty — contains any file that is not a sidecar when
 *       {@code allowSidecars:true} (default). With {@code allowSidecars:false} any child
 *       causes a refusal.</li>
 *   <li>Folder still appears as a {@code title_locations.path} prefix on this volume.</li>
 * </ul>
 *
 * <p>Sidecar files: {@code .json}, {@code REASON.txt}, {@code NOTES.txt}, {@code NOTES.md}.
 * When {@code allowSidecars:true}, these files are deleted alongside the folder itself.
 */
@Slf4j
@RequiredArgsConstructor
public class DeleteEmptyFolderTool implements Tool {

    /** File names and extensions that are considered sidecars (not real content). */
    private static final Set<String> SIDECAR_NAMES = Set.of("REASON.txt", "NOTES.txt", "NOTES.md");
    private static final Set<String> SIDECAR_EXTENSIONS = Set.of(".json");

    /** Volume-relative folder paths that are protected tier roots. */
    private static final Set<String> PROTECTED_ROOTS = Set.of(
            "stars", "queue", "attention", "_attention", "_sandbox", "trash"
    );

    private final SessionContext session;
    private final Jdbi jdbi;
    private final CurationLog curationLog;

    @Override public String name() { return "delete_empty_folder"; }

    @Override
    public String description() {
        return "Deletes an empty (or sidecar-only) folder from the mounted volume. "
             + "Refuses if the folder contains non-sidecar files, is a protected tier root, "
             + "or still appears as a title_locations path prefix on this volume. "
             + "Default allowSidecars:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",      "string",  "Volume identifier — must match the mounted volume.")
                .prop("path",          "string",  "Volume-relative folder path, e.g. /queue/Old Name (LABEL-001)")
                .prop("allowSidecars", "boolean", "If true (default), ignore sidecar files when checking emptiness.", true)
                .require("volumeId", "path")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg   = Schemas.requireString(args, "volumeId");
        String pathArg       = Schemas.requireString(args, "path");
        boolean allowSidecars = Schemas.optBoolean(args, "allowSidecars", true);

        Map<String, Object> inputs = Map.of(
                "volumeId",      volumeIdArg,
                "path",          pathArg,
                "allowSidecars", allowSidecars
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

        // ── Path traversal guard ────────────────────────────────────────────
        if (pathArg.contains("..")) {
            return refused(volumeIdArg, inputs, "path contains '..' traversal sequence");
        }

        Path folderPath = Path.of(pathArg);

        // ── Tier-root guard ─────────────────────────────────────────────────
        if (isProtectedRoot(folderPath)) {
            return refused(volumeIdArg, inputs, "path is a protected tier root and cannot be deleted");
        }

        // ── Existence check ─────────────────────────────────────────────────
        if (!fs.exists(folderPath)) {
            return refused(volumeIdArg, inputs, "path does not exist: " + pathArg);
        }
        if (!fs.isDirectory(folderPath)) {
            return refused(volumeIdArg, inputs, "path is not a directory: " + pathArg);
        }

        // ── Title-locations prefix check ────────────────────────────────────
        int locationCount = countLocationsByPrefix(mountedVolumeId, pathArg);
        if (locationCount > 0) {
            return refused(volumeIdArg, inputs,
                    "folder is still referenced by " + locationCount
                    + " title_location row(s) on volume " + mountedVolumeId);
        }

        // ── Emptiness check ─────────────────────────────────────────────────
        List<Path> children;
        try {
            children = fs.listDirectory(folderPath);
        } catch (IOException e) {
            return refused(volumeIdArg, inputs, "could not list directory: " + e.getMessage());
        }

        List<Path> nonSidecars = children.stream()
                .filter(p -> !isSidecar(p, allowSidecars))
                .toList();

        if (!nonSidecars.isEmpty()) {
            String names = nonSidecars.stream()
                    .map(p -> p.getFileName().toString())
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(unknown)");
            return refused(volumeIdArg, inputs,
                    "folder is not empty; non-sidecar children: [" + names + "]"
                    + (nonSidecars.size() > 5 ? " +" + (nonSidecars.size() - 5) + " more" : ""));
        }

        // ── Execute: delete sidecars first, then folder ─────────────────────
        try {
            for (Path child : children) {
                fs.delete(child);
            }
            fs.delete(folderPath);

            log.info("delete_empty_folder volume={} path={} deletedSidecars={}",
                    volumeIdArg, pathArg, children.size());
            CurationLogRecord record = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null,
                    Map.of("path", pathArg, "sidecarsDeleted", children.size()),
                    Map.of("path", pathArg, "deleted", true),
                    "ok", List.of());
            curationLog.append(volumeIdArg, record);

            return new Result(pathArg, true, "ok", null);

        } catch (IOException e) {
            log.warn("delete_empty_folder failed volume={} path={}: {}", volumeIdArg, pathArg, e.getMessage());
            CurationLogRecord record = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null, null, null,
                    "failed", List.of(e.getMessage()));
            curationLog.append(volumeIdArg, record);
            return new Result(pathArg, false, "failed", e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isProtectedRoot(Path path) {
        int depth = path.getNameCount();
        if (depth == 0) return true; // volume root

        String top = path.getName(0).toString();
        if (!PROTECTED_ROOTS.contains(top)) return false;

        // /stars itself is protected; /stars/<tier> is also protected
        if (top.equals("stars") && depth <= 2) return true;
        // all other protected roots are only the root itself
        return depth == 1;
    }

    private boolean isSidecar(Path path, boolean allowSidecars) {
        if (!allowSidecars) return false;
        String name = path.getFileName().toString();
        if (SIDECAR_NAMES.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot).toLowerCase();
            return SIDECAR_EXTENSIONS.contains(ext);
        }
        return false;
    }

    private int countLocationsByPrefix(String volumeId, String path) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM title_locations
                        WHERE volume_id = :v
                          AND stale_since IS NULL
                          AND (path = :p OR path LIKE :p || '/%')
                        """)
                .bind("v", volumeId)
                .bind("p", path)
                .mapTo(Integer.class)
                .one());
    }

    private Result refused(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("delete_empty_folder refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null, null, null,
                "failed", List.of("refused: " + reason));
        curationLog.append(logVolume, record);
        return new Result(inputs.getOrDefault("path", "").toString(), false, "refused", reason);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    public record Result(String path, boolean deleted, String status, String error) {}
}
