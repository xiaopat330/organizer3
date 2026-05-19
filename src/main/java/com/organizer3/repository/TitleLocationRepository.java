package com.organizer3.repository;

import com.organizer3.model.TitleLocation;
import org.jdbi.v3.core.Handle;

import java.nio.file.Path;
import java.util.List;

/**
 * Persistence operations for {@link TitleLocation} records.
 *
 * <h3>Stale-row semantics (Phase 1 — grace-period orphans)</h3>
 * <p>Every row has a {@code stale_since} column. {@code NULL} = live (observed on the most
 * recent sync of its scope). Non-null = the ISO-8601 timestamp at which the row was first
 * marked absent. Rows past {@code sync.staleGraceDays} days are swept by
 * {@link #sweepStaleOlderThan(int)}.
 *
 * <p>All default read methods ({@link #findByTitle}, {@link #findByVolume}, etc.) hide stale
 * rows (i.e. {@code WHERE stale_since IS NULL}) unless the caller passes {@code includeStale=true}.
 * The predicate is centralized in the JDBI base SQL — callers do not scatter it.
 */
public interface TitleLocationRepository {

    /**
     * Upsert a location row. On conflict (same title_id, volume_id, path), clears
     * {@code stale_since} back to NULL and refreshes {@code last_seen_at} — re-observing a
     * path makes it live again. This is the write path used by the sync scanner.
     */
    TitleLocation save(TitleLocation location);

    /**
     * Returns live locations for the given title (stale rows excluded by default).
     * Pass {@code includeStale=true} to include grace-period stale rows (e.g. reconcile).
     */
    List<TitleLocation> findByTitle(long titleId);
    List<TitleLocation> findByTitle(long titleId, boolean includeStale);

    /**
     * Batch variant of {@link #findByTitle} — live rows only for the given title ids.
     * Pass {@code includeStale=true} to include grace-period stale rows.
     */
    List<TitleLocation> findByTitleIds(List<Long> titleIds);
    List<TitleLocation> findByTitleIds(List<Long> titleIds, boolean includeStale);

    /**
     * Returns live locations on the given volume (stale rows excluded by default).
     * Pass {@code includeStale=true} to include grace-period stale rows.
     */
    List<TitleLocation> findByVolume(String volumeId);
    List<TitleLocation> findByVolume(String volumeId, boolean includeStale);

    // -------------------------------------------------------------------------
    // Sync-path: mark-stale (replaces delete in the sync pipeline)
    // -------------------------------------------------------------------------

    /**
     * Marks all rows for the given volume stale, setting {@code stale_since = nowIso} for rows
     * that don't already have a value. Idempotent — an already-stale row keeps its original
     * {@code stale_since}.
     *
     * <p>Replaces {@link #deleteByVolume} in the sync path. The delete variant is kept for
     * tests and explicit admin cleanup but is deprecated for sync use.
     *
     * @return number of rows newly marked stale (already-stale rows not counted)
     */
    int markStaleByVolume(String volumeId, String nowIso);

    /**
     * Marks stale all rows for the given volume + partition, leaving other partitions untouched.
     * Idempotent — already-stale rows keep their original {@code stale_since}.
     *
     * <p>Replaces {@link #deleteByVolumeAndPartition} in the partition-sync path.
     *
     * @return number of rows newly marked stale
     */
    int markStaleByVolumeAndPartition(String volumeId, String partitionId, String nowIso);

    // -------------------------------------------------------------------------
    // Sweep
    // -------------------------------------------------------------------------

    /**
     * Returns all rows whose {@code stale_since} is older than {@code graceDays} days.
     * Used by the catastrophic-delete guard before the sweep.
     */
    List<TitleLocation> findStaleOlderThan(int graceDays);

    /**
     * Deletes rows whose {@code stale_since} is older than {@code graceDays} days.
     * Returns the number of rows deleted.
     *
     * <p>Catastrophic-delete guard: the caller is responsible for checking
     * {@link #findStaleOlderThan} before calling this, but the implementation also
     * enforces the threshold internally for defense-in-depth.
     */
    int sweepStaleOlderThan(int graceDays);

    /**
     * Returns the total count of live (non-stale) title_location rows across all volumes.
     * Used as the denominator for the catastrophic-delete guard in the stale-row sweep.
     */
    int countAllLive();

    /**
     * Returns the count of rows on the given volume that are still stale with
     * {@code stale_since = markedAtIso}. Used after a scan to compute {@code staleCleared}:
     * rows that were marked at {@code markedAtIso} but NOT re-observed (i.e. still stale).
     *
     * @param volumeId     the volume that was just synced
     * @param markedAtIso  the ISO-8601 timestamp passed to {@link #markStaleByVolume}
     */
    int countStaleMarkedAt(String volumeId, String markedAtIso);

    /**
     * Returns count and oldest age (in days) of stale rows still inside the grace window.
     * Used by Library Health.
     */
    PendingGraceSummary countPendingGrace();

    record PendingGraceSummary(int count, int oldestDays) {
        public static final PendingGraceSummary EMPTY = new PendingGraceSummary(0, 0);
    }

    // -------------------------------------------------------------------------
    // Hard-delete variants (kept for tests / explicit admin cleanup)
    // -------------------------------------------------------------------------

    /** @deprecated Use {@link #markStaleByVolume} in the sync path. Hard-delete kept for admin/test use. */
    @Deprecated
    void deleteByVolume(String volumeId);

    /** @deprecated Use {@link #markStaleByVolumeAndPartition} in the sync path. Hard-delete kept for admin/test use. */
    @Deprecated
    void deleteByVolumeAndPartition(String volumeId, String partitionId);

    /**
     * Find a single {@code title_locations} row by its primary key.
     * Returns {@link java.util.Optional#empty()} if no row exists with that id.
     * Includes stale rows (the caller determines whether the row is eligible for actions).
     */
    java.util.Optional<TitleLocation> findById(long locationId);

    /** Delete a single title_location row. No-op if the id does not exist. */
    void deleteById(long locationId);

    /**
     * Update an existing row's {@code path} and {@code partition_id} after a sort-phase
     * move. No-op if the row does not exist. Does not touch {@code last_seen_at} or
     * {@code added_date} — those reflect discovery state, not filing state.
     */
    void updatePathAndPartition(long locationId, java.nio.file.Path newPath, String newPartitionId);

    /** Update path only within an existing handle/transaction (for use inside recode transactions). */
    void updatePath(long locationId, Path newPath, Handle h);

    // -------------------------------------------------------------------------
    // Reconcile finder methods — read-only, used by ReconcileService
    // -------------------------------------------------------------------------

    /**
     * Returns titles that have more than one live {@code title_locations} row across volumes.
     * A single title with two live rows on different volumes is the smoking gun for an unsynced
     * source volume after a cross-volume move.
     *
     * <p>Only live rows ({@code stale_since IS NULL}) are considered.
     */
    List<DuplicateLiveLocation> findDuplicateLiveLocations();

    /**
     * Returns stale rows that are still within the grace window (i.e. stale but not yet past grace).
     * These are titles in limbo — no live location but not yet prunable.
     *
     * @param graceDays the configured grace period (e.g. 90)
     */
    List<PendingGraceRow> findPendingGrace(int graceDays);

    /**
     * Returns stale rows that are past the grace window — they would be swept on the next sync.
     *
     * @param graceDays the configured grace period (e.g. 90)
     */
    List<PendingGraceRow> findPastGraceStragglers(int graceDays);

    /**
     * A title that has more than one live location row across volumes.
     *
     * @param titleId   the title's database id
     * @param code      the title's product code
     * @param locations the live location rows (at least 2)
     */
    record DuplicateLiveLocation(
            long titleId,
            String code,
            List<LocationTuple> locations
    ) {
        public record LocationTuple(long locationId, String volumeId, String partitionId, String path) {}
    }

    /**
     * A single stale {@code title_locations} row, used in reconcile pending-grace and
     * past-grace reports.
     */
    record PendingGraceRow(
            long locationId,
            long titleId,
            String code,
            String volumeId,
            String path,
            String staleSinceIso,
            int daysStale
    ) {}
}
