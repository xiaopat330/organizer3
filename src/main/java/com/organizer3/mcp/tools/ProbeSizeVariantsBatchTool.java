package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Probe duration metadata for unprobed videos that belong to size-variant candidate titles
 * (those flagged by {@link FindSizeVariantTitlesTool}). Targeted alternative to
 * {@link ProbeVideosBatchTool} — probes only the ~2-4k videos tied to duplicate candidates
 * instead of the full 60k-row library.
 *
 * <p>Cursor-paginated: pass {@code nextCursor} back as {@code fromId} until {@code count=0}.
 * Does not require a mounted volume; probing runs through the HTTP streaming endpoint.
 */
public class ProbeSizeVariantsBatchTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;

    private final VideoRepository videoRepo;
    private final BiFunction<Long, String, Map<String, Object>> prober;

    public ProbeSizeVariantsBatchTool(VideoRepository videoRepo,
                                      BiFunction<Long, String, Map<String, Object>> prober) {
        this.videoRepo = videoRepo;
        this.prober    = prober;
    }

    @Override public String name() { return "probe_size_variants_batch"; }

    @Override public String description() {
        return "Probe duration for unprobed videos whose titles are flagged by "
             + "find_size_variant_titles. Cursor-paginated: pass nextCursor back as fromId. "
             + "Stop when count=0. Does not require a mounted volume.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("fromId",     "integer", "Start after this video id (0 for first call).", 0)
                .prop("limit",      "integer",
                        "Max videos to probe per call. Default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ".",
                        DEFAULT_LIMIT)
                .prop("min_ratio",  "number",  "Size ratio threshold matching find_size_variant_titles. Default 2.0.", 2.0)
                .prop("min_videos", "integer", "Min videos per title threshold. Default 2.", 2)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long   fromId    = Math.max(0L, Schemas.optLong(args, "fromId", 0));
        int    limit     = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        double minRatio  = Math.max(1.0, Schemas.optDouble(args, "min_ratio", 2.0));
        int    minVideos = Math.max(2, Schemas.optInt(args, "min_videos", 2));

        List<Video> batch = videoRepo.findUnprobedForSizeVariants(fromId, limit, minRatio, minVideos);
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
        long remaining = videoRepo.countUnprobedForSizeVariants(minRatio, minVideos);
        return new Result(fromId, cursor, batch.size(), ok, failed, remaining);
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

    public record Result(long fromId, long nextCursor,
                         int scanned, int ok, int failed, long remainingUnprobed) {}
}
