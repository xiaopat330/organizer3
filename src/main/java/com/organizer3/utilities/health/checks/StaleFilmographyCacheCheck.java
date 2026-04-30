package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Actress filmography caches that are stale (30+ days old) or last-failed (fetch error / 404).
 *
 * <p>Stale caches mean the slug-resolver may be working from out-of-date filmography data,
 * causing false no_match revalidation downgrades. Failed caches prevent revalidation entirely
 * for the affected actress. Both should be refreshed.
 *
 * <p>Entry count is included per row so the user can distinguish a stale-but-rich cache
 * (worth re-fetching to check drift) from a stale-and-empty cache (likely an error to fix).
 */
public final class StaleFilmographyCacheCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final Jdbi jdbi;

    public StaleFilmographyCacheCheck(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String id() { return "filmography_cache_stale"; }
    @Override public String label() { return "Filmography cache — stale or failed"; }
    @Override public String description() {
        return "Actress filmographies that haven't been refreshed in 30+ days or last fetch failed. Re-fetch via actress.refresh_filmography or wait for the next event-driven refresh.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        List<Finding> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT f.actress_slug,
                               f.last_fetch_status,
                               f.fetched_at,
                               (SELECT COUNT(*) FROM javdb_actress_filmography_entry e
                                WHERE e.actress_slug = f.actress_slug) AS entry_count
                        FROM javdb_actress_filmography f
                        WHERE f.last_fetch_status IN ('not_found', 'fetch_failed')
                           OR (f.last_fetch_status = 'ok'
                               AND f.fetched_at < datetime('now', '-30 days'))
                        ORDER BY f.fetched_at ASC NULLS FIRST
                        LIMIT :lim
                        """)
                .bind("lim", SAMPLE_LIMIT)
                .map((rs, ctx) -> new Finding(
                        rs.getString("actress_slug"),
                        rs.getString("actress_slug"),
                        "status=" + rs.getString("last_fetch_status")
                                + " fetched=" + rs.getString("fetched_at")
                                + " entries=" + rs.getInt("entry_count")))
                .list());

        int total = jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM javdb_actress_filmography
                        WHERE last_fetch_status IN ('not_found', 'fetch_failed')
                           OR (last_fetch_status = 'ok'
                               AND fetched_at < datetime('now', '-30 days'))
                        """).mapTo(Integer.class).one());

        return new CheckResult(total, rows);
    }
}
