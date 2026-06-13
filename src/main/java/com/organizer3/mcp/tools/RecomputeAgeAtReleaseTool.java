package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.db.AgeAtReleaseRecomputer;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * MCP tool: recompute {@code title_actresses.age_at_release} and surface implausible rows.
 *
 * <p>Live run: calls {@link AgeAtReleaseRecomputer#recomputeAll()}, then fetches implausible
 * rows enriched with title code and actress canonical name.
 *
 * <p>Dry run: reports how many rows differ from the derived value (prospective change count)
 * and the current implausible rows — without writing anything.
 *
 * <p>Result shape:
 * <pre>{@code
 * {
 *   "changedRows":    <int>,
 *   "totalComputable": <int>,
 *   "implausible": [
 *     { "titleId": <long>, "code": <string>, "actressId": <long>, "actressName": <string>, "age": <int> },
 *     ...
 *   ]
 * }
 * }</pre>
 */
@Slf4j
public class RecomputeAgeAtReleaseTool implements Tool {

    private static final String IMPLAUSIBLE_ENRICHED_SQL = """
            SELECT ta.title_id,
                   t.code,
                   ta.actress_id,
                   a.canonical_name,
                   ta.age_at_release
            FROM title_actresses ta
            JOIN titles     t ON t.id = ta.title_id
            JOIN actresses  a ON a.id = ta.actress_id
            WHERE ta.age_at_release IS NOT NULL
              AND (ta.age_at_release < 18 OR ta.age_at_release > 70)
            ORDER BY ta.title_id, ta.actress_id
            """;

    private static final String TOTAL_COMPUTABLE_SQL =
            "SELECT COUNT(*) FROM title_actresses WHERE age_at_release IS NOT NULL";

    private final AgeAtReleaseRecomputer recomputer;
    private final Jdbi jdbi;

    public RecomputeAgeAtReleaseTool(AgeAtReleaseRecomputer recomputer, Jdbi jdbi) {
        this.recomputer = recomputer;
        this.jdbi = jdbi;
    }

    @Override
    public String name() { return "recompute_age_at_release"; }

    @Override
    public String description() {
        return "Recompute title_actresses.age_at_release from actress DOB + title release date. "
             + "Returns changedRows, totalComputable, and all implausible rows (age < 18 or > 70) "
             + "enriched with title code and actress name. Use dry_run=true to preview without writing.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("dry_run", "boolean",
                        "If true (default false), report what WOULD change without writing.", false)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        boolean dryRun = Schemas.optBoolean(args, "dry_run", false);

        int changedRows;
        if (dryRun) {
            changedRows = countProspectiveChanges();
            log.info("recompute_age_at_release dry-run: {} rows would change", changedRows);
        } else {
            changedRows = recomputer.recomputeAll();
            log.info("recompute_age_at_release live: {} rows changed", changedRows);
        }

        int totalComputable = countComputable();
        List<ImplausibleRow> implausible = fetchImplausible();

        return new Result(changedRows, totalComputable, implausible);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Counts rows whose stored {@code age_at_release} would change — equivalently, rows where
     * the stored value differs from the freshly derived value (NULL→non-NULL, non-NULL→NULL,
     * or numeric mismatch). Uses the same derivation logic as {@link AgeAtReleaseRecomputer}.
     */
    private int countProspectiveChanges() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM title_actresses ta
                        LEFT JOIN titles t ON t.id = ta.title_id
                        LEFT JOIN actresses a ON a.id = ta.actress_id
                        LEFT JOIN title_javdb_enrichment e ON e.title_id = t.id
                        WHERE
                            CASE
                                WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                                WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                                ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                                    - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                            END IS NOT ta.age_at_release
                            OR (
                                CASE
                                    WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                                    WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                                    ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                                        - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                                END IS NULL AND ta.age_at_release IS NOT NULL
                            )
                            OR (
                                CASE
                                    WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                                    WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                                    ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                                        - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                                END IS NOT NULL AND ta.age_at_release IS NULL
                            )
                        """)
                        .mapTo(Integer.class)
                        .one());
    }

    private int countComputable() {
        return jdbi.withHandle(h ->
                h.createQuery(TOTAL_COMPUTABLE_SQL).mapTo(Integer.class).one());
    }

    private List<ImplausibleRow> fetchImplausible() {
        return jdbi.withHandle(h ->
                h.createQuery(IMPLAUSIBLE_ENRICHED_SQL)
                        .map((rs, ctx) -> new ImplausibleRow(
                                rs.getLong("title_id"),
                                rs.getString("code"),
                                rs.getLong("actress_id"),
                                rs.getString("canonical_name"),
                                rs.getInt("age_at_release")))
                        .list());
    }

    // ── result types ─────────────────────────────────────────────────────────

    public record ImplausibleRow(
            long titleId,
            String code,
            long actressId,
            String actressName,
            int age) {}

    public record Result(
            int changedRows,
            int totalComputable,
            List<ImplausibleRow> implausible) {}
}
