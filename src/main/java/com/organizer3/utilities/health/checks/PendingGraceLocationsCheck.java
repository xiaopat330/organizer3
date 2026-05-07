package com.organizer3.utilities.health.checks;

import com.organizer3.config.AppConfig;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;

import java.util.List;

/**
 * Reports {@code title_locations} rows that are stale (not observed on the most recent
 * sync of their scope) but still within the grace window ({@code sync.staleGraceDays}).
 *
 * <p>These represent titles that may have been moved to another volume — they are not yet
 * orphaned. The count surfaces so the admin can see how many rows are in limbo and how
 * long they've been there.
 *
 * <p>Distinct from {@link StaleLocationsCheck}, which uses the old derivation
 * ({@code last_seen_at < volume.last_synced_at}) and reports the pre-grace-period concept.
 * This check uses the new {@code stale_since} column.
 */
public final class PendingGraceLocationsCheck implements LibraryHealthCheck {

    private final TitleLocationRepository locationRepo;

    public PendingGraceLocationsCheck(TitleLocationRepository locationRepo) {
        this.locationRepo = locationRepo;
    }

    @Override public String id()          { return "stale_locations_pending_grace"; }
    @Override public String label()       { return "Stale locations awaiting confirmation"; }
    @Override public String description() {
        int graceDays = AppConfig.get().volumes().syncOrDefaults().staleGraceDaysOrDefault();
        return "Title-location rows marked absent by a sync but within the " + graceDays
                + "-day grace window. These are not yet orphaned — re-syncing the volume "
                + "they moved to will clear them.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        TitleLocationRepository.PendingGraceSummary summary = locationRepo.countPendingGrace();
        if (summary.count() == 0) {
            return CheckResult.empty();
        }
        int graceDays = AppConfig.get().volumes().syncOrDefaults().staleGraceDaysOrDefault();
        String detail = summary.count() + " row(s); oldest " + summary.oldestDays()
                + " day(s) (grace: " + graceDays + " days)";
        List<Finding> findings = List.of(new Finding("pending_grace", "Pending grace", detail));
        return new CheckResult(summary.count(), findings);
    }
}
