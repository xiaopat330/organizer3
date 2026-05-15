package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP adapter that trashes all duplicate videos for a title on the mounted
 * volume except the named keeper. The actual per-file trash + DB-row delete
 * lives in {@link TitleFolderService#trashVideo}; this adapter:
 *
 * <ol>
 *   <li>resolves the mounted volume + Trash primitive from the session;</li>
 *   <li>narrows the title's videos to the mounted volume + drops the keeper;</li>
 *   <li>iterates the remaining set, calling {@code service.trashVideo} per
 *       file and aggregating the outcomes into the legacy MCP Result.</li>
 * </ol>
 *
 * <p>Gated on {@code mcp.allowMutations} and {@code mcp.allowFileOps}.
 * Defaults to {@code dryRun: true}.
 */
@Slf4j
public class TrashDuplicateVideoTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final VideoRepository videoRepo;
    private final TitleFolderService folderService;
    private final Clock clock;

    public TrashDuplicateVideoTool(SessionContext session, Jdbi jdbi,
                                   OrganizerConfig config, VideoRepository videoRepo) {
        this(session, jdbi, config, videoRepo, Clock.systemUTC());
    }

    TrashDuplicateVideoTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                            VideoRepository videoRepo, Clock clock) {
        this.session       = session;
        this.jdbi          = jdbi;
        this.config        = config;
        this.videoRepo     = videoRepo;
        this.folderService = new TitleFolderService(null, videoRepo, jdbi);
        this.clock         = clock;
    }

    @Override public String name() { return "trash_duplicate_video"; }
    @Override public String description() {
        return "Trash all videos for a title on the mounted volume except the one specified by "
             + "keepVideoId, then remove their DB records. Actions the output of "
             + "find_size_variant_titles or find_duplicate_candidates. Defaults to dryRun:true. "
             + "Pass allowOrphanLocation:true to also drop title_location rows whose folder is "
             + "left empty of videos after the trash batch (the keeper's location is never dropped).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode",           "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("keepVideoId",         "integer", "DB id of the video to retain. All others on this volume are trashed.")
                .prop("dryRun",              "boolean", "If true (default), return the plan without moving anything.", true)
                .prop("allowOrphanLocation", "boolean", "If true, drop title_location rows whose folder has no remaining videos after the trash batch.", false)
                .require("titleCode", "keepVideoId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode         = Schemas.requireString(args, "titleCode").trim();
        long   keepId            = Schemas.requireLong(args, "keepVideoId");
        boolean dryRun           = Schemas.optBoolean(args, "dryRun", true);
        boolean allowOrphanLocation = Schemas.optBoolean(args, "allowOrphanLocation", false);

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) throw new IllegalArgumentException(
                "No volume is currently mounted. Mount one before calling this tool.");
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) throw new IllegalArgumentException(
                "Active connection is closed; re-mount the volume.");

        VolumeConfig vol = config.findById(volumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeId));
        ServerConfig srv = config.findServerById(vol.server()).orElseThrow(
                () -> new IllegalArgumentException("Server not in config: " + vol.server()));
        if (srv.trash() == null || srv.trash().isBlank()) throw new IllegalArgumentException(
                "Server '" + srv.id() + "' has no 'trash:' folder configured.");

        long titleId = lookupTitleId(titleCode);
        List<Video> allVideos = videoRepo.findByTitle(titleId);
        List<Video> onVolume = allVideos.stream()
                .filter(v -> volumeId.equals(v.getVolumeId()))
                .toList();

        if (onVolume.isEmpty()) throw new IllegalArgumentException(
                "No videos for '" + titleCode + "' on mounted volume '" + volumeId + "'.");

        boolean keepExists = onVolume.stream().anyMatch(v -> v.getId() == keepId);
        if (!keepExists) throw new IllegalArgumentException(
                "keepVideoId=" + keepId + " is not a video for '" + titleCode
                + "' on volume '" + volumeId + "'.");

        Video keeper = onVolume.stream().filter(v -> v.getId() == keepId).findFirst().orElseThrow();
        List<Video> toTrash = onVolume.stream().filter(v -> v.getId() != keepId).toList();

        Plan plan = new Plan(volumeId, titleCode, toVideoInfo(keeper),
                toTrash.stream().map(this::toVideoInfo).toList());

        if (dryRun || toTrash.isEmpty()) {
            return new Result(dryRun || toTrash.isEmpty(), plan, List.of(), List.of(), 0, List.of());
        }

        VolumeFileSystem fs = conn.fileSystem();
        Trash trash = new Trash(fs, volumeId, srv.trash(), clock);
        List<String> trashed = new ArrayList<>();
        List<String> failed  = new ArrayList<>();
        String reason = "Duplicate video — kept videoId " + keepId;

        for (Video v : toTrash) {
            TitleFolderService.TrashOutcome outcome = folderService.trashVideo(trash, v, reason);
            if (outcome.success()) {
                log.info("MCP trash_duplicate_video: trashed + deleted row — titleCode={} videoId={} path={} trashedTo={}",
                        titleCode, v.getId(), outcome.source(), outcome.trashedTo());
                trashed.add(outcome.source() + " → " + outcome.trashedTo());
            } else {
                log.warn("MCP trash_duplicate_video failed — titleCode={} videoId={} path={} error={}",
                        titleCode, v.getId(), outcome.source(), outcome.error());
                failed.add(outcome.source() + " → " + outcome.error());
            }
        }
        log.info("MCP trash_duplicate_video summary — titleCode={} keptVideoId={} trashed={} failed={}",
                titleCode, keepId, trashed.size(), failed.size());

        // ── Optional orphan-location cascade ────────────────────────────────
        // After all video rows are deleted (trashVideo deletes the DB row on success),
        // find any title_location on this volume whose folder path has no remaining videos.
        // The COUNT(*) check in dropEmptyLocations naturally protects the keeper's location.
        int locationsDropped = 0;
        List<OrphanedTitle> orphanedTitles = List.of();
        if (allowOrphanLocation && !trashed.isEmpty()) {
            var cascade = dropEmptyLocations(titleId, volumeId);
            locationsDropped = cascade.locationsDropped();
            orphanedTitles   = cascade.orphanedTitles();
            if (locationsDropped > 0) {
                log.info("MCP trash_duplicate_video cascade: dropped {} location(s) titleCode={}",
                        locationsDropped, titleCode);
            }
        }

        return new Result(false, plan, trashed, failed, locationsDropped, orphanedTitles);
    }

    private long lookupTitleId(String code) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT id FROM titles WHERE UPPER(code) = UPPER(:code)")
                .bind("code", code)
                .mapTo(Long.class)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Title not found: " + code)));
    }

    private VideoInfo toVideoInfo(Video v) {
        return new VideoInfo(v.getId(), v.getFilename(), v.getPath().toString(),
                v.getSizeBytes(), v.getDurationSec());
    }

    /**
     * After trashing videos (and deleting their DB rows), find any title_location on this
     * volume whose folder path no longer has any remaining video under it. Drops those
     * location rows and surfaces any titles that are left with no remaining locations at all.
     *
     * <p>The keeper's location is naturally preserved because its video still exists in the
     * videos table — the COUNT(*) check on remaining videos will be &gt; 0 for that path.
     */
    private CascadeResult dropEmptyLocations(long titleId, String volumeId) {
        return jdbi.inTransaction(h -> {
            // Find all live locations for this title on this volume.
            List<Long> candidateIds = h.createQuery("""
                            SELECT id FROM title_locations
                            WHERE title_id = :titleId
                              AND volume_id = :volumeId
                              AND stale_since IS NULL
                            """)
                    .bind("titleId",  titleId)
                    .bind("volumeId", volumeId)
                    .mapTo(Long.class)
                    .list();

            if (candidateIds.isEmpty()) return new CascadeResult(0, List.of());

            // Of these, keep only the ones where no video remains under that location path.
            // Videos are stored with full paths; a video belongs under a location if its
            // path starts with locationPath + "/".
            List<Long> emptyLocationIds = new ArrayList<>();
            for (long locId : candidateIds) {
                String locPath = h.createQuery("SELECT path FROM title_locations WHERE id = :id")
                        .bind("id", locId)
                        .mapTo(String.class)
                        .one();
                int remaining = h.createQuery("""
                                SELECT COUNT(*) FROM videos
                                WHERE title_id = :titleId
                                  AND volume_id = :volumeId
                                  AND (path = :p OR path LIKE :p || '/%')
                                """)
                        .bind("titleId",  titleId)
                        .bind("volumeId", volumeId)
                        .bind("p", locPath)
                        .mapTo(Integer.class)
                        .one();
                if (remaining == 0) {
                    emptyLocationIds.add(locId);
                }
            }

            if (emptyLocationIds.isEmpty()) return new CascadeResult(0, List.of());

            h.createUpdate("DELETE FROM title_locations WHERE id IN (<ids>)")
                    .bindList("ids", emptyLocationIds)
                    .execute();

            // Surface any titles now left with no locations at all.
            List<OrphanedTitle> orphaned = h.createQuery("""
                            SELECT t.id, t.code
                            FROM titles t
                            WHERE t.id = :titleId
                              AND NOT EXISTS (SELECT 1 FROM title_locations tl WHERE tl.title_id = t.id)
                            """)
                    .bind("titleId", titleId)
                    .map((rs, ctx) -> new OrphanedTitle(rs.getLong("id"), rs.getString("code")))
                    .list();

            return new CascadeResult(emptyLocationIds.size(), orphaned);
        });
    }

    public record VideoInfo(long id, String filename, String path,
                            Long sizeBytes, Long durationSec) {}
    public record Plan(String volumeId, String titleCode,
                       VideoInfo keep, List<VideoInfo> toTrash) {}
    public record OrphanedTitle(long titleId, String titleCode) {}
    private record CascadeResult(int locationsDropped, List<OrphanedTitle> orphanedTitles) {}
    public record Result(boolean dryRun, Plan plan, List<String> trashed, List<String> failed,
                         int locationsDropped, List<OrphanedTitle> orphanedTitles) {}
}
