package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import com.organizer3.shell.SessionContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Probe metadata for up to {@code limit} videos on the currently-mounted volume and
 * return progress. Cursor-based so an agent can resume: each response reports the
 * highest id seen, which the caller echoes back as {@code fromId} on the next call.
 * When {@code count} comes back as 0, all rows with null duration on that volume
 * have been attempted.
 *
 * <p>Mirrors the shell command {@code probe videos} but in bounded-call form — keeps
 * per-request latency small enough that agent orchestrators (Claude Desktop, long-chain
 * reasoning) don't time out on a full-volume backfill.
 *
 * <p>Prober is injected as a {@link BiFunction} so the test classpath doesn't have to
 * load {@code VideoProbe}'s FFmpeg native libraries.
 */
public class ProbeVideosBatchTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;

    private final SessionContext session;
    private final VideoRepository videoRepo;
    private final BiFunction<Long, String, Map<String, Object>> prober;

    public ProbeVideosBatchTool(SessionContext session, VideoRepository videoRepo,
                                 BiFunction<Long, String, Map<String, Object>> prober) {
        this.session = session;
        this.videoRepo = videoRepo;
        this.prober = prober;
    }

    @Override public String name()        { return "probe_videos_batch"; }
    @Override public String description() {
        return "Probe metadata for up to 'limit' unprobed videos on the mounted volume. "
             + "Cursor-paginated: pass nextCursor back as fromId. Stop when count=0. "
             + "Requires an active mount.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("fromId", "integer",
                        "Start after this video id. Use 0 for the first call; echo back 'nextCursor' from the previous response.", 0)
                .prop("limit",  "integer",
                        "Maximum videos to probe per call. Default 50, max 200.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one first (see mount_volume).");
        }
        long fromId = Math.max(0L, Schemas.optLong(args, "fromId", 0));
        int limit   = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        List<Video> batch = videoRepo.findUnprobed(volumeId, fromId, limit);
        int ok = 0, failed = 0;
        long cursor = fromId;
        for (Video v : batch) {
            cursor = Math.max(cursor, v.getId());
            Map<String, Object> meta = prober.apply(v.getId(), v.getFilename());
            if (meta.isEmpty() || !meta.containsKey("durationSeconds")) {
                failed++;
            } else {
                apply(v, meta);
                ok++;
            }
        }
        long remaining = videoRepo.countUnprobed(volumeId);
        return new Result(volumeId, fromId, cursor, batch.size(), ok, failed, remaining);
    }

    private void apply(Video v, Map<String, Object> meta) {
        Long    durationSec = meta.get("durationSeconds") instanceof Number n ? n.longValue() : null;
        Integer width       = meta.get("width")  instanceof Number n ? n.intValue()  : null;
        Integer height      = meta.get("height") instanceof Number n ? n.intValue()  : null;
        String  videoCodec  = nullIfUnknown(stringOf(meta.get("videoCodec")));
        String  audioCodec  = nullIfUnknown(stringOf(meta.get("audioCodec")));
        String  container   = containerFrom(v.getFilename());
        videoRepo.updateMetadata(v.getId(), durationSec, width, height, videoCodec, audioCodec, container);
    }

    private static String containerFrom(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        return (dot < 0 || dot == filename.length() - 1) ? null
                : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String nullIfUnknown(String s) {
        return (s == null || "unknown".equalsIgnoreCase(s)) ? null : s;
    }

    private static String stringOf(Object o) { return o == null ? null : o.toString(); }

    public record Result(String volumeId, long fromId, long nextCursor,
                         int scanned, int ok, int failed, long remainingUnprobed) {}
}
