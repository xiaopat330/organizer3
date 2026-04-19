package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Mass-scan for titles where every video's duration lies within a tight tolerance —
 * strong signal that a title's multiple videos are the same content re-encoded at
 * different bitrates or codecs, not a legitimate multi-part release.
 *
 * <p>Only considers titles with ≥2 videos where all videos have probed duration. Titles
 * with any unprobed video are silently skipped (use {@code probe videos} first).
 *
 * <p>Each candidate's per-video sizes are included in the output when available — an
 * agent can read the size spread together with the duration match to decide whether the
 * videos are redundant re-encodes (large size variance → strongest duplicate signal) or
 * suspect byte-for-byte copies (tiny size variance across identical durations).
 */
public class FindDuplicateCandidatesTool implements Tool {

    private static final int DEFAULT_TOLERANCE = 30;
    private static final int DEFAULT_LIMIT     = 100;
    private static final int MAX_LIMIT         = 5000;

    private final Jdbi jdbi;

    public FindDuplicateCandidatesTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_duplicate_candidates"; }
    @Override public String description() {
        return "Flag titles whose multiple videos all share a near-identical duration — likely "
             + "same-content quality variants rather than a legitimate multi-part set. "
             + "Returns per-video filename, encoding, and size so the agent can rank by "
             + "size variance (big variance = clear quality variants).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("duration_tolerance_sec", "integer",
                        "Max spread (max-min) between video durations. Default 30s.", DEFAULT_TOLERANCE)
                .prop("require_size", "boolean",
                        "If true, only include titles where every video has size_bytes populated. "
                      + "Helps focus on actionable candidates once the backfill has run. Default false.", false)
                .prop("limit", "integer", "Maximum candidate titles. Default 100, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int toleranceSec = Math.max(1, Schemas.optInt(args, "duration_tolerance_sec", DEFAULT_TOLERANCE));
        int limit        = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        boolean requireSize = Schemas.optBoolean(args, "require_size", false);

        return jdbi.withHandle(h -> {
            List<Row> rows = new ArrayList<>();
            // GROUP_CONCAT gathers the per-video facts so the agent has enough context
            // without a drill-down round-trip. We require non-null duration on every
            // row (COUNT(*) = COUNT(duration_sec)) so partially-probed titles don't
            // fire false positives. Size is reported alongside but not required by
            // default — a "-1" sentinel in the size stream means that row has no size.
            String havingSize = requireSize ? " AND COUNT(*) = COUNT(v.size_bytes)" : "";
            h.createQuery("""
                    SELECT t.id AS title_id,
                           t.code AS code,
                           COUNT(*) AS video_count,
                           MAX(v.duration_sec) - MIN(v.duration_sec) AS duration_spread,
                           MAX(v.duration_sec) AS max_dur,
                           COALESCE(MAX(v.size_bytes) - MIN(v.size_bytes), 0) AS size_spread,
                           GROUP_CONCAT(v.filename, '|') AS filenames,
                           GROUP_CONCAT(COALESCE(v.video_codec, '?') || '@'
                                       || COALESCE(v.width || 'x' || v.height, '?'), '|') AS encodes,
                           GROUP_CONCAT(COALESCE(v.size_bytes, -1), '|') AS sizes
                    FROM titles t
                    JOIN videos v ON v.title_id = t.id
                    GROUP BY t.id, t.code
                    HAVING COUNT(*) >= 2
                       AND COUNT(*) = COUNT(v.duration_sec)
                       AND (MAX(v.duration_sec) - MIN(v.duration_sec)) <= :tolerance""" + havingSize + """

                    ORDER BY video_count DESC, t.code
                    LIMIT :limit
                    """)
                    .bind("tolerance", toleranceSec)
                    .bind("limit", limit)
                    .map((rs, ctx) -> new Row(
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            rs.getInt("video_count"),
                            rs.getLong("duration_spread"),
                            rs.getLong("max_dur"),
                            rs.getLong("size_spread"),
                            splitPipe(rs.getString("filenames")),
                            splitPipe(rs.getString("encodes")),
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
        for (String t : s.split("\\|")) {
            long n = Long.parseLong(t);
            out.add(n < 0 ? null : n);
        }
        return out;
    }

    public record Row(long titleId, String code, int videoCount, long durationSpreadSec,
                      long durationSec, long sizeSpreadBytes,
                      List<String> filenames, List<String> encodes, List<Long> sizesBytes) {}
    public record Result(int count, List<Row> candidates) {}
}
