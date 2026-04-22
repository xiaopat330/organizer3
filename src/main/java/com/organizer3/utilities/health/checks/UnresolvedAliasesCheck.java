package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Rows in {@code actress_aliases} whose {@code actress_id} has no matching actress row.
 * Normally FK constraints prevent this, but SQLite FK enforcement is off by default — and
 * manual deletes or merge operations in the past may have left dangling references. Surface
 * them so the user can delete or repoint from the Aliases editor.
 */
public final class UnresolvedAliasesCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final Jdbi jdbi;

    public UnresolvedAliasesCheck(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String id() { return "unresolved_aliases"; }
    @Override public String label() { return "Unresolved aliases"; }
    @Override public String description() {
        return "Alias rows pointing at an actress id that no longer exists.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.ACTRESS_DATA_SCREEN; }

    @Override
    public CheckResult run() {
        List<Finding> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT aa.actress_id AS aid, aa.alias_name AS alias
                        FROM actress_aliases aa
                        LEFT JOIN actresses a ON a.id = aa.actress_id
                        WHERE a.id IS NULL
                        ORDER BY aa.alias_name
                        LIMIT :lim
                        """)
                .bind("lim", SAMPLE_LIMIT)
                .map((rs, ctx) -> new Finding(
                        rs.getLong("aid") + ":" + rs.getString("alias"),
                        rs.getString("alias"),
                        "points at missing actress_id=" + rs.getLong("aid")))
                .list());
        int total = jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM actress_aliases aa
                        LEFT JOIN actresses a ON a.id = aa.actress_id
                        WHERE a.id IS NULL
                        """).mapTo(Integer.class).one());
        return new CheckResult(total, rows);
    }
}
