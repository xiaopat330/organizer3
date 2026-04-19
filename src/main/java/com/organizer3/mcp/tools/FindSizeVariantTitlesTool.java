package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Flag multi-video titles whose videos span a wide size range — a strong signal that
 * the videos are the same content encoded at different qualities (quality variants)
 * rather than a legitimate multi-part release, where all parts tend to share similar
 * bitrate and size.
 *
 * <p>Size-only heuristic, complementary to {@link FindDuplicateCandidatesTool} which
 * requires probed duration. Useful before the ffmpeg probe has been run across the
 * library; an SMB size stat per video is orders of magnitude cheaper than a full probe.
 *
 * <p>Requires every video on a title to have {@code size_bytes} populated; titles with
 * any missing size are skipped (not false-flagged).
 */
public class FindSizeVariantTitlesTool implements Tool {

    private static final double DEFAULT_RATIO = 2.0;
    private static final int DEFAULT_LIMIT    = 100;
    private static final int MAX_LIMIT        = 5000;

    private final Jdbi jdbi;

    public FindSizeVariantTitlesTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_size_variant_titles"; }
    @Override public String description() {
        return "Flag multi-video titles whose file sizes span >= min_ratio between the largest "
             + "and smallest video. High variance suggests quality-variants (same content, "
             + "different encodings), not a legitimate multi-part set.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("min_ratio",    "number",  "Minimum max/min size ratio. Default 2.0.", DEFAULT_RATIO)
                .prop("min_videos",   "integer", "Minimum videos per title to consider. Default 2.", 2)
                .prop("limit",        "integer", "Maximum candidate titles. Default " + DEFAULT_LIMIT
                                                 + ", max " + MAX_LIMIT + ".", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        double minRatio = Math.max(1.0, Schemas.optDouble(args, "min_ratio", DEFAULT_RATIO));
        int minVideos   = Math.max(2, Schemas.optInt(args, "min_videos", 2));
        int limit       = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            List<Row> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT t.id AS title_id,
                           t.code AS code,
                           COUNT(*) AS video_count,
                           MIN(v.size_bytes) AS min_size,
                           MAX(v.size_bytes) AS max_size,
                           1.0 * MAX(v.size_bytes) / NULLIF(MIN(v.size_bytes), 0) AS ratio,
                           GROUP_CONCAT(v.filename, '|') AS filenames,
                           GROUP_CONCAT(v.size_bytes, '|') AS sizes
                    FROM titles t
                    JOIN videos v ON v.title_id = t.id
                    GROUP BY t.id, t.code
                    HAVING COUNT(*) >= :minVideos
                       AND COUNT(*) = COUNT(v.size_bytes)
                       AND MIN(v.size_bytes) > 0
                       AND (1.0 * MAX(v.size_bytes) / MIN(v.size_bytes)) >= :minRatio
                    ORDER BY ratio DESC, video_count DESC, t.code
                    LIMIT :limit
                    """)
                    .bind("minVideos", minVideos)
                    .bind("minRatio",  minRatio)
                    .bind("limit",     limit)
                    .map((rs, ctx) -> new Row(
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            rs.getInt("video_count"),
                            rs.getLong("min_size"),
                            rs.getLong("max_size"),
                            rs.getDouble("ratio"),
                            splitPipe(rs.getString("filenames")),
                            splitPipeLong(rs.getString("sizes"))))
                    .forEach(rows::add);
            return new Result(rows.size(), rows);
        });
    }

    private static List<String> splitPipe(String s) {
        if (s == null || s.isEmpty()) return List.of();
        return List.of(s.split("\\|"));
    }

    private static List<Long> splitPipeLong(String s) {
        if (s == null || s.isEmpty()) return List.of();
        List<Long> out = new ArrayList<>();
        for (String t : s.split("\\|")) out.add(Long.parseLong(t));
        return out;
    }

    public record Row(long titleId, String code, int videoCount,
                      long minSizeBytes, long maxSizeBytes, double sizeRatio,
                      List<String> filenames, List<Long> sizesBytes) {}
    public record Result(int count, List<Row> candidates) {}
}
