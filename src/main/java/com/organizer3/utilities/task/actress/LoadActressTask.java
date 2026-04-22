package com.organizer3.utilities.task.actress;

import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Apply a single actress YAML (by slug) to the DB. Wraps {@link ActressYamlLoader#loadOne}
 * in the standard task lifecycle so it streams phase events and respects the atomic task lock.
 *
 * <p>Non-destructive in intent — writes are unconditional overwrites of enrichment fields only,
 * never touching operational state (tier, favorite, grade, bookmark, rejected). The underlying
 * loader is idempotent, so running this task twice in a row has no ill effect.
 */
public final class LoadActressTask implements Task {

    public static final String ID = "actress.load_one";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Load actress YAML",
            "Apply one actress YAML from the classpath to the DB (enrichment overwrite, operational state preserved).",
            List.of(new TaskSpec.InputSpec("slug", "Slug", TaskSpec.InputSpec.InputType.STRING, true))
    );

    private final ActressYamlLoader loader;

    public LoadActressTask(ActressYamlLoader loader) {
        this.loader = loader;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        String slug = inputs.getString("slug");
        io.phaseStart("load", "Load actress YAML");
        try {
            ActressYamlLoader.LoadResult r = loader.loadOne(slug);
            io.phaseEnd("load", "ok", summaryFor(r));
        } catch (IllegalArgumentException e) {
            io.phaseLog("load", e.getMessage());
            io.phaseEnd("load", "failed", "No YAML found for '" + slug + "'");
        } catch (Exception e) {
            io.phaseLog("load", "Load failed: " + e.getMessage());
            io.phaseEnd("load", "failed", e.getMessage());
        }
    }

    /** Human-readable one-liner distilled from a LoadResult. */
    static String summaryFor(ActressYamlLoader.LoadResult r) {
        StringBuilder s = new StringBuilder(r.canonicalName())
                .append(" · ").append(r.titlesCreated()).append(" created, ")
                .append(r.titlesEnriched()).append(" enriched");
        int unresolved = r.unresolvedCodes() == null ? 0 : r.unresolvedCodes().size();
        if (unresolved > 0) s.append(", ").append(unresolved).append(" unresolved");
        return s.toString();
    }
}
