package com.organizer3.sync;

import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleLocationRepository.DuplicateLiveLocation;
import com.organizer3.repository.TitleLocationRepository.PendingGraceRow;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleRepository.ActressMismatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** Returns the configured grace-period days used by this service. */
    public int graceDays() { return graceDays; }

    // -------------------------------------------------------------------------
    // Single-row sweep (drilldown endpoint)
    // -------------------------------------------------------------------------

    /**
     * Attempts to delete a single {@code title_locations} row by id. The row is only deleted
     * if it exists and its {@code stale_since} is past the grace window.
     *
     * @param locationId the primary key of the row to sweep
     * @return a {@link SweepRowResult} discriminating 404 / 409-in-grace / 200-deleted
     */
    public SweepRowResult sweepRow(long locationId) {
        Optional<TitleLocation> found = locationRepo.findById(locationId);
        if (found.isEmpty()) {
            return new SweepRowResult.NotFound();
        }
        TitleLocation loc = found.get();

        if (loc.getStaleSince() == null) {
            // Row has no stale_since — not eligible
            return new SweepRowResult.InGrace(null, graceDays);
        }

        long staleDays = Duration.between(loc.getStaleSince(), Instant.now()).toDays();
        if (staleDays < graceDays) {
            return new SweepRowResult.InGrace((int) staleDays, graceDays);
        }

        locationRepo.deleteById(locationId);
        log.info("Drilldown sweep: deleted past-grace row id={} titleId={} volume={} path={}",
                locationId, loc.getTitleId(), loc.getVolumeId(), loc.getPath());
        return new SweepRowResult.Deleted(loc.getTitleId(), loc.getVolumeId(), loc.getPath().toString());
    }

    /** Sealed result type for {@link #sweepRow(long)}. */
    public sealed interface SweepRowResult {
        record Deleted(long titleId, String volumeId, String path) implements SweepRowResult {}
        record NotFound() implements SweepRowResult {}
        /** staleDays is null when stale_since is null (not stale at all). */
        record InGrace(Integer staleDays, int graceDays) implements SweepRowResult {}
    }

    // -------------------------------------------------------------------------
    // Trust-volume (drilldown endpoint)
    // -------------------------------------------------------------------------

    /**
     * Validates the trust-volume request and identifies the other volume that should be synced.
     *
     * <p>Does NOT trigger the sync — that is the caller's responsibility (the route layer holds
     * the TaskRunner). Returns a {@link TrustVolumeResult} describing what should happen.
     *
     * @param titleId       the title with duplicate live locations
     * @param trustVolumeId the volume the user is asserting as canonical
     * @return a {@link TrustVolumeResult} discriminating not-found / too-few-locations /
     *         trust-volume-not-found / too-many-volumes / ok
     */
    public TrustVolumeResult resolveTrustVolume(long titleId, String trustVolumeId) {
        // Live locations only (stale_since IS NULL)
        List<TitleLocation> liveLocations = locationRepo.findByTitle(titleId);

        if (liveLocations.isEmpty()) {
            // Check if the title exists at all (stale-only or truly not-found)
            List<TitleLocation> all = locationRepo.findByTitle(titleId, true);
            return all.isEmpty()
                    ? new TrustVolumeResult.TitleNotFound()
                    : new TrustVolumeResult.InsufficientLocations(all.size());
        }

        long distinctVolumes = liveLocations.stream().map(TitleLocation::getVolumeId).distinct().count();

        if (distinctVolumes < 2) {
            return new TrustVolumeResult.InsufficientLocations((int) distinctVolumes);
        }
        if (distinctVolumes > 2) {
            return new TrustVolumeResult.TooManyVolumes((int) distinctVolumes);
        }

        // Exactly 2 distinct volumes
        boolean trustVolPresent = liveLocations.stream().anyMatch(l -> trustVolumeId.equals(l.getVolumeId()));
        if (!trustVolPresent) {
            return new TrustVolumeResult.TrustVolumeNotInLocations();
        }

        TitleLocation otherLoc = liveLocations.stream()
                .filter(l -> !trustVolumeId.equals(l.getVolumeId()))
                .findFirst()
                .orElseThrow(); // safe: we have exactly 2 distinct volumes
        return new TrustVolumeResult.Ok(otherLoc.getVolumeId(), otherLoc.getPartitionId());
    }

    /** Sealed result type for {@link #resolveTrustVolume(long, String)}. */
    public sealed interface TrustVolumeResult {
        record Ok(String otherVolumeId, String otherPartitionId) implements TrustVolumeResult {}
        record TitleNotFound() implements TrustVolumeResult {}
        record InsufficientLocations(int liveVolumeCount) implements TrustVolumeResult {}
        record TrustVolumeNotInLocations() implements TrustVolumeResult {}
        record TooManyVolumes(int volumeCount) implements TrustVolumeResult {}
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
