package com.organizer3.utilities.volume;

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
                          AND tl.last_seen_at < v.last_synced_at
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
                          AND tl.last_seen_at < v.last_synced_at
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
     * Delete every stale location on the given volume, re-evaluating the predicate server-side.
     * Returns the number of rows deleted.
     */
    public int delete(String volumeId) {
        return jdbi.withHandle(h -> h.createUpdate("""
                        DELETE FROM title_locations
                        WHERE volume_id = :vol
                          AND id IN (
                            SELECT tl.id FROM title_locations tl
                            JOIN volumes v ON v.id = tl.volume_id
                            WHERE v.last_synced_at IS NOT NULL
                              AND tl.volume_id = :vol
                              AND tl.last_seen_at < v.last_synced_at
                          )
                        """)
                .bind("vol", volumeId)
                .execute());
    }

    public record StaleRow(long locationId, long titleId, String titleCode,
                           String path, String lastSeenAt, String volumeLastSyncedAt) {}
}
