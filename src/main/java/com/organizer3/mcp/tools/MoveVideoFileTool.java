package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.MediaConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves a single video file from one location to another on the same mounted volume.
 *
 * <p>Optionally registers the destination in the DB via {@code addAsLocationOf}: if
 * the title code is provided, a {@code videos} row is inserted at the new path.
 * A {@code title_locations} row is also inserted if no existing location for the
 * title is an ancestor of (or equals) the destination's parent directory.
 *
 * <p>Default {@code dryRun: true}.
 * All calls (ok / dry-run / failed) are appended to the curation log.
 */
@Slf4j
public class MoveVideoFileTool implements Tool {

    /** Extensions recognized as valid video files (lowercase, no dot). */
    static final List<String> VIDEO_EXTENSIONS = MediaConfig.DEFAULT_VIDEO_EXTENSIONS;

    private final SessionContext session;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository locationRepo;
    private final Jdbi jdbi;
    private final CurationLog curationLog;

    public MoveVideoFileTool(SessionContext session,
                             TitleRepository titleRepo,
                             TitleLocationRepository locationRepo,
                             Jdbi jdbi,
                             CurationLog curationLog) {
        this.session     = session;
        this.titleRepo   = titleRepo;
        this.locationRepo = locationRepo;
        this.jdbi        = jdbi;
        this.curationLog = curationLog;
    }

    @Override public String name() { return "move_video_file"; }

    @Override
    public String description() {
        return "Moves a single video file from sourcePath to destPath on the mounted volume. "
             + "The destination parent directory must already exist — the tool will not create it. "
             + "Optional addAsLocationOf inserts a videos row (and, if needed, a title_locations row) "
             + "after the move. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",       "string",  "Must match the currently mounted volume id.")
                .prop("sourcePath",     "string",  "Volume-relative absolute path of the source video file.")
                .prop("destPath",       "string",  "Volume-relative absolute path where the file should land (full path including filename).")
                .prop("addAsLocationOf","string",  "Optional title code (e.g. 'MIMK-190'). If provided, inserts a videos row and (if needed) a title_locations row after the move.")
                .prop("dryRun",         "boolean", "If true (default), return the plan without moving anything.", true)
                .require("volumeId", "sourcePath", "destPath")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId        = Schemas.requireString(args, "volumeId").trim();
        String sourcePath      = Schemas.requireString(args, "sourcePath").trim();
        String destPath        = Schemas.requireString(args, "destPath").trim();
        String addAsLocationOf = Schemas.optString(args, "addAsLocationOf", null);
        boolean dryRun         = Schemas.optBoolean(args, "dryRun", true);

        if (addAsLocationOf != null) addAsLocationOf = addAsLocationOf.trim().toUpperCase();

        Map<String, Object> inputs = buildInputs(volumeId, sourcePath, destPath, addAsLocationOf, dryRun);

        // ── Volume check ────────────────────────────────────────────────────────
        String mountedVolumeId = session.getMountedVolumeId();
        if (mountedVolumeId == null) {
            return failed(null, inputs, "no volume mounted");
        }
        if (!volumeId.equals(mountedVolumeId)) {
            return failed(mountedVolumeId, inputs,
                    "volumeId '" + volumeId + "' does not match mounted volume '" + mountedVolumeId + "'");
        }

        VolumeConnection conn = session.getActiveConnection();
        VolumeFileSystem fs   = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        // ── Extension check (before any I/O) ────────────────────────────────────
        String ext = extensionOf(sourcePath).toLowerCase();
        if (!VIDEO_EXTENSIONS.contains(ext)) {
            return failed(mountedVolumeId, inputs,
                    "source extension '." + ext + "' is not a recognized video extension");
        }

        java.nio.file.Path src  = java.nio.file.Path.of(sourcePath);
        java.nio.file.Path dest = java.nio.file.Path.of(destPath);
        java.nio.file.Path destParent = dest.getParent();

        // ── Resolve addAsLocationOf title before any I/O ────────────────────────
        Long titleId = null;
        if (addAsLocationOf != null) {
            String code = addAsLocationOf;
            Optional<com.organizer3.model.Title> titleOpt = titleRepo.findByCode(code);
            if (titleOpt.isEmpty()) {
                return failed(mountedVolumeId, inputs,
                        "addAsLocationOf: no title found with code '" + code + "'");
            }
            titleId = titleOpt.get().getId();
        }

        Map<String, Object> plan = Map.of(
                "from", sourcePath,
                "to",   destPath,
                "addAsLocationOf", addAsLocationOf != null ? addAsLocationOf : "<none>"
        );

        // ── Dry-run ─────────────────────────────────────────────────────────────
        if (dryRun || fs == null) {
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            curationLog.append(mountedVolumeId, new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null, status, List.of()));
            return new Result(sourcePath, destPath, status, null, null);
        }

        // ── FS existence checks ─────────────────────────────────────────────────
        if (!fs.exists(src)) {
            return failed(mountedVolumeId, inputs, "source does not exist: " + sourcePath);
        }
        if (fs.isDirectory(src)) {
            return failed(mountedVolumeId, inputs, "source is a directory, not a file: " + sourcePath);
        }
        if (fs.exists(dest)) {
            return failed(mountedVolumeId, inputs, "destination already exists: " + destPath);
        }
        if (!fs.exists(destParent)) {
            return failed(mountedVolumeId, inputs,
                    "destination parent does not exist: " + destParent
                    + " — create the folder first");
        }

        // ── Execute move ────────────────────────────────────────────────────────
        try {
            fs.move(src, dest);
            log.info("move_video_file volume={} from={} to={}", mountedVolumeId, sourcePath, destPath);
        } catch (IOException e) {
            log.warn("move_video_file failed volume={} from={} to={}: {}",
                    mountedVolumeId, sourcePath, destPath, e.getMessage());
            curationLog.append(mountedVolumeId, new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                    "failed", List.of(e.getMessage())));
            return new Result(sourcePath, destPath, "failed", null, e.getMessage());
        }

        // ── DB integration ──────────────────────────────────────────────────────
        DbInserts dbInserts = null;
        if (titleId != null) {
            final Long resolvedTitleId = titleId;
            final String resolvedCode  = addAsLocationOf;
            dbInserts = insertDbRows(mountedVolumeId, resolvedTitleId, resolvedCode, dest, destParent, fs);
        }

        curationLog.append(mountedVolumeId, new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(), inputs, plan,
                Map.of("path", sourcePath),
                Map.of("path", destPath),
                "ok", List.of()));
        return new Result(sourcePath, destPath, "ok", dbInserts, null);
    }

    // ── DB integration helpers ──────────────────────────────────────────────

    /**
     * Inserts a videos row for the destination file, and conditionally inserts a
     * title_locations row if no existing location for the title is an ancestor of
     * (or equals) the destination parent.
     *
     * <p>If the size fetch fails after a successful move, the videos row is still
     * inserted with {@code size_bytes = null} and a warning is logged — the move is
     * already irrecoverable from this tool's perspective.
     */
    private DbInserts insertDbRows(String volumeId, long titleId, String titleCode,
                                   java.nio.file.Path dest, java.nio.file.Path destParent,
                                   VolumeFileSystem fs) {
        // Fetch size best-effort
        Long sizeBytes = null;
        try {
            sizeBytes = fs.size(dest);
        } catch (IOException e) {
            log.warn("move_video_file: could not read file size after move — videos.size_bytes will be null. path={} error={}",
                    dest, e.getMessage());
        }

        final Long finalSize = sizeBytes;
        String filename = dest.getFileName().toString();
        String ext = extensionOf(filename);

        return jdbi.inTransaction(h -> {
            // Insert videos row
            long videoId = h.createUpdate("""
                    INSERT INTO videos
                        (title_id, volume_id, filename, path, last_seen_at,
                         duration_sec, width, height, video_codec, audio_codec, container,
                         size_bytes)
                    VALUES
                        (:titleId, :volumeId, :filename, :path, :lastSeenAt,
                         NULL, NULL, NULL, NULL, NULL, :container,
                         :sizeBytes)
                    """)
                    .bind("titleId", titleId)
                    .bind("volumeId", volumeId)
                    .bind("filename", filename)
                    .bind("path", dest.toString())
                    .bind("lastSeenAt", LocalDate.now().toString())
                    .bind("container", ext.isEmpty() ? null : ext)
                    .bind("sizeBytes", finalSize)
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            log.info("move_video_file: inserted videos row id={} titleCode={} path={}",
                    videoId, titleCode, dest);

            // Check if title_locations already covers this path via an ancestor row
            List<TitleLocation> liveLocations = locationRepo.findByTitle(titleId);
            boolean alreadyCovered = liveLocations.stream()
                    .filter(l -> volumeId.equals(l.getVolumeId()))
                    .anyMatch(l -> isAncestorOrEqual(l.getPath(), destParent));

            Long locationId = null;
            if (!alreadyCovered) {
                String partitionId = derivePartitionId(destParent);
                locationId = h.createUpdate("""
                        INSERT INTO title_locations
                            (title_id, volume_id, partition_id, path, last_seen_at, added_date, stale_since)
                        VALUES (:titleId, :volumeId, :partitionId, :path, :lastSeenAt, :addedDate, NULL)
                        ON CONFLICT(title_id, volume_id, path)
                        DO UPDATE SET
                            stale_since  = NULL,
                            last_seen_at = excluded.last_seen_at,
                            partition_id = excluded.partition_id
                        """)
                        .bind("titleId", titleId)
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .bind("path", destParent.toString())
                        .bind("lastSeenAt", LocalDate.now().toString())
                        .bind("addedDate", LocalDate.now().toString())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                log.info("move_video_file: inserted title_locations row id={} titleCode={} path={}",
                        locationId, titleCode, destParent);
            } else {
                log.info("move_video_file: title_locations already covers path — skipping insert. titleCode={} destParent={}",
                        titleCode, destParent);
            }

            return new DbInserts(videoId, locationId);
        });
    }

    /**
     * Returns true if {@code ancestor} is an ancestor of (or equals) {@code path}.
     * Used to determine whether an existing title_locations row already covers the
     * destination's parent directory.
     */
    static boolean isAncestorOrEqual(java.nio.file.Path ancestor, java.nio.file.Path path) {
        return path.startsWith(ancestor);
    }

    /**
     * Derives a partition_id from the destination parent path.
     * For {@code /stars/<tier>/...} paths, the partition is the tier name.
     * For other paths, uses the top-level folder name.
     */
    static String derivePartitionId(java.nio.file.Path destParent) {
        if (destParent.getNameCount() >= 2) {
            String top = destParent.getName(0).toString();
            if ("stars".equals(top)) {
                return destParent.getName(1).toString();
            }
        }
        if (destParent.getNameCount() >= 1) {
            return destParent.getName(0).toString();
        }
        return "unknown";
    }

    // ── Common helpers ──────────────────────────────────────────────────────────

    /** Extracts the lowercase file extension (no dot), or empty string if none. */
    static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot > slash && dot < path.length() - 1) {
            return path.substring(dot + 1).toLowerCase();
        }
        return "";
    }

    private Result failed(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("move_video_file refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        curationLog.append(logVolume, new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(), inputs, null, null, null,
                "failed", List.of(reason)));
        return new Result(null, null, "failed", null, reason);
    }

    private Map<String, Object> buildInputs(String volumeId, String sourcePath,
                                             String destPath, String addAsLocationOf,
                                             boolean dryRun) {
        var m = new LinkedHashMap<String, Object>();
        m.put("volumeId", volumeId);
        m.put("sourcePath", sourcePath);
        m.put("destPath", destPath);
        if (addAsLocationOf != null) m.put("addAsLocationOf", addAsLocationOf);
        m.put("dryRun", dryRun);
        return java.util.Collections.unmodifiableMap(m);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    // ── Result types ────────────────────────────────────────────────────────────

    /** DB rows inserted after a successful move with addAsLocationOf. */
    public record DbInserts(long videoId, Long locationId) {
        /** True if a title_locations row was also inserted (not just a videos row). */
        public boolean insertedLocation() { return locationId != null; }
    }

    public record Result(String from, String to, String status, DbInserts dbInserts, String error) {}
}
