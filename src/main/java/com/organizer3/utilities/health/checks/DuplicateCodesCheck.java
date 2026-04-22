package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Titles that have more than one {@code title_locations} row on the <em>same</em> volume.
 * Cross-volume duplicates are expected in a multi-NAS library and are not flagged. Within
 * a single volume, multiple locations for one title usually means an accidental copy or a
 * mis-folder — human judgement is required to pick the canonical copy, so Phase 1 is
 * surface-only.
 */
public final class DuplicateCodesCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final Jdbi jdbi;

    public DuplicateCodesCheck(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String id() { return "duplicate_codes"; }
    @Override public String label() { return "Duplicate codes (intra-volume)"; }
    @Override public String description() {
        return "Titles with more than one file location on the same volume.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        List<Finding> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT t.id AS tid, t.code AS code, tl.volume_id AS vol, COUNT(*) AS n
                        FROM title_locations tl
                        JOIN titles t ON t.id = tl.title_id
                        GROUP BY tl.title_id, tl.volume_id
                        HAVING n > 1
                        ORDER BY n DESC, t.code
                        LIMIT :lim
                        """)
                .bind("lim", SAMPLE_LIMIT)
                .map((rs, ctx) -> new Finding(
                        rs.getLong("tid") + "@" + rs.getString("vol"),
                        rs.getString("code"),
                        rs.getInt("n") + " copies on volume " + rs.getString("vol").toUpperCase()))
                .list());
        int total = jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM (
                            SELECT 1 FROM title_locations
                            GROUP BY title_id, volume_id
                            HAVING COUNT(*) > 1
                        )
                        """).mapTo(Integer.class).one());
        return new CheckResult(total, rows);
    }
}
