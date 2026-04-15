package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Find title locations that appear stale relative to their volume's sync state — the
 * location's {@code last_seen_at} is older than the volume's {@code last_synced_at},
 * meaning the file wasn't observed during the most recent sync and probably no longer
 * exists on disk.
 *
 * <p>Volumes that have never been synced ({@code last_synced_at IS NULL}) are skipped —
 * there's no baseline to compare against.
 */
public class FindStaleLocationsTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 5000;

    private final Jdbi jdbi;

    public FindStaleLocationsTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_stale_locations"; }
    @Override public String description() {
        return "Find title_location rows whose last_seen_at is older than their volume's last_synced_at — "
             + "the file wasn't observed during the most recent sync and probably no longer exists.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volume_id", "string",  "Restrict results to a single volume. Optional.")
                .prop("limit",     "integer", "Maximum locations to return. Default 200, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.optString(args, "volume_id", null);
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            StringBuilder sql = new StringBuilder("""
                    SELECT tl.id, tl.title_id, t.code, tl.volume_id, tl.path,
                           tl.last_seen_at, v.last_synced_at
                    FROM title_locations tl
                    JOIN volumes v ON v.id = tl.volume_id
                    JOIN titles  t ON t.id = tl.title_id
                    WHERE v.last_synced_at IS NOT NULL
                      AND tl.last_seen_at < v.last_synced_at
                    """);
            if (volumeId != null && !volumeId.isBlank()) {
                sql.append("  AND tl.volume_id = :vol\n");
            }
            sql.append("ORDER BY tl.last_seen_at\nLIMIT :lim");

            var q = h.createQuery(sql.toString()).bind("lim", limit);
            if (volumeId != null && !volumeId.isBlank()) q.bind("vol", volumeId);

            List<Row> rows = new ArrayList<>();
            q.map((rs, ctx) -> new Row(
                    rs.getLong("id"),
                    rs.getLong("title_id"),
                    rs.getString("code"),
                    rs.getString("volume_id"),
                    rs.getString("path"),
                    rs.getString("last_seen_at"),
                    rs.getString("last_synced_at")))
             .forEach(rows::add);
            return new Result(rows.size(), rows);
        });
    }

    public record Row(long locationId, long titleId, String titleCode, String volumeId,
                      String path, String lastSeenAt, String volumeLastSyncedAt) {}
    public record Result(int count, List<Row> staleLocations) {}
}
