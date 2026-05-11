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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes a specific allowlist of "noise" files from a folder on the mounted volume.
 *
 * <p>Targeted files (case-insensitive matching):
 * <ul>
 *   <li>Exact names: {@code Thumbs.db}, {@code .DS_Store}, {@code REASON.txt}</li>
 *   <li>By extension: {@code .jpg}, {@code .jpeg}, {@code .png}</li>
 * </ul>
 *
 * <p>Allowed path prefixes (volume-relative):
 * {@code /attention/}, {@code /_attention/}, {@code /queue/}, {@code /stars/},
 * {@code /_sandbox/}, {@code /recent/}, {@code /archive/}.
 *
 * <p>Refusal conditions:
 * <ul>
 *   <li>Folder does not exist or is not a directory.</li>
 *   <li>Path does not start with an allowed prefix.</li>
 *   <li>Path is a protected tier root ({@code /stars}, {@code /stars/<tier>}, etc.).</li>
 * </ul>
 *
 * <p>Default {@code dryRun:true} — returns the plan without deleting anything.
 */
@Slf4j
@RequiredArgsConstructor
public class DeleteLooseFilesTool implements Tool {

    /** Exact file names that are always noise (case-insensitive comparison). */
    private static final Set<String> NOISE_NAMES = Set.of("thumbs.db", ".ds_store", "reason.txt");

    /** File extensions that are treated as noise (cover image leftovers). */
    private static final Set<String> NOISE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");

    /**
     * Allowed top-level folders. Must start with one of these prefixes (volume-relative).
     * Mirror of write_text_file but with stars/recent/archive added.
     */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "attention", "_attention", "queue", "stars", "_sandbox", "recent", "archive"
    );

    /**
     * Protected tier roots that cannot be targeted directly.
     * /stars and /stars/<tier> are also protected (depth <= 2 under stars).
     */
    private static final Set<String> PROTECTED_ROOTS = Set.of(
            "stars", "queue", "attention", "_attention", "_sandbox", "trash", "recent", "archive"
    );

    private final SessionContext session;
    private final CurationLog curationLog;

    @Override public String name() { return "delete_loose_files"; }

    @Override
    public String description() {
        return "Deletes allowlisted noise files (Thumbs.db, .DS_Store, REASON.txt, *.jpg/jpeg/png) "
             + "from a folder on the mounted volume. "
             + "Allowed path prefixes: /attention/, /_attention/, /queue/, /stars/, /_sandbox/, /recent/, /archive/. "
             + "Default dryRun:true — returns the plan without deleting.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Volume identifier — must match the mounted volume.")
                .prop("path",     "string",  "Volume-relative folder path, e.g. /attention/Actress Name (LABEL-001)")
                .prop("dryRun",   "boolean", "If true (default), return the plan without deleting.", true)
                .require("volumeId", "path")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg = Schemas.requireString(args, "volumeId");
        String pathArg     = Schemas.requireString(args, "path");
        boolean dryRun     = Schemas.optBoolean(args, "dryRun", true);

        Map<String, Object> inputs = Map.of(
                "volumeId", volumeIdArg,
                "path",     pathArg,
                "dryRun",   dryRun
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

        // ── Prefix check ────────────────────────────────────────────────────
        if (folderPath.getNameCount() < 1) {
            return refused(volumeIdArg, inputs, "path must not be the volume root");
        }
        String topLevel = folderPath.getName(0).toString();
        if (!ALLOWED_PREFIXES.contains(topLevel)) {
            return refused(volumeIdArg, inputs,
                    "top-level folder '" + topLevel + "' is not allowed; must be one of " + ALLOWED_PREFIXES);
        }

        // ── Tier-root guard ─────────────────────────────────────────────────
        if (isProtectedRoot(folderPath)) {
            return refused(volumeIdArg, inputs, "path is a protected tier root and cannot be targeted");
        }

        // ── Existence check ─────────────────────────────────────────────────
        if (!fs.exists(folderPath)) {
            return refused(volumeIdArg, inputs, "path does not exist: " + pathArg);
        }
        if (!fs.isDirectory(folderPath)) {
            return refused(volumeIdArg, inputs, "path is not a directory: " + pathArg);
        }

        // ── List children and classify ──────────────────────────────────────
        List<Path> children;
        try {
            children = fs.listDirectory(folderPath);
        } catch (IOException e) {
            return refused(volumeIdArg, inputs, "could not list directory: " + e.getMessage());
        }

        List<String> toDelete = new ArrayList<>();
        List<String> toSkip   = new ArrayList<>();

        for (Path child : children) {
            if (isNoise(child)) {
                toDelete.add(child.getFileName().toString());
            } else {
                toSkip.add(child.getFileName().toString());
            }
        }

        // ── Dry-run: return plan without deleting ───────────────────────────
        if (dryRun) {
            CurationLogRecord record = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null,
                    Map.of("wouldDelete", toDelete, "wouldSkip", toSkip),
                    null,
                    "dry-run", List.of());
            curationLog.append(volumeIdArg, record);

            boolean wouldBeEmpty = toSkip.isEmpty();
            return new Result(pathArg, true, toDelete, toSkip, wouldBeEmpty, "dry-run", null);
        }

        // ── Execute: delete each noise file ─────────────────────────────────
        List<String> deleted  = new ArrayList<>();
        List<String> failed   = new ArrayList<>();

        for (Path child : children) {
            if (!isNoise(child)) continue;
            try {
                fs.delete(child);
                deleted.add(child.getFileName().toString());
            } catch (IOException e) {
                log.warn("delete_loose_files failed to delete volume={} file={}: {}",
                        volumeIdArg, child, e.getMessage());
                failed.add(child.getFileName().toString() + " (" + e.getMessage() + ")");
            }
        }

        boolean nowEmpty = toSkip.isEmpty() && failed.isEmpty();
        String status = failed.isEmpty() ? "ok" : "partial";

        log.info("delete_loose_files volume={} path={} deleted={} skipped={} failed={}",
                volumeIdArg, pathArg, deleted.size(), toSkip.size(), failed.size());

        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null,
                Map.of("deleted", deleted, "skipped", toSkip, "failed", failed),
                Map.of("path", pathArg, "deletedCount", deleted.size(), "nowEmpty", nowEmpty),
                status, failed.isEmpty() ? List.of() : List.of("some deletes failed: " + failed));
        curationLog.append(volumeIdArg, record);

        String errorMsg = failed.isEmpty() ? null : "some files could not be deleted: " + failed;
        return new Result(pathArg, false, deleted, toSkip, nowEmpty, status, errorMsg);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isNoise(Path path) {
        String name = path.getFileName().toString();
        if (NOISE_NAMES.contains(name.toLowerCase())) return true;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot).toLowerCase();
            return NOISE_EXTENSIONS.contains(ext);
        }
        return false;
    }

    private boolean isProtectedRoot(Path path) {
        int depth = path.getNameCount();
        if (depth == 0) return true; // volume root

        String top = path.getName(0).toString();
        if (!PROTECTED_ROOTS.contains(top)) return false;

        // /stars itself is protected; /stars/<tier> is also protected
        if (top.equals("stars") && depth <= 2) return true;
        // all other protected roots: only the root itself is protected
        return depth == 1;
    }

    private Result refused(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("delete_loose_files refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null, null, null,
                "refused", List.of("refused: " + reason));
        curationLog.append(logVolume, record);
        return new Result(
                inputs.getOrDefault("path", "").toString(),
                false, List.of(), List.of(), false, "refused", reason);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    public record Result(
            String path,
            boolean dryRun,
            List<String> deleted,
            List<String> skipped,
            boolean emptyAfter,
            String status,
            String error
    ) {}
}
