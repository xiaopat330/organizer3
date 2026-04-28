package com.organizer3.utilities.task.actress;

import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Re-apply the {@code grade} field from every actress YAML to existing DB titles. Lighter-weight
 * than the full {@code actress.load_all} task: does not touch title_original, release_date,
 * notes, or tags, and does not create stubs for missing codes. Used to repair grade staleness
 * after new titles have been imported by volume sync since the last full load.
 */
public final class SyncYamlGradesTask implements Task {

    public static final String ID = "actress.sync_yaml_grades";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Sync YAML grades",
            "Reapply portfolio grades from every actress YAML to matching DB titles. Skips manual grades.",
            List.of()
    );

    private final ActressYamlLoader loader;

    public SyncYamlGradesTask(ActressYamlLoader loader) {
        this.loader = loader;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("sync_grades", "Sync YAML grades");
        List<String> slugs;
        try {
            slugs = loader.listSlugs();
        } catch (Exception e) {
            io.phaseLog("sync_grades", "Failed to enumerate YAMLs: " + e.getMessage());
            io.phaseEnd("sync_grades", "failed", e.getMessage());
            return;
        }

        int total = slugs.size();
        int done = 0;
        int succeeded = 0;
        int failed = 0;
        int totalScanned = 0;
        int totalWritten = 0;
        int totalMissingTitle = 0;
        int totalNoGrade = 0;

        boolean cancelled = false;
        for (String slug : slugs) {
            if (io.isCancellationRequested()) {
                cancelled = true;
                io.phaseLog("sync_grades", "Cancellation requested — stopping after " + done + " of " + total + ".");
                break;
            }
            io.phaseProgress("sync_grades", done, total, "processing " + slug);
            try {
                ActressYamlLoader.SyncGradesResult r = loader.syncGradesFromYaml(slug);
                succeeded++;
                totalScanned += r.scanned();
                totalWritten += r.written();
                totalMissingTitle += r.missingTitle();
                totalNoGrade += r.noGrade();
                if (r.written() > 0 || r.missingTitle() > 0) {
                    io.phaseLog("sync_grades", slug + ": wrote " + r.written()
                            + " · " + r.missingTitle() + " missing · " + r.noGrade() + " ungraded");
                }
            } catch (Exception e) {
                failed++;
                io.phaseLog("sync_grades", "Failed " + slug + ": " + e.getMessage());
            }
            done++;
            io.phaseProgress("sync_grades", done, total, "processed " + slug);
        }

        String summary = succeeded + " of " + total + " YAMLs · "
                + totalWritten + " grades written · "
                + totalMissingTitle + " missing titles · "
                + totalNoGrade + " ungraded entries"
                + (failed > 0 ? " · " + failed + " failed" : "")
                + (cancelled ? " · cancelled" : "");
        String status = (total == 0) ? "ok"
                : (succeeded == 0 && !cancelled) ? "failed"
                : "ok";
        io.phaseEnd("sync_grades", status, summary);
    }
}
