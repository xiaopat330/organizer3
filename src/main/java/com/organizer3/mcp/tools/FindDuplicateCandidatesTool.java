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
 * <p>Only considers titles with ≥2 videos where all videos have probed metadata. Titles
 * with any unprobed video are silently skipped (use {@code probe videos} first).
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
             + "same-content quality variants rather than a legitimate multi-part set.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("duration_tolerance_sec", "integer",
                        "Max spread (max-min) between video durations. Default 30s.", DEFAULT_TOLERANCE)
                .prop("limit", "integer", "Maximum candidate titles. Default 100, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int toleranceSec = Math.max(1, Schemas.optInt(args, "duration_tolerance_sec", DEFAULT_TOLERANCE));
        int limit        = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            List<Row> rows = new ArrayList<>();
            // GROUP_CONCAT gathers the per-video facts so the agent has enough context
            // without a drill-down round-trip. We require non-null duration on every
            // row (COUNT(*) = COUNT(duration_sec)) so partially-probed titles don't
            // fire false positives.
            h.createQuery("""
                    SELECT t.id AS title_id,
                           t.code AS code,
                           COUNT(*) AS video_count,
                           MAX(v.duration_sec) - MIN(v.duration_sec) AS duration_spread,
                           MAX(v.duration_sec) AS max_dur,
                           GROUP_CONCAT(v.filename, '|') AS filenames,
                           GROUP_CONCAT(COALESCE(v.video_codec, '?') || '@'
                                       || COALESCE(v.width || 'x' || v.height, '?'), '|') AS encodes
                    FROM titles t
                    JOIN videos v ON v.title_id = t.id
                    GROUP BY t.id, t.code
                    HAVING COUNT(*) >= 2
                       AND COUNT(*) = COUNT(v.duration_sec)
                       AND (MAX(v.duration_sec) - MIN(v.duration_sec)) <= :tolerance
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
                            splitPipe(rs.getString("filenames")),
                            splitPipe(rs.getString("encodes"))))
                    .forEach(rows::add);
            return new Result(rows.size(), rows);
        });
    }

    private static List<String> splitPipe(String s) {
        if (s == null || s.isEmpty()) return List.of();
        return List.of(s.split("\\|"));
    }

    public record Row(long titleId, String code, int videoCount, long durationSpreadSec,
                      long durationSec, List<String> filenames, List<String> encodes) {}
    public record Result(int count, List<Row> candidates) {}
}
