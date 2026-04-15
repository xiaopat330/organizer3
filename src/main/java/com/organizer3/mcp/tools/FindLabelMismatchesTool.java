package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Find titles whose {@code label} column doesn't match the prefix of the {@code code}
 * (the segment before the first dash). A parser bug, a manual edit, or a mis-sync
 * typically produces these — e.g. {@code code='ABP-001'} but {@code label='SNIS'}.
 */
public class FindLabelMismatchesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 5000;

    private final Jdbi jdbi;

    public FindLabelMismatchesTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_label_mismatches"; }
    @Override public String description() {
        return "Find titles where the label column doesn't match the prefix of the code (before the first dash). "
             + "Indicates a parser bug, manual edit, or mis-sync.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Maximum titles to return. Default 200, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        // SQLite's substr/instr lets us extract the code prefix up to first '-' cheaply.
        // Case-insensitive compare since labels in the DB are uppercased but code spellings vary.
        return jdbi.withHandle(h -> {
            List<Row> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT id, code, label,
                           CASE WHEN instr(code, '-') > 0
                                THEN substr(code, 1, instr(code, '-') - 1)
                                ELSE code
                           END AS code_prefix
                    FROM titles
                    WHERE label IS NOT NULL
                      AND code IS NOT NULL
                      AND UPPER(label) != UPPER(CASE WHEN instr(code, '-') > 0
                                                     THEN substr(code, 1, instr(code, '-') - 1)
                                                     ELSE code
                                                END)
                    ORDER BY code
                    LIMIT ?""")
                    .bind(0, limit)
                    .map((rs, ctx) -> new Row(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("label"),
                            rs.getString("code_prefix")))
                    .forEach(rows::add);
            return new Result(rows.size(), rows);
        });
    }

    public record Row(long titleId, String code, String label, String codePrefix) {}
    public record Result(int count, List<Row> titles) {}
}
