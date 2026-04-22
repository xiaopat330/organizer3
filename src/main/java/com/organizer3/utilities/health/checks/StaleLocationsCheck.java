package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolls up stale {@code title_locations} rows across all volumes. The per-volume Clean action
 * lives on the Volumes screen; Library Health only reports the aggregate + per-volume counts,
 * and routes the user there for the fix.
 */
public final class StaleLocationsCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final Jdbi jdbi;

    public StaleLocationsCheck(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String id() { return "stale_locations"; }
    @Override public String label() { return "Stale title locations"; }
    @Override public String description() {
        return "Title-location rows whose file wasn't seen on the latest sync of that volume.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.VOLUMES_SCREEN; }

    @Override
    public CheckResult run() {
        // Per-volume breakdown — one finding row per volume with stale rows. The UI groups
        // by count and sends the user to the Volumes screen to run the actual Clean action.
        List<Finding> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT tl.volume_id AS vol, COUNT(*) AS n
                        FROM title_locations tl
                        JOIN volumes v ON v.id = tl.volume_id
                        WHERE v.last_synced_at IS NOT NULL
                          AND tl.last_seen_at < v.last_synced_at
                        GROUP BY tl.volume_id
                        ORDER BY n DESC
                        LIMIT :lim
                        """)
                .bind("lim", SAMPLE_LIMIT)
                .map((rs, ctx) -> new Finding(
                        rs.getString("vol"),
                        "Volume " + rs.getString("vol").toUpperCase(),
                        rs.getInt("n") + " stale row(s)"))
                .list());
        int total = jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM title_locations tl
                        JOIN volumes v ON v.id = tl.volume_id
                        WHERE v.last_synced_at IS NOT NULL
                          AND tl.last_seen_at < v.last_synced_at
                        """).mapTo(Integer.class).one());
        return new CheckResult(total, new ArrayList<>(rows));
    }
}
