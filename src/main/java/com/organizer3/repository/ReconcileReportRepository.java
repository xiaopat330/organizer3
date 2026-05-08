package com.organizer3.repository;

import com.organizer3.sync.ReconcileReport;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for persisted reconcile report rows.
 *
 * <p>Each run of {@link com.organizer3.sync.ReconcileService} saves a summary row here so
 * the admin can see the results of overnight coherent-sync runs the next morning.
 * The full detail lists (verbose output) are stored as JSON in {@code detail_json}.
 */
public interface ReconcileReportRepository {

    /**
     * Persist a report row.
     *
     * @param report      the generated report
     * @param triggeredBy {@code "manual"} or {@code "coherent_sync"}
     * @param detailJson  serialized detail lists (nullable; only retained in verbose mode)
     * @return the generated row id
     */
    long save(ReconcileReport report, String triggeredBy, String detailJson);

    /**
     * Return the {@code limit} most recent persisted reports, newest first.
     */
    List<PersistedReport> findRecent(int limit);

    /**
     * Find a single persisted report by its row id.
     */
    Optional<PersistedReport> findById(long id);

    /**
     * Find the most recent persisted report where {@code triggered_by} matches the given value.
     * Returns {@link Optional#empty()} if no matching row exists.
     *
     * @param triggeredBy {@code "manual"} or {@code "coherent_sync"}
     */
    Optional<PersistedReport> findLastByTrigger(String triggeredBy);

    /**
     * A persisted reconcile report row — scalar summary plus raw JSON details.
     */
    record PersistedReport(
            long id,
            String generatedAt,
            int duplicateLiveLocations,
            int pendingGrace,
            int oldestPendingGraceDays,
            int pastGraceStragglers,
            int actressFolderMismatches,
            String triggeredBy,
            String detailJson
    ) {}
}
