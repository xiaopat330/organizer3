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
import com.organizer3.trash.Trash;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Trash duplicate videos from a title, keeping one chosen video in place.
 *
 * <p>Intended to action the output of {@code find_size_variant_titles} or
 * {@code find_duplicate_candidates}. Given a title code and the video ID to keep,
 * trashes all other videos for that title on the currently-mounted volume using
 * the Trash primitive, then removes their DB records.
 *
 * <p>Gated on {@code mcp.allowMutations} and {@code mcp.allowFileOps}.
 * Defaults to {@code dryRun: true}.
 */
public class TrashDuplicateVideoTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final VideoRepository videoRepo;
    private final Clock clock;

    public TrashDuplicateVideoTool(SessionContext session, Jdbi jdbi,
                                   OrganizerConfig config, VideoRepository videoRepo) {
        this(session, jdbi, config, videoRepo, Clock.systemUTC());
    }

    TrashDuplicateVideoTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                            VideoRepository videoRepo, Clock clock) {
        this.session   = session;
        this.jdbi      = jdbi;
        this.config    = config;
        this.videoRepo = videoRepo;
        this.clock     = clock;
    }

    @Override public String name() { return "trash_duplicate_video"; }
    @Override public String description() {
        return "Trash all videos for a title on the mounted volume except the one specified by "
             + "keepVideoId, then remove their DB records. Actions the output of "
             + "find_size_variant_titles or find_duplicate_candidates. Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode",   "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("keepVideoId", "integer", "DB id of the video to retain. All others on this volume are trashed.")
                .prop("dryRun",      "boolean", "If true (default), return the plan without moving anything.", true)
                .require("titleCode", "keepVideoId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode  = Schemas.requireString(args, "titleCode").trim();
        long   keepId     = Schemas.requireLong(args, "keepVideoId");
        boolean dryRun    = Schemas.optBoolean(args, "dryRun", true);

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

        Plan plan = new Plan(
                volumeId, titleCode,
                toVideoInfo(keeper),
                toTrash.stream().map(this::toVideoInfo).toList());

        if (dryRun || toTrash.isEmpty()) return new Result(dryRun || toTrash.isEmpty(), plan, List.of(), List.of());

        VolumeFileSystem fs = conn.fileSystem();
        Trash trash = new Trash(fs, volumeId, srv.trash(), clock);
        List<String> trashed = new ArrayList<>();
        List<String> failed  = new ArrayList<>();

        for (Video v : toTrash) {
            Path filePath = v.getPath();
            try {
                Trash.Result r = trash.trashItem(filePath, "video");
                videoRepo.delete(v.getId());
                trashed.add(filePath + " → " + r.trashedPath());
            } catch (IOException e) {
                failed.add(filePath + " → " + e.getMessage());
            }
        }
        return new Result(false, plan, trashed, failed);
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

    public record VideoInfo(long id, String filename, String path,
                            Long sizeBytes, Long durationSec) {}
    public record Plan(String volumeId, String titleCode,
                       VideoInfo keep, List<VideoInfo> toTrash) {}
    public record Result(boolean dryRun, Plan plan, List<String> trashed, List<String> failed) {}
}
