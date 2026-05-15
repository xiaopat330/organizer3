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

/**
 * Atomically trashes a title location folder and drops its {@code title_locations} DB row.
 *
 * <p>Collapses the legacy 3-call dance
 * ({@code trash_duplicate_video} → {@code delete_loose_files} → {@code delete_empty_folder})
 * into a single call for the case where a folder is a duplicate location of a title and the
 * user wants the whole folder gone.
 *
 * <p>Defaults to {@code dryRun:true}.
 */
@Slf4j
public class TrashTitleLocationTool implements Tool {

    /** Exact file names that are noise (case-insensitive). */
    private static final Set<String> NOISE_NAMES = Set.of("thumbs.db", ".ds_store", "reason.txt");

    /** File extensions that are treated as noise. */
    private static final Set<String> NOISE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");

    /**
     * Allowed top-level folders (volume-relative path must start with one of these).
     */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "attention", "_attention", "queue", "stars", "_sandbox", "recent", "archive"
    );

    /**
     * Protected tier roots that cannot be targeted directly.
     * /stars and /stars/&lt;tier&gt; are also protected (depth &le; 2 under stars).
     */
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

    public TrashTitleLocationTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                                  VideoRepository videoRepo, CurationLog curationLog) {
        this(session, jdbi, config, videoRepo, curationLog, Clock.systemUTC());
    }

    TrashTitleLocationTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                           VideoRepository videoRepo, CurationLog curationLog, Clock clock) {
        this.session       = session;
        this.jdbi          = jdbi;
        this.config        = config;
        this.videoRepo     = videoRepo;
        this.folderService = new TitleFolderService(null, videoRepo, jdbi);
        this.curationLog   = curationLog;
        this.clock         = clock;
    }

    @Override public String name() { return "trash_title_location"; }

    @Override
    public String description() {
        return "Atomically trash a title location folder (all videos + noise files) and drop the "
             + "title_locations DB row. Collapses trash_duplicate_video + delete_loose_files + "
             + "delete_empty_folder into one call. Requires an exact title_location match for the "
             + "given path. Surfaces whether the title becomes orphaned (no remaining locations). "
             + "Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Volume identifier — must match the mounted volume.")
                .prop("path",     "string",  "Volume-relative folder path, e.g. /queue/Old Name (LABEL-001)")
                .prop("dryRun",   "boolean", "If true (default), return the plan without doing anything.", true)
                .prop("cascadeOrphanActresses", "boolean",
                        "If true, delete actresses left with no titles having any surviving location "
                        + "(plus their title_actresses, actress_aliases, actress_companies rows). "
                        + "Default false — orphans are reported only.", false)
                .require("volumeId", "path")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg = Schemas.requireString(args, "volumeId");
        String pathArg     = Schemas.requireString(args, "path");
        boolean dryRun     = Schemas.optBoolean(args, "dryRun", true);
        boolean cascadeOrphans = Schemas.optBoolean(args, "cascadeOrphanActresses", false);

        Map<String, Object> inputs = Map.of(
                "volumeId", volumeIdArg,
                "path",     pathArg,
                "dryRun",   dryRun,
                "cascadeOrphanActresses", cascadeOrphans
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

        // ── Locate matching title_location row ──────────────────────────────
        List<LocationRow> locations = findExactLocations(mountedVolumeId, pathArg);
        if (locations.isEmpty()) {
            return refused(volumeIdArg, inputs,
                    "no active title_location row found for volume=" + mountedVolumeId + " path=" + pathArg);
        }
        if (locations.size() > 1) {
            return refused(volumeIdArg, inputs,
                    "multiple title_location rows found for this path (" + locations.size()
                    + ") — resolve manually");
        }
        LocationRow loc = locations.get(0);

        // ── Resolve trash config ────────────────────────────────────────────
        VolumeConfig vol = config.findById(volumeIdArg).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeIdArg));
        ServerConfig srv = config.findServerById(vol.server()).orElseThrow(
                () -> new IllegalArgumentException("Server not in config: " + vol.server()));
        if (srv.trash() == null || srv.trash().isBlank()) {
            return refused(volumeIdArg, inputs,
                    "server '" + srv.id() + "' has no 'trash:' folder configured");
        }

        // ── Find videos under this location ─────────────────────────────────
        List<Video> videos = findVideosUnderLocation(loc.titleId(), mountedVolumeId, pathArg);

        // ── Dry-run: return plan ─────────────────────────────────────────────
        if (dryRun) {
            List<String> videoPaths = videos.stream()
                    .map(v -> v.getPath().toString())
                    .toList();
            CurationLogRecord record = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(),
                    inputs, null,
                    Map.of("titleId", loc.titleId(), "videosToTrash", videoPaths),
                    null, "dry-run", List.of());
            curationLog.append(volumeIdArg, record);
            return new Result(pathArg, loc.titleId(), true, "dry-run", null,
                    videoPaths, List.of(), List.of(), false,
                    List.of(), 0);
        }

        // ── Live run ─────────────────────────────────────────────────────────
        Trash trash = new Trash(fs, mountedVolumeId, srv.trash(), clock);
        String reason = "Trash title location — " + pathArg;

        List<String> trashed = new ArrayList<>();
        List<String> failed  = new ArrayList<>();

        for (Video v : videos) {
            TitleFolderService.TrashOutcome outcome = folderService.trashVideo(trash, v, reason);
            if (outcome.success()) {
                log.info("trash_title_location: trashed video titleId={} videoId={} path={} trashedTo={}",
                        loc.titleId(), v.getId(), outcome.source(), outcome.trashedTo());
                trashed.add(outcome.source() + " → " + outcome.trashedTo());
            } else {
                log.warn("trash_title_location: failed video titleId={} videoId={} path={} error={}",
                        loc.titleId(), v.getId(), outcome.source(), outcome.error());
                failed.add(outcome.source() + " → " + outcome.error());
            }
        }

        // ── Purge empty subdirectories left after video trashing ─────────────
        try {
            purgeEmptyDescendantDirs(fs, folderPath);
        } catch (IOException e) {
            log.warn("trash_title_location: error purging empty subdirs volume={} path={}: {}",
                    volumeIdArg, pathArg, e.getMessage());
        }

        // ── Clean up noise files remaining in the folder ─────────────────────
        List<Path> children;
        try {
            children = fs.exists(folderPath) ? fs.listDirectory(folderPath) : List.of();
        } catch (IOException e) {
            return partialResult(volumeIdArg, inputs, pathArg, loc.titleId(), trashed, failed,
                    "could not list directory after trashing videos: " + e.getMessage());
        }

        List<Path> nonNoise = children.stream().filter(p -> !isNoise(p)).toList();
        if (!nonNoise.isEmpty()) {
            String names = nonNoise.stream()
                    .map(p -> p.getFileName().toString())
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(unknown)");
            String msg = "non-noise files remain after trashing videos: [" + names + "]"
                    + (nonNoise.size() > 5 ? " +" + (nonNoise.size() - 5) + " more" : "");
            log.warn("trash_title_location: {} volume={} path={}", msg, volumeIdArg, pathArg);
            return partialResult(volumeIdArg, inputs, pathArg, loc.titleId(), trashed, failed, msg);
        }

        // ── Delete noise files, then the folder ──────────────────────────────
        for (Path child : children) {
            try {
                fs.delete(child);
            } catch (IOException e) {
                log.warn("trash_title_location: failed to delete noise file {} : {}", child, e.getMessage());
                failed.add(child.getFileName().toString() + " → " + e.getMessage());
            }
        }

        try {
            if (fs.exists(folderPath)) {
                fs.delete(folderPath);
            }
        } catch (IOException e) {
            return partialResult(volumeIdArg, inputs, pathArg, loc.titleId(), trashed, failed,
                    "failed to delete folder: " + e.getMessage());
        }

        // ── Drop the title_location row ───────────────────────────────────────
        jdbi.useHandle(h -> h.createUpdate(
                "DELETE FROM title_locations WHERE id = :id")
                .bind("id", loc.locationId())
                .execute());

        // ── Check for orphaned title ──────────────────────────────────────────
        boolean orphaned = isOrphaned(loc.titleId());

        // ── Detect orphaned actresses (and optionally cascade-delete) ─────────
        List<OrphanActress> orphanedActresses = findOrphanedActresses(loc.titleId());
        int cascadedActresses = 0;
        if (cascadeOrphans && !orphanedActresses.isEmpty()) {
            cascadedActresses = cascadeDeleteActresses(orphanedActresses);
        }

        log.info("trash_title_location volume={} path={} titleId={} videos={} failed={} orphaned={} orphanActresses={} cascaded={}",
                volumeIdArg, pathArg, loc.titleId(), trashed.size(), failed.size(), orphaned,
                orphanedActresses.size(), cascadedActresses);

        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null,
                Map.of("titleId", loc.titleId(), "trashed", trashed, "failed", failed,
                        "orphaned", orphaned,
                        "orphanedActresses", orphanedActresses,
                        "cascadedActresses", cascadedActresses),
                Map.of("path", pathArg, "deleted", true),
                failed.isEmpty() ? "ok" : "partial",
                failed.isEmpty() ? List.of() : List.of("some operations failed: " + failed));
        curationLog.append(volumeIdArg, record);

        return new Result(pathArg, loc.titleId(), false,
                failed.isEmpty() ? "ok" : "partial",
                failed.isEmpty() ? null : "some operations failed: " + failed,
                List.of(), trashed, failed, orphaned,
                orphanedActresses, cascadedActresses);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Recursively deletes empty subdirectories under {@code parent}.
     * A directory is deletable if, after recursively cleaning its children,
     * it is empty (contains no files and no non-empty subdirectories).
     */
    private void purgeEmptyDescendantDirs(VolumeFileSystem fs, Path parent) throws IOException {
        if (!fs.exists(parent) || !fs.isDirectory(parent)) return;
        for (Path child : fs.listDirectory(parent)) {
            if (fs.isDirectory(child)) {
                purgeEmptyDescendantDirs(fs, child);
                // delete if now empty
                List<Path> remaining = fs.listDirectory(child);
                if (remaining.isEmpty()) {
                    fs.delete(child);
                }
            }
        }
    }

    private List<LocationRow> findExactLocations(String volumeId, String path) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT id, title_id FROM title_locations
                        WHERE volume_id = :v
                          AND stale_since IS NULL
                          AND path = :p
                        """)
                .bind("v", volumeId)
                .bind("p", path)
                .map((rs, ctx) -> new LocationRow(rs.getLong("id"), rs.getLong("title_id")))
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

    /**
     * Returns the list of actresses credited on {@code trashedTitleId} who, after the trash op,
     * have NO surviving titles with at least one {@code title_locations} row. Deterministic order
     * (by actress id ascending) so cascade behavior is reproducible.
     */
    private List<OrphanActress> findOrphanedActresses(long trashedTitleId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT a.id, a.canonical_name FROM actresses a
                        JOIN title_actresses ta ON ta.actress_id = a.id
                        WHERE ta.title_id = :tid
                          AND NOT EXISTS (
                              SELECT 1 FROM title_actresses ta2
                              JOIN title_locations tl ON tl.title_id = ta2.title_id
                              WHERE ta2.actress_id = a.id
                                AND tl.stale_since IS NULL
                          )
                        ORDER BY a.id
                        """)
                .bind("tid", trashedTitleId)
                .map((rs, ctx) -> new OrphanActress(rs.getLong("id"), rs.getString("canonical_name")))
                .list());
    }

    /**
     * Deletes the given orphan actresses and their dependent rows
     * (title_actresses, actress_aliases, actress_companies, actresses).
     * Returns the number of actresses fully deleted.
     */
    private int cascadeDeleteActresses(List<OrphanActress> orphans) {
        int deleted = 0;
        for (OrphanActress oa : orphans) {
            jdbi.useTransaction(h -> {
                h.createUpdate("DELETE FROM title_actresses  WHERE actress_id = :id").bind("id", oa.id()).execute();
                h.createUpdate("DELETE FROM actress_aliases  WHERE actress_id = :id").bind("id", oa.id()).execute();
                h.createUpdate("DELETE FROM actress_companies WHERE actress_id = :id").bind("id", oa.id()).execute();
                h.createUpdate("DELETE FROM actresses        WHERE id = :id").bind("id", oa.id()).execute();
            });
            log.info("trash_title_location: cascaded orphan actress id={} canonical_name={}",
                    oa.id(), oa.canonicalName());
            deleted++;
        }
        return deleted;
    }

    private boolean isOrphaned(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM title_locations WHERE title_id = :tid")
                .bind("tid", titleId)
                .mapTo(Integer.class)
                .one()) == 0;
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

    private boolean isProtectedRoot(Path path) {
        int depth = path.getNameCount();
        if (depth == 0) return true;

        String top = path.getName(0).toString();
        if (!PROTECTED_ROOTS.contains(top)) return false;

        if (top.equals("stars") && depth <= 2) return true;
        return depth == 1;
    }

    private Result refused(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("trash_title_location refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null, null, null,
                "refused", List.of("refused: " + reason));
        curationLog.append(logVolume, record);
        return new Result(inputs.getOrDefault("path", "").toString(), 0L, false,
                "refused", reason, List.of(), List.of(), List.of(), false,
                List.of(), 0);
    }

    private Result partialResult(String volumeId, Map<String, Object> inputs, String path,
                                 long titleId, List<String> trashed, List<String> failed,
                                 String error) {
        CurationLogRecord record = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(),
                inputs, null,
                Map.of("trashed", trashed, "failed", failed),
                null, "partial", List.of(error));
        curationLog.append(volumeId, record);
        return new Result(path, titleId, false, "partial", error, List.of(), trashed, failed, false,
                List.of(), 0);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    record LocationRow(long locationId, long titleId) {}

    public record OrphanActress(long id, String canonicalName) {}

    public record Result(
            String path,
            long titleId,
            boolean dryRun,
            String status,
            String error,
            List<String> plannedVideos,
            List<String> trashed,
            List<String> failed,
            boolean titleOrphaned,
            List<OrphanActress> orphanedActresses,
            int cascadedActresses
    ) {}
}
