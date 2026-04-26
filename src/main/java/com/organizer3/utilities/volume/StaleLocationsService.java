package com.organizer3.utilities.volume;

import com.organizer3.repository.CatastrophicDeleteException;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Finds and removes stale {@code title_locations} rows — rows whose {@code last_seen_at} is older
 * than the owning volume's {@code last_synced_at}. These represent files that used to be on the
 * volume and weren't observed during the most recent sync, so they almost certainly no longer
 * exist on disk.
 *
 * <p>Used in three places:
 * <ul>
 *   <li>{@link VolumeStateService} — count for the health badge.</li>
 *   <li>Preview endpoint — detail rows for the visualize-then-confirm UI.</li>
 *   <li>{@code CleanStaleLocationsTask} — executes the delete, re-querying under the same
 *       predicate so a stale client list can't cause an unintended deletion.</li>
 * </ul>
 *
 * <p>Volumes that have never been synced ({@code last_synced_at IS NULL}) are skipped — there's
 * no baseline to compare against.
 */
public final class StaleLocationsService {

    /**
     * Max rows returned from the preview query. Matches {@code FindStaleLocationsTool}'s MAX so
     * the two surfaces agree on an upper bound. The task's delete operation is not limit-capped —
     * it deletes every matching row.
     */
    private static final int PREVIEW_LIMIT = 5000;

    private final Jdbi jdbi;

    public StaleLocationsService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Count of stale location rows on the given volume. Cheap; safe to call per-volume per-refresh. */
    public int count(String volumeId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM title_locations tl
                        JOIN volumes v ON v.id = tl.volume_id
                        WHERE v.last_synced_at IS NOT NULL
                          AND tl.volume_id = :vol
                          AND tl.last_seen_at < DATE(v.last_synced_at)
                        """)
                .bind("vol", volumeId)
                .mapTo(Integer.class)
                .one());
    }

    /** Preview rows for the UI. Ordered oldest-first (longest-stale shown first). */
    public List<StaleRow> preview(String volumeId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT tl.id AS location_id, tl.title_id, t.code AS title_code,
                               tl.path, tl.last_seen_at, v.last_synced_at AS volume_last_synced_at
                        FROM title_locations tl
                        JOIN volumes v ON v.id = tl.volume_id
                        JOIN titles  t ON t.id = tl.title_id
                        WHERE v.last_synced_at IS NOT NULL
                          AND tl.volume_id = :vol
                          AND tl.last_seen_at < DATE(v.last_synced_at)
                        ORDER BY tl.last_seen_at
                        LIMIT :lim
                        """)
                .bind("vol", volumeId)
                .bind("lim", PREVIEW_LIMIT)
                .map((rs, ctx) -> new StaleRow(
                        rs.getLong("location_id"),
                        rs.getLong("title_id"),
                        rs.getString("title_code"),
                        rs.getString("path"),
                        rs.getString("last_seen_at"),
                        rs.getString("volume_last_synced_at")))
                .list());
    }

    /**
     * Rule 4 cascade-safety threshold. The stale-locations Clean action is per-volume and
     * already narrowly scoped — but the 2026-04-23 incident showed the predicate itself can
     * be wrong. This guard refuses to delete when the predicate would hit more than half of
     * a volume's locations, on the assumption that a legitimate sync only leaves a small
     * tail of stale rows and anything larger is a predicate bug.
     *
     * <p>50% is more conservative than the {@code deleteOrphaned} 25% threshold because
     * per-volume scope narrows the blast radius per call — but a user running Clean on all
     * five volumes in sequence (exactly what happened in the incident) can still wipe the
     * whole catalog, so we don't get more permissive just because we're per-volume.
     */
    public static final int STALE_DELETE_FRACTION_PERCENT = 50;

    /**
     * Delete every stale location on the given volume, re-evaluating the predicate server-side.
     * Returns the number of rows deleted.
     *
     * <p><b>Cascade safety:</b> throws {@link CatastrophicDeleteException} without deleting
     * anything if the predicate would hit more than {@link #STALE_DELETE_FRACTION_PERCENT}%
     * of the volume's locations. Legitimate sync cleanups leave a small tail; anything larger
     * is almost certainly a predicate bug. The 2026-04-23 incident would have been stopped
     * here if this guard had existed then.
     */
    public int delete(String volumeId) {
        return jdbi.inTransaction(h -> {
            int total = h.createQuery("SELECT COUNT(*) FROM title_locations WHERE volume_id = :vol")
                    .bind("vol", volumeId).mapTo(Integer.class).one();
            int stale = h.createQuery("""
                            SELECT COUNT(*) FROM title_locations tl
                            JOIN volumes v ON v.id = tl.volume_id
                            WHERE v.last_synced_at IS NOT NULL
                              AND tl.volume_id = :vol
                              AND tl.last_seen_at < DATE(v.last_synced_at)
                            """)
                    .bind("vol", volumeId).mapTo(Integer.class).one();
            if (stale == 0) return 0;
            // Threshold = (total * PCT) / 100, integer math. For total=1 the threshold is 0,
            // so deleting the one stale row is still allowed — max(1, ...) ensures the guard
            // never blocks a single-row cleanup.
            int threshold = Math.max(1, (total * STALE_DELETE_FRACTION_PERCENT) / 100);
            if (stale > threshold) {
                throw new CatastrophicDeleteException(
                        "stale-locations clean(" + volumeId + ")", stale, total, threshold);
            }
            return h.createUpdate("""
                            DELETE FROM title_locations
                            WHERE volume_id = :vol
                              AND id IN (
                                SELECT tl.id FROM title_locations tl
                                JOIN volumes v ON v.id = tl.volume_id
                                WHERE v.last_synced_at IS NOT NULL
                                  AND tl.volume_id = :vol
                                  AND tl.last_seen_at < DATE(v.last_synced_at)
                              )
                            """)
                    .bind("vol", volumeId)
                    .execute();
        });
    }

    public record StaleRow(long locationId, long titleId, String titleCode,
                           String path, String lastSeenAt, String volumeLastSyncedAt) {}
}
