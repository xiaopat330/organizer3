package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolls up stale {@code title_locations} rows across all volumes. The per-volume Clean action
 * lives on the Volumes screen; Library Health only reports the aggregate + per-volume counts,
 * and routes the user there for the fix.
 *
 * <p><b>Relationship to {@code PendingGraceLocationsCheck}:</b> This check uses the
 * <em>pre-grace-period</em> staleness definition — a row is "stale" when its
 * {@code last_seen_at} is older than the volume's {@code last_synced_at}. This is a
 * filesystem-sync signal (the file wasn't observed on the last sweep) and predates the
 * grace-period mechanism introduced with the {@code stale_since} column.
 *
 * <p>{@code PendingGraceLocationsCheck} uses the newer definition: {@code stale_since IS NOT NULL},
 * which is set atomically by the sync path and cleared on re-observation. The two checks measure
 * overlapping but distinct things and are intentionally kept separate. Do not retire this check
 * without auditing {@link com.organizer3.utilities.volume.StaleLocationsService} and the
 * Volumes-screen "Clean" action that depends on it.
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
                          AND tl.last_seen_at < DATE(v.last_synced_at)
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
                          AND tl.last_seen_at < DATE(v.last_synced_at)
                        """).mapTo(Integer.class).one());
        return new CheckResult(total, new ArrayList<>(rows));
    }
}
