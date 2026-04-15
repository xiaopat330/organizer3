package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Surface titles missing key linkage data — either no on-disk location or no credited
 * actress. Both are common sync-gap signals: a locationless title suggests a removal
 * that didn't clean up the titles row, while an actressless title suggests a code
 * that wasn't attributable during sync.
 */
public class FindOrphanTitlesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 5000;

    private final Jdbi jdbi;

    public FindOrphanTitlesTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_orphan_titles"; }
    @Override public String description() {
        return "Find titles that are missing linkage data: locationless (no title_locations row) "
             + "or actressless (no actress_id and no title_actresses row). Returns two lists in one call.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Maximum titles per category. Default 200, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            List<Row> locationless = query(h, """
                    SELECT t.id, t.code, t.label
                    FROM titles t
                    LEFT JOIN title_locations tl ON tl.title_id = t.id
                    WHERE tl.id IS NULL
                    ORDER BY t.code
                    LIMIT ?""", limit);

            List<Row> actressless = query(h, """
                    SELECT t.id, t.code, t.label
                    FROM titles t
                    LEFT JOIN title_actresses ta ON ta.title_id = t.id
                    WHERE t.actress_id IS NULL AND ta.title_id IS NULL
                    ORDER BY t.code
                    LIMIT ?""", limit);

            return new Result(locationless.size(), actressless.size(), locationless, actressless);
        });
    }

    private static List<Row> query(Handle h, String sql, int limit) {
        List<Row> rows = new ArrayList<>();
        h.createQuery(sql).bind(0, limit)
                .map((rs, ctx) -> new Row(rs.getLong("id"), rs.getString("code"), rs.getString("label")))
                .forEach(rows::add);
        return rows;
    }

    public record Row(long titleId, String code, String label) {}
    public record Result(int locationlessCount, int actresslessCount,
                         List<Row> locationless, List<Row> actressless) {}
}
