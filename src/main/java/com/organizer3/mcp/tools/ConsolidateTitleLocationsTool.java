package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.titlefolder.TitleFolderService;
import com.organizer3.trash.Trash;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consolidates a title's multiple {@code title_locations} rows down to a single
 * declared canonical location, trashing the rest (folder + sidecars + DB row).
 *
 * <p>Operates only on locations whose volume matches the currently mounted volume.
 * Off-volume locations are reported in {@code skipped} so the caller can re-mount
 * and re-invoke. Cross-volume consolidation cannot be performed in a single call —
 * the session holds exactly one active volume connection at a time.
 *
 * <p>Trashed locations follow the same flow as {@code trash_title_location}:
 * each video is moved to the volume's trash sidecar, noise files are deleted,
 * the folder is removed, and the {@code title_locations} row is dropped.
 *
 * <p>Defaults to {@code dryRun:true}.
 *
 * <p>Orphan-actress cascade is intentionally omitted: the kept location survives,
 * so the title is never orphaned by this op and the existing orphan-actress check
 * (which keys on titles having no surviving location) is a no-op here.
 */
@Slf4j
public class ConsolidateTitleLocationsTool implements Tool {

    private static final Set<String> NOISE_NAMES = Set.of("thumbs.db", ".ds_store", "reason.txt");
    private static final Set<String> NOISE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "attention", "_attention", "queue", "stars", "_sandbox", "recent", "archive"
    );
    private static final Set<String> PROTECTED_ROOTS = Set.of(
            "stars", "queue", "attention", "_attention", "_sandbox", "trash", "recent", "archive"
    );

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final VideoRepository videoRepo;
    private final TitleFolderService folderService;
    private final CurationLog curationLog;
    private final Clock clock;

    public ConsolidateTitleLocationsTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                                         VideoRepository videoRepo, CurationLog curationLog) {
        this(session, jdbi, config, videoRepo, curationLog, Clock.systemUTC());
    }

    ConsolidateTitleLocationsTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                                  VideoRepository videoRepo, CurationLog curationLog, Clock clock) {
        this.session       = session;
        this.jdbi          = jdbi;
        this.config        = config;
        this.videoRepo     = videoRepo;
        this.folderService = new TitleFolderService(null, videoRepo, jdbi);
        this.curationLog   = curationLog;
        this.clock         = clock;
    }

    @Override public String name() { return "consolidate_title_locations"; }

    @Override
    public String description() {
        return "Consolidate a title's multiple title_locations rows down to one canonical location: "
             + "the keepLocationId is retained; all other locations are trashed (folder + sidecars + DB row), "
             + "with each video moved to its volume's trash sidecar. Operates only on locations on the "
             + "currently-mounted volume; off-volume locations are reported in 'skipped' so the caller can "
             + "re-mount and re-invoke. Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleId",        "integer", "Title id whose locations to consolidate.")
                .prop("keepLocationId", "integer", "title_locations.id of the canonical location to retain.")
                .prop("dryRun",         "boolean", "If true (default), return the plan without doing anything.", true)
                .require("titleId", "keepLocationId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long titleId        = Schemas.requireLong(args, "titleId");
        long keepLocationId = Schemas.requireLong(args, "keepLocationId");
        boolean dryRun      = Schemas.optBoolean(args, "dryRun", true);

        Map<String, Object> inputs = Map.of(
                "titleId", titleId,
                "keepLocationId", keepLocationId,
                "dryRun", dryRun
        );

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        List<LocationRow> all = findLocationsForTitle(titleId);
        if (all.isEmpty()) {
            return refused(mountedVolumeId, inputs, titleId,
                    "no title_locations rows found for titleId=" + titleId);
        }

        LocationRow keep = all.stream()
                .filter(l -> l.id() == keepLocationId)
                .findFirst().orElse(null);
        if (keep == null) {
            String ids = all.stream().map(l -> Long.toString(l.id()))
                    .collect(Collectors.joining(", "));
            return refused(mountedVolumeId, inputs, titleId,
                    "keepLocationId=" + keepLocationId + " not in title's location set; available ids: [" + ids + "]");
        }

        List<LocationRow> toTrash = all.stream()
                .filter(l -> l.id() != keepLocationId)
                .toList();

        // No-op: keep is the only location
        if (toTrash.isEmpty()) {
            log.info("consolidate_title_locations: titleId={} only one location (id={}) — no-op",
                    titleId, keepLocationId);
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null,
                    Map.of("titleId", titleId, "consolidated", 0, "note", "single location, no-op"),
                    null, "ok", List.of());
            if (mountedVolumeId != null) curationLog.append(mountedVolumeId, rec);
            return new Result("ok", null, dryRun, titleId,
                    locInfo(keep), List.of(), List.of(), List.of(),
                    0, false);
        }

        // Partition into in-scope vs off-volume
        List<LocationRow> inScope = new ArrayList<>();
        List<LocationRow> offVolume = new ArrayList<>();
        for (LocationRow l : toTrash) {
            if (mountedVolumeId != null && mountedVolumeId.equals(l.volumeId())) {
                inScope.add(l);
            } else {
                offVolume.add(l);
            }
        }

        boolean crossVolume = !offVolume.isEmpty();
        List<LocationInfo> skipped = offVolume.stream().map(this::locInfo).toList();

        if (inScope.isEmpty()) {
            // Nothing to do on this volume
            String msg = "no non-keep locations on mounted volume (" + mountedVolumeId
                    + "); mount one of the other volumes and re-invoke";
            log.info("consolidate_title_locations: titleId={} {}", titleId, msg);
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null,
                    Map.of("titleId", titleId, "skipped", skipped, "reason", msg),
                    null, "skipped", List.of(msg));
            if (mountedVolumeId != null) curationLog.append(mountedVolumeId, rec);
            return new Result("skipped", msg, dryRun, titleId, locInfo(keep),
                    List.of(), List.of(), skipped, 0, crossVolume);
        }

        // From here on we need fs/mount to actually run
        if (fs == null) {
            return refused(mountedVolumeId, inputs, titleId, "no volume mounted");
        }

        // Validate each in-scope location's path
        for (LocationRow l : inScope) {
            String err = validatePath(l.path());
            if (err != null) {
                return refused(mountedVolumeId, inputs, titleId,
                        "location id=" + l.id() + " path=" + l.path() + " refused: " + err);
            }
        }

        // Resolve trash config
        VolumeConfig vol = config.findById(mountedVolumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + mountedVolumeId));
        ServerConfig srv = config.findServerById(vol.server()).orElseThrow(
                () -> new IllegalArgumentException("Server not in config: " + vol.server()));
        if (srv.trash() == null || srv.trash().isBlank()) {
            return refused(mountedVolumeId, inputs, titleId,
                    "server '" + srv.id() + "' has no 'trash:' folder configured");
        }

        // ── Dry-run plan ─────────────────────────────────────────────────────
        if (dryRun) {
            List<LocationInfo> plan = inScope.stream().map(this::locInfo).toList();
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null,
                    Map.of("titleId", titleId, "plannedTrash", plan, "skipped", skipped, "keep", locInfo(keep)),
                    null, "dry-run", List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result("dry-run", null, true, titleId, locInfo(keep),
                    plan, List.of(), skipped, 0, crossVolume);
        }

        // ── Live run ─────────────────────────────────────────────────────────
        Trash trash = new Trash(fs, mountedVolumeId, srv.trash(), clock);

        List<LocationInfo> trashedOk = new ArrayList<>();
        List<FailedLocation> failed = new ArrayList<>();

        for (LocationRow l : inScope) {
            try {
                String err = trashOneLocation(l, fs, trash);
                if (err == null) {
                    trashedOk.add(locInfo(l));
                } else {
                    failed.add(new FailedLocation(l.id(), l.volumeId(), l.path(), err));
                }
            } catch (Exception e) {
                log.warn("consolidate_title_locations: unexpected error trashing location id={} path={}: {}",
                        l.id(), l.path(), e.getMessage(), e);
                failed.add(new FailedLocation(l.id(), l.volumeId(), l.path(),
                        "unexpected: " + e.getMessage()));
            }
        }

        String status = failed.isEmpty() ? (skipped.isEmpty() ? "ok" : "partial") : "partial";
        String err = failed.isEmpty()
                ? (skipped.isEmpty() ? null : "off-volume locations still pending (see skipped)")
                : "some locations failed to trash";

        log.info("consolidate_title_locations: titleId={} kept={} trashed={} failed={} skipped={}",
                titleId, keep.id(), trashedOk.size(), failed.size(), skipped.size());

        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null,
                Map.of("titleId", titleId,
                       "kept", locInfo(keep),
                       "trashed", trashedOk,
                       "failed", failed,
                       "skipped", skipped),
                Map.of("consolidated", trashedOk.size()),
                status,
                err == null ? List.of() : List.of(err));
        curationLog.append(mountedVolumeId, rec);

        return new Result(status, err, false, titleId, locInfo(keep),
                List.of(), trashedOk, skipped, failed.size(), crossVolume,
                failed);
    }

    // ── per-location trash core ──────────────────────────────────────────────

    /**
     * Trashes a single location: its videos, noise files, folder, and DB row.
     * Returns {@code null} on success, an error message on failure.
     */
    private String trashOneLocation(LocationRow loc, VolumeFileSystem fs, Trash trash) {
        Path folderPath = Path.of(loc.path());
        String reason = "Consolidate title locations — " + loc.path();

        // Find videos under this location
        List<Video> videos = findVideosUnderLocation(loc.titleId(), loc.volumeId(), loc.path());
        List<String> failures = new ArrayList<>();
        for (Video v : videos) {
            TitleFolderService.TrashOutcome outcome = folderService.trashVideo(trash, v, reason);
            if (!outcome.success()) {
                failures.add(outcome.source() + " → " + outcome.error());
                log.warn("consolidate_title_locations: failed to trash video locId={} path={} err={}",
                        loc.id(), outcome.source(), outcome.error());
            } else {
                log.info("consolidate_title_locations: trashed video locId={} path={} → {}",
                        loc.id(), outcome.source(), outcome.trashedTo());
            }
        }

        // Purge empty subdirectories
        try {
            purgeEmptyDescendantDirs(fs, folderPath);
        } catch (IOException e) {
            log.warn("consolidate_title_locations: purge empty subdirs failed locId={} path={}: {}",
                    loc.id(), loc.path(), e.getMessage());
        }

        // List remaining children
        List<Path> children;
        try {
            children = fs.exists(folderPath) ? fs.listDirectory(folderPath) : List.of();
        } catch (IOException e) {
            return "could not list directory after trashing videos: " + e.getMessage();
        }

        List<Path> nonNoise = children.stream().filter(p -> !isNoise(p)).toList();
        if (!nonNoise.isEmpty()) {
            String names = nonNoise.stream()
                    .map(p -> p.getFileName().toString())
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(unknown)");
            return "non-noise files remain after trashing videos: [" + names + "]"
                    + (nonNoise.size() > 5 ? " +" + (nonNoise.size() - 5) + " more" : "");
        }

        // Delete noise files
        for (Path child : children) {
            try {
                fs.delete(child);
            } catch (IOException e) {
                failures.add(child.getFileName().toString() + " → " + e.getMessage());
            }
        }

        // Delete the folder
        try {
            if (fs.exists(folderPath)) {
                fs.delete(folderPath);
            }
        } catch (IOException e) {
            return "failed to delete folder: " + e.getMessage();
        }

        // Drop title_locations row
        jdbi.useHandle(h -> h.createUpdate(
                "DELETE FROM title_locations WHERE id = :id")
                .bind("id", loc.id())
                .execute());

        if (!failures.isEmpty()) {
            return "partial — some files failed: " + failures;
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String validatePath(String pathStr) {
        if (pathStr.contains("..")) return "path contains '..' traversal sequence";
        Path p = Path.of(pathStr);
        if (p.getNameCount() < 1) return "path must not be the volume root";
        String top = p.getName(0).toString();
        if (!ALLOWED_PREFIXES.contains(top)) {
            return "top-level folder '" + top + "' is not allowed; must be one of " + ALLOWED_PREFIXES;
        }
        if (isProtectedRoot(p)) return "path is a protected tier root and cannot be targeted";
        return null;
    }

    private boolean isProtectedRoot(Path path) {
        int depth = path.getNameCount();
        if (depth == 0) return true;
        String top = path.getName(0).toString();
        if (!PROTECTED_ROOTS.contains(top)) return false;
        if (top.equals("stars") && depth <= 2) return true;
        return depth == 1;
    }

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

    private void purgeEmptyDescendantDirs(VolumeFileSystem fs, Path parent) throws IOException {
        if (!fs.exists(parent) || !fs.isDirectory(parent)) return;
        for (Path child : fs.listDirectory(parent)) {
            if (fs.isDirectory(child)) {
                purgeEmptyDescendantDirs(fs, child);
                List<Path> remaining = fs.listDirectory(child);
                if (remaining.isEmpty()) {
                    fs.delete(child);
                }
            }
        }
    }

    private List<LocationRow> findLocationsForTitle(long titleId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT id, title_id, volume_id, path FROM title_locations
                        WHERE title_id = :tid
                          AND stale_since IS NULL
                        ORDER BY id
                        """)
                .bind("tid", titleId)
                .map((rs, ctx) -> new LocationRow(
                        rs.getLong("id"),
                        rs.getLong("title_id"),
                        rs.getString("volume_id"),
                        rs.getString("path")))
                .list());
    }

    private List<Video> findVideosUnderLocation(long titleId, String volumeId, String path) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT id FROM videos
                        WHERE title_id = :tid
                          AND volume_id = :v
                          AND (path = :p OR path LIKE :p || '/%')
                        """)
                .bind("tid", titleId)
                .bind("v", volumeId)
                .bind("p", path)
                .mapTo(Long.class)
                .list())
                .stream()
                .flatMap(id -> videoRepo.findById(id).stream())
                .toList();
    }

    private LocationInfo locInfo(LocationRow r) {
        return new LocationInfo(r.id(), r.volumeId(), r.path());
    }

    private Result refused(String volumeId, Map<String, Object> inputs, long titleId, String reason) {
        log.info("consolidate_title_locations refused titleId={} reason={}", titleId, reason);
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null, null, null,
                "refused", List.of("refused: " + reason));
        curationLog.append(volumeId != null ? volumeId : "unknown", rec);
        return new Result("refused", reason, false, titleId, null,
                List.of(), List.of(), List.of(), 0, false);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    record LocationRow(long id, long titleId, String volumeId, String path) {}

    public record LocationInfo(long id, String volume, String path) {}

    public record FailedLocation(long id, String volume, String path, String error) {}

    public record Result(
            String status,
            String error,
            boolean dryRun,
            long titleId,
            LocationInfo kept,
            List<LocationInfo> plannedTrash,
            List<LocationInfo> trashed,
            List<LocationInfo> skipped,
            int failedCount,
            boolean crossVolume,
            List<FailedLocation> failed
    ) {
        // Convenience constructor without explicit failed list
        public Result(String status, String error, boolean dryRun, long titleId,
                      LocationInfo kept, List<LocationInfo> plannedTrash,
                      List<LocationInfo> trashed, List<LocationInfo> skipped,
                      int failedCount, boolean crossVolume) {
            this(status, error, dryRun, titleId, kept, plannedTrash, trashed, skipped,
                    failedCount, crossVolume, List.of());
        }
    }
}
