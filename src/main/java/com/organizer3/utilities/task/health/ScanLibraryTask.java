package com.organizer3.utilities.task.health;

import com.organizer3.utilities.health.LibraryHealthService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Run every library health check as a single atomic task. Each check becomes a phase so the
 * UI can render per-check progress and populate the per-check counts as they complete.
 *
 * <p>Individual check failures don't fail the task — the service catches and reports per-check
 * errors, and this wrapper marks the failing phase as {@code failed} while continuing with
 * the rest. The terminal task status lands on {@code partial} if any phase failed, or
 * {@code ok} if every check ran.
 */
public final class ScanLibraryTask implements Task {

    public static final String ID = "library.scan";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Scan library",
            "Run all library-health diagnostic checks.",
            List.of()
    );

    private final LibraryHealthService service;

    public ScanLibraryTask(LibraryHealthService service) {
        this.service = service;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        // Report id is independent of the task run id — the service keeps one "latest" report
        // regardless of what triggered the scan (UI, MCP, etc.).
        String reportId = java.util.UUID.randomUUID().toString();
        service.scan(reportId,
                check -> io.phaseStart(check.id(), check.label()),
                (check, result, error) -> {
                    if (error != null) {
                        io.phaseLog(check.id(), "Check failed: " + error.getMessage());
                        io.phaseEnd(check.id(), "failed", error.getMessage());
                    } else {
                        String summary = result.total() == 0
                                ? "no findings"
                                : result.total() + " finding(s)";
                        io.phaseEnd(check.id(), "ok", summary);
                    }
                });
    }
}
