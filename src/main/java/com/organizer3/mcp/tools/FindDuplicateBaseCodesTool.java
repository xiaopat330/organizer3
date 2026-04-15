package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Find groups of titles sharing the same {@code base_code} — the normalized form of the
 * catalog code. {@code titles.code} is UNIQUE so exact-code duplicates can't exist, but
 * two titles can land on the same base_code through different spellings (e.g. "ABP-001"
 * vs "ABP-0001" vs "ABP001"). These are near-certainly the same release indexed twice.
 */
public class FindDuplicateBaseCodesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 2000;

    private final Jdbi jdbi;

    public FindDuplicateBaseCodesTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_duplicate_base_codes"; }
    @Override public String description() {
        return "Find groups of titles sharing the same base_code (e.g. ABP-001 vs ABP-0001). "
             + "Near-certainly duplicate indexings of the same release.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Maximum groups to return. Default 200, max 2000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            Map<String, List<TitleRow>> groups = new LinkedHashMap<>();
            h.createQuery("""
                    SELECT id, code, base_code, label
                    FROM titles
                    WHERE base_code IS NOT NULL
                      AND base_code IN (
                          SELECT base_code FROM titles WHERE base_code IS NOT NULL
                          GROUP BY base_code HAVING COUNT(*) > 1
                      )
                    ORDER BY base_code, code
                    LIMIT ?""")
                    .bind(0, limit * 20) // fetch more rows to cover wide groups
                    .map((rs, ctx) -> new Object[] {
                            rs.getString("base_code"),
                            new TitleRow(rs.getLong("id"), rs.getString("code"), rs.getString("label"))
                    })
                    .forEach(o -> groups.computeIfAbsent((String) o[0], k -> new ArrayList<>()).add((TitleRow) o[1]));

            List<Group> out = new ArrayList<>();
            for (Map.Entry<String, List<TitleRow>> e : groups.entrySet()) {
                if (e.getValue().size() < 2) continue;
                out.add(new Group(e.getKey(), e.getValue()));
                if (out.size() >= limit) break;
            }
            return new Result(out.size(), out);
        });
    }

    public record TitleRow(long titleId, String code, String label) {}
    public record Group(String baseCode, List<TitleRow> titles) {}
    public record Result(int count, List<Group> groups) {}
}
