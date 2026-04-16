package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * List titles that have more than one video file. Candidate pool for the duplicate-vs-set
 * triage — a title with multiple videos is either a legitimate multi-disc/part release or
 * an accidental quality-variant duplication.
 *
 * <p>Output is metadata-light on purpose: just counts and filenames. For the full
 * metadata-based verdict on a single title, use {@code analyze_title_videos}.
 */
public class ListMultiVideoTitlesTool implements Tool {

    private static final int DEFAULT_MIN_VIDEOS = 2;
    private static final int DEFAULT_LIMIT      = 100;
    private static final int MAX_LIMIT          = 5000;

    private final Jdbi jdbi;

    public ListMultiVideoTitlesTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "list_multi_video_titles"; }
    @Override public String description() {
        return "List titles whose video count is ≥ min_videos. Use analyze_title_videos to drill into one.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("min_videos", "integer", "Minimum videos per title. Default 2.", DEFAULT_MIN_VIDEOS)
                .prop("limit",      "integer", "Maximum titles to return. Default 100, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int minVideos = Math.max(2, Schemas.optInt(args, "min_videos", DEFAULT_MIN_VIDEOS));
        int limit     = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            List<Row> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT t.id AS title_id, t.code AS code, COUNT(v.id) AS video_count,
                           GROUP_CONCAT(v.filename, '|') AS filenames
                    FROM titles t
                    JOIN videos v ON v.title_id = t.id
                    GROUP BY t.id, t.code
                    HAVING COUNT(v.id) >= :minVideos
                    ORDER BY video_count DESC, t.code
                    LIMIT :limit
                    """)
                    .bind("minVideos", minVideos)
                    .bind("limit", limit)
                    .map((rs, ctx) -> new Row(
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            rs.getInt("video_count"),
                            splitPipe(rs.getString("filenames"))))
                    .forEach(rows::add);
            return new Result(rows.size(), rows);
        });
    }

    private static List<String> splitPipe(String s) {
        if (s == null || s.isEmpty()) return List.of();
        return List.of(s.split("\\|"));
    }

    public record Row(long titleId, String code, int videoCount, List<String> filenames) {}
    public record Result(int count, List<Row> titles) {}
}
