package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate worklist: actresses who have at least one on-disk folder whose path
 * does not contain their canonical name. The usual post-merge cleanup target —
 * titles correctly attributed in the DB, but the containing folder on a volume
 * still reflects an old/folded-away name.
 *
 * <p>The signal is: for each title where {@code titles.actress_id} is set, check
 * whether the {@code title_locations.path} contains the actress's canonical name.
 * If not, that title is a cleanup candidate. Actresses are ranked by the number of
 * mismatched locations descending — the ones who most need Phase 3 folder renames
 * float to the top.
 */
public class ListActressesWithMisnamedFoldersTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT     = 5000;

    private final Jdbi jdbi;

    public ListActressesWithMisnamedFoldersTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "list_actresses_with_misnamed_folders"; }
    @Override public String description() {
        return "List actresses whose filed titles live in folders whose on-disk path doesn't contain "
             + "the actress's canonical name — the post-merge Phase 3 folder-rename worklist. Use "
             + "find_misnamed_folders_for_actress to drill into a specific actress's mismatches.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volume_id", "string",  "Restrict to one volume. Optional.")
                .prop("limit",     "integer", "Maximum actresses to return. Default 100, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.optString(args, "volume_id", null);
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            StringBuilder sql = new StringBuilder("""
                    SELECT a.id, a.canonical_name, COUNT(*) AS mismatched, a.tier
                    FROM titles t
                    JOIN title_locations tl ON tl.title_id = t.id
                    JOIN actresses a        ON a.id = t.actress_id
                    WHERE t.actress_id IS NOT NULL
                      AND a.canonical_name IS NOT NULL
                      AND instr(LOWER(tl.path), LOWER(a.canonical_name)) = 0
                    """);
            if (volumeId != null && !volumeId.isBlank()) {
                sql.append("  AND tl.volume_id = :vol\n");
            }
            sql.append("""
                    GROUP BY a.id, a.canonical_name, a.tier
                    ORDER BY mismatched DESC, a.canonical_name
                    LIMIT :lim
                    """);

            var q = h.createQuery(sql.toString()).bind("lim", limit);
            if (volumeId != null && !volumeId.isBlank()) q.bind("vol", volumeId);

            List<Row> rows = new ArrayList<>();
            q.map((rs, ctx) -> new Row(
                    rs.getLong("id"),
                    rs.getString("canonical_name"),
                    rs.getString("tier"),
                    rs.getInt("mismatched")))
             .forEach(rows::add);
            return new Result(rows.size(), rows);
        });
    }

    public record Row(long actressId, String canonicalName, String tier, int mismatchedLocations) {}
    public record Result(int count, List<Row> actresses) {}
}
