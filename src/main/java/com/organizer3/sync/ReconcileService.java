package com.organizer3.sync;

import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleLocationRepository.DuplicateLiveLocation;
import com.organizer3.repository.TitleLocationRepository.PendingGraceRow;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleRepository.ActressMismatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Executes the reconcile-only pass: a read-mostly DB examination that detects
 * inconsistencies in {@code title_locations} state and surfaces them as a
 * {@link ReconcileReport}.
 *
 * <p>The four signals are:
 * <ol>
 *   <li><b>Duplicate live locations</b> — same title_id with live rows on &gt;1 volume.
 *       Likely an unsynced source after a cross-volume move.</li>
 *   <li><b>Pending-grace</b> — stale rows still inside the grace window. Titles in limbo.</li>
 *   <li><b>Past-grace stragglers</b> — stale rows past the grace window; swept on next sync.</li>
 *   <li><b>Actress-folder mismatches</b> — live location path doesn't contain the actress's
 *       canonical name.</li>
 * </ol>
 *
 * <p>The optional {@code --sweep} flag (via {@link #sweepPastGraceStragglers()}) drops past-grace
 * rows immediately. A catastrophic-delete guard refuses to drop more than
 * {@code max(50, 10% of live rows)} in a single call.
 */
@Slf4j
@RequiredArgsConstructor
public class ReconcileService {

    /** Max rows to return in the actress-mismatch finder (a single reconcile report). */
    private static final int MISMATCH_LIMIT = 5000;

    private final TitleLocationRepository locationRepo;
    private final TitleRepository titleRepo;
    private final ReconcileReportRepository reportRepo;
    private final int graceDays;

    /**
     * Run the reconcile pass.
     *
     * @param verbose if true, populate the detail lists inside the report; otherwise only counts
     * @return the full report
     */
    public ReconcileReport run(boolean verbose) {
        log.info("Reconcile pass starting (graceDays={} verbose={})", graceDays, verbose);

        List<DuplicateLiveLocation> dupDetails = locationRepo.findDuplicateLiveLocations();
        List<PendingGraceRow> pendingDetails    = locationRepo.findPendingGrace(graceDays);
        List<PendingGraceRow> pastGraceDetails  = locationRepo.findPastGraceStragglers(graceDays);
        List<ActressMismatch> mismatchDetails   = titleRepo.findActressFolderMismatches(MISMATCH_LIMIT);

        int oldestPendingDays = pendingDetails.stream()
                .mapToInt(PendingGraceRow::daysStale)
                .max()
                .orElse(0);

        ReconcileReport report = new ReconcileReport(
                Instant.now(),
                dupDetails.size(),
                pendingDetails.size(),
                oldestPendingDays,
                pastGraceDetails.size(),
                mismatchDetails.size(),
                verbose ? dupDetails : List.of(),
                verbose ? pendingDetails : List.of(),
                verbose ? pastGraceDetails : List.of(),
                verbose ? mismatchDetails : List.of()
        );

        log.info("Reconcile pass complete — dupLive={} pendingGrace={} pastGrace={} mismatches={}",
                report.duplicateLiveLocations(), report.pendingGrace(),
                report.pastGraceStragglers(), report.actressFolderMismatches());

        return report;
    }

    /**
     * Persist a report row (called after {@link #run(boolean)} when the caller wants to store
     * the result for later retrieval via the web UI or MCP).
     *
     * @param report      the report to persist
     * @param triggeredBy {@code "manual"} or {@code "coherent_sync"}
     * @param detailJson  serialized detail JSON (nullable)
     * @return the generated row id
     */
    public long persist(ReconcileReport report, String triggeredBy, String detailJson) {
        return reportRepo.save(report, triggeredBy, detailJson);
    }

    /**
     * Explicitly drop stale rows that are past the grace window immediately, without
     * waiting for the next sync sweep.
     *
     * <p><b>Catastrophic-delete guard:</b> refuses to drop more than
     * {@code max(50, 10% of countAllLive())} rows in a single call. If the guard trips,
     * returns -1 (no rows deleted, no exception thrown).
     *
     * @return number of rows deleted, or {@code -1} if the guard refused
     */
    public int sweepPastGraceStragglers() {
        List<PendingGraceRow> toDelete = locationRepo.findPastGraceStragglers(graceDays);
        if (toDelete.isEmpty()) {
            log.info("Reconcile sweep: no past-grace stragglers to delete");
            return 0;
        }

        int liveCount = locationRepo.countAllLive();
        int threshold = Math.max(50, liveCount / 10);

        if (toDelete.size() > threshold) {
            log.error("Reconcile sweep refused: would delete {} rows but catastrophic-delete guard "
                    + "threshold is {} (live rows={}). Investigate before proceeding.",
                    toDelete.size(), threshold, liveCount);
            return -1;
        }

        int deleted = locationRepo.sweepStaleOlderThan(graceDays);
        log.info("Reconcile sweep: deleted {} past-grace stale rows", deleted);
        return deleted;
    }
}
