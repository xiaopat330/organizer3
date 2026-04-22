package com.organizer3.utilities.task.actress;

import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Apply every actress YAML discoverable on the classpath, one at a time. Emits per-slug progress
 * so the UI can show "12 / 43 · processing sora_aoi". Per-slug failures are counted and logged
 * but do not halt the run — the task's phase status is {@code ok} if at least one load succeeded
 * and {@code failed} only when none did.
 */
public final class LoadAllActressesTask implements Task {

    public static final String ID = "actress.load_all";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Load all actress YAMLs",
            "Apply every actress YAML on the classpath in one atomic task.",
            List.of()
    );

    private final ActressYamlLoader loader;

    public LoadAllActressesTask(ActressYamlLoader loader) {
        this.loader = loader;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("load_all", "Load all actress YAMLs");
        List<String> slugs;
        try {
            slugs = loader.listSlugs();
        } catch (Exception e) {
            io.phaseLog("load_all", "Failed to enumerate YAMLs: " + e.getMessage());
            io.phaseEnd("load_all", "failed", e.getMessage());
            return;
        }

        int total = slugs.size();
        int done = 0;
        int succeeded = 0;
        int failed = 0;
        int titlesCreated = 0;
        int titlesEnriched = 0;

        boolean cancelled = false;
        for (String slug : slugs) {
            if (io.isCancellationRequested()) {
                cancelled = true;
                io.phaseLog("load_all", "Cancellation requested — stopping after " + done + " of " + total + ".");
                break;
            }
            io.phaseProgress("load_all", done, total, "processing " + slug);
            try {
                ActressYamlLoader.LoadResult r = loader.loadOne(slug);
                succeeded++;
                titlesCreated += r.titlesCreated();
                titlesEnriched += r.titlesEnriched();
            } catch (Exception e) {
                failed++;
                io.phaseLog("load_all", "Failed " + slug + ": " + e.getMessage());
            }
            done++;
            io.phaseProgress("load_all", done, total, "processed " + slug);
        }

        String summary = succeeded + " of " + total + " loaded · "
                + titlesCreated + " titles created, " + titlesEnriched + " enriched"
                + (failed > 0 ? " · " + failed + " failed" : "")
                + (cancelled ? " · cancelled" : "");
        String status = (total == 0) ? "ok"
                : (succeeded == 0 && !cancelled) ? "failed"
                : "ok"; // partial is reflected in the summary text; phase-level ok covers "some work done"
        io.phaseEnd("load_all", status, summary);
    }
}
