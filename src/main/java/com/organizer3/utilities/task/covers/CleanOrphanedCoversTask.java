package com.organizer3.utilities.task.covers;

import com.organizer3.utilities.covers.OrphanedCoversService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Bulk-delete every orphaned cover under the local covers cache. Atomic under the task lock,
 * re-evaluates the predicate server-side so a stale client preview can't widen the delete set.
 *
 * <p>Inputs are empty — the task always operates on the full orphan set the service finds.
 * The visualize-then-confirm preview is served by {@link OrphanedCoversService#preview()}
 * via the routes layer; this task just runs the delete.
 */
public final class CleanOrphanedCoversTask implements Task {

    public static final String ID = "covers.clean_orphaned";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Clean orphaned covers",
            "Delete cover image files in the local cache that have no matching title.",
            List.of()
    );

    private final OrphanedCoversService service;

    public CleanOrphanedCoversTask(OrphanedCoversService service) {
        this.service = service;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("delete", "Delete orphaned covers");
        try {
            OrphanedCoversService.DeleteResult result = service.delete();
            String summary = result.deleted() + " deleted · " + formatSize(result.bytesFreed()) + " freed"
                    + (result.failed() > 0 ? " · " + result.failed() + " failed" : "");
            io.phaseEnd("delete", result.failed() > 0 ? "failed" : "ok", summary);
        } catch (RuntimeException e) {
            io.phaseLog("delete", "Delete failed: " + e.getMessage());
            io.phaseEnd("delete", "failed", e.getMessage());
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
