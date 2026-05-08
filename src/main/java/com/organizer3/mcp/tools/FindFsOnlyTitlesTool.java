package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.sync.MediaExtensions;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects folders on disk whose code isn't in the DB — sync drift detection.
 *
 * <p>Walks the entire volume tree looking for "title-shaped" folders (a folder that contains
 * at least one video file directly). For each such folder, attempts to parse the basename as a
 * JAV code via {@link BasenameParser}. If the parsed code is absent from the {@code titles} table,
 * the folder is included in results. Folders whose code is already in the DB are silently excluded.
 *
 * <p>Best-effort parsing: if a basename can't be parsed, the folder is still included with
 * {@code parsedCode: null} — callers can filter these separately.
 *
 * <p>Read-only — no mutation, no curation log.
 */
@Slf4j
public class FindFsOnlyTitlesTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;

    public FindFsOnlyTitlesTool(SessionContext session, Jdbi jdbi) {
        this.session = session;
        this.jdbi    = jdbi;
    }

    @Override public String name() { return "find_fs_only_titles"; }

    @Override
    public String description() {
        return "Detects folders on disk whose code isn't in the DB (sync drift, manual drops, "
             + "partial restores). Walks the mounted volume tree; for each title-shaped folder "
             + "(contains video files), checks whether the parsed code exists in the titles table. "
             + "Read-only sanity check.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volume_id", "string", "Volume id. Defaults to the currently mounted volume.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg = Schemas.optString(args, "volume_id", null);

        String mountedVolumeId = session.getMountedVolumeId();
        String effectiveVolumeId = (volumeIdArg != null && !volumeIdArg.isBlank())
                ? volumeIdArg : mountedVolumeId;

        if (effectiveVolumeId == null) {
            throw new IllegalArgumentException("No volume mounted and no volume_id provided.");
        }
        if (!effectiveVolumeId.equals(mountedVolumeId)) {
            throw new IllegalArgumentException(
                    "volume_id '" + effectiveVolumeId + "' does not match mounted volume '"
                    + mountedVolumeId + "'.");
        }

        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("No active volume connection. Mount the volume first.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        log.info("find_fs_only_titles starting volume={}", effectiveVolumeId);

        // ── Load all known codes from DB ──────────────────────────────────────
        Set<String> knownCodes = loadKnownCodes(effectiveVolumeId);
        log.info("find_fs_only_titles knownCodes={}", knownCodes.size());

        // ── Walk the volume tree ──────────────────────────────────────────────
        List<TitleResult> results = new ArrayList<>();
        try {
            walkForTitleFolders(fs, Path.of("/"), results, knownCodes);
        } catch (IOException e) {
            log.warn("find_fs_only_titles walk error volume={}: {}", effectiveVolumeId, e.getMessage());
            throw new IllegalStateException("FS walk failed: " + e.getMessage(), e);
        }

        log.info("find_fs_only_titles found {} fs-only folders on volume={}", results.size(), effectiveVolumeId);
        return new Result(effectiveVolumeId, results);
    }

    /**
     * Walks the directory tree rooted at {@code root}. For each directory that contains
     * at least one video file as a direct child (title-shaped), checks if its code is in DB.
     * If not found, adds to results.
     */
    private void walkForTitleFolders(VolumeFileSystem fs, Path root,
                                      List<TitleResult> results,
                                      Set<String> knownCodes) throws IOException {
        List<Path> children;
        try {
            children = fs.listDirectory(root);
        } catch (IOException e) {
            // Skip unreadable directories
            return;
        }

        List<Path> subdirs = new ArrayList<>();
        int videoCount = 0;
        long totalSize = 0;
        int childFileCount = 0;

        for (Path child : children) {
            if (fs.isDirectory(child)) {
                subdirs.add(child);
            } else {
                childFileCount++;
                if (MediaExtensions.isVideo(child)) {
                    videoCount++;
                    try {
                        totalSize += fs.size(child);
                    } catch (IOException ignored) {}
                }
            }
        }

        if (videoCount > 0) {
            // This is a title-shaped folder
            String basename = root.getFileName() != null ? root.getFileName().toString() : "";
            String parsedCode = BasenameParser.extractCode(basename);
            List<String> parsedCast = List.of();
            if (parsedCode == null || !knownCodes.contains(parsedCode.toUpperCase())) {
                // Include in results — either no code or unknown code
                // For cast: do a best-effort full parse to extract cast names
                if (parsedCode != null) {
                    BasenameParser.ParseResult pr = BasenameParser.parse(basename);
                    parsedCast = pr.castTokens();
                }
                results.add(new TitleResult(root.toString(), parsedCode, parsedCast, totalSize, childFileCount));
            }
            // Recurse into subdirs anyway (nested video folders are possible)
        }

        // Recurse into subdirectories
        for (Path subdir : subdirs) {
            walkForTitleFolders(fs, subdir, results, knownCodes);
        }
    }

    /**
     * Load the set of known title codes from the DB for the given volume.
     * Codes are stored upper-case in DB; compare case-insensitively.
     */
    private Set<String> loadKnownCodes(String volumeId) {
        return jdbi.withHandle(h ->
                new HashSet<>(h.createQuery("""
                        SELECT UPPER(t.code)
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE tl.volume_id = :volumeId
                          AND tl.stale_since IS NULL
                        """)
                        .bind("volumeId", volumeId)
                        .mapTo(String.class)
                        .list()));
    }

    // ── output records ────────────────────────────────────────────────────────

    public record TitleResult(
            String folderPath,
            String parsedCode,
            List<String> parsedCast,
            long sizeBytes,
            int childFileCount
    ) {}

    public record Result(String volumeId, List<TitleResult> results) {}
}
