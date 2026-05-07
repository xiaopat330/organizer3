package com.organizer3.sync;

import com.organizer3.repository.TitleLocationRepository.DuplicateLiveLocation;
import com.organizer3.repository.TitleLocationRepository.PendingGraceRow;
import com.organizer3.repository.TitleRepository.ActressMismatch;

import java.time.Instant;
import java.util.List;

/**
 * Output of a reconcile pass. All four signals are always populated with counts;
 * detail lists are populated only when {@code verbose=true} was requested.
 *
 * <p>Produced by {@link ReconcileService#run(boolean)} and persisted by
 * {@link com.organizer3.repository.ReconcileReportRepository}.
 */
public record ReconcileReport(
        Instant generatedAt,

        /** Number of titles with >1 live location row across volumes. */
        int duplicateLiveLocations,

        /** Number of stale rows still inside the grace window. */
        int pendingGrace,

        /**
         * Age in days of the oldest pending-grace row.
         * 0 when {@code pendingGrace == 0}.
         */
        int oldestPendingGraceDays,

        /** Number of stale rows past the grace window (would be swept on next sync). */
        int pastGraceStragglers,

        /** Number of titles whose live location path doesn't contain the actress's canonical name. */
        int actressFolderMismatches,

        /** Detail list; populated only in verbose mode; otherwise an empty list. */
        List<DuplicateLiveLocation> duplicateLiveDetails,

        /** Detail list; populated only in verbose mode; otherwise an empty list. */
        List<PendingGraceRow> pendingGraceDetails,

        /** Detail list; populated only in verbose mode; otherwise an empty list. */
        List<PendingGraceRow> pastGraceDetails,

        /** Detail list; populated only in verbose mode; otherwise an empty list. */
        List<ActressMismatch> mismatchDetails
) {
    /** Returns true if all four counters are zero — library is fully consistent. */
    public boolean isClean() {
        return duplicateLiveLocations == 0 && pendingGrace == 0
                && pastGraceStragglers == 0 && actressFolderMismatches == 0;
    }
}
