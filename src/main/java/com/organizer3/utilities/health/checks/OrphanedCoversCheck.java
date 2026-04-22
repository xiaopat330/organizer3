package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.covers.OrphanedCoversService;
import com.organizer3.utilities.health.LibraryHealthCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports orphaned covers — image files under the local covers cache with no matching title.
 * Delegates the scan to {@link OrphanedCoversService} so the same predicate drives both the
 * health check preview and the bulk cleanup task.
 */
public final class OrphanedCoversCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final OrphanedCoversService service;

    public OrphanedCoversCheck(OrphanedCoversService service) {
        this.service = service;
    }

    @Override public String id() { return "orphaned_covers"; }
    @Override public String label() { return "Orphaned covers"; }
    @Override public String description() {
        return "Cover image files on disk with no matching title in the database.";
    }
    // The CleanOrphanedCoversTask runs inline on this screen — no cross-screen hop needed.
    @Override public FixRouting fixRouting() { return FixRouting.INLINE; }

    @Override
    public CheckResult run() {
        OrphanedCoversService.OrphanPreview pv = service.preview();
        List<Finding> sample = new ArrayList<>();
        for (OrphanedCoversService.OrphanRow row : pv.rows()) {
            if (sample.size() >= SAMPLE_LIMIT) break;
            sample.add(new Finding(
                    row.label() + "/" + row.filename(),
                    row.absolutePath(),
                    "local cover cache · " + formatSize(row.sizeBytes())
                            + " · safe to delete (no DB row for " + row.baseCode() + ")"));
        }
        return new CheckResult(pv.count(), sample);
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
