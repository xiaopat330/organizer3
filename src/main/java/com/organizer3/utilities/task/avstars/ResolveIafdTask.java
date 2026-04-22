package com.organizer3.utilities.task.avstars;

import com.organizer3.utilities.avstars.IafdResolverService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Finalize an IAFD pick for a single AV actress: fetch profile, download headshot, persist.
 * Runs inside the atomic task lock so concurrent resolves (from UI + MCP, say) can't race.
 *
 * <p>Inputs: {@code actressId} (Long), {@code iafdId} (String UUID).
 */
public final class ResolveIafdTask implements Task {

    public static final String ID = "avstars.resolve_iafd";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Apply IAFD pick",
            "Fetch profile + headshot for the chosen IAFD identity and persist.",
            List.of(new TaskSpec.InputSpec("actressId", "AV actress", TaskSpec.InputSpec.InputType.STRING, true),
                    new TaskSpec.InputSpec("iafdId",    "IAFD id",    TaskSpec.InputSpec.InputType.STRING, true))
    );

    private final IafdResolverService resolver;

    public ResolveIafdTask(IafdResolverService resolver) {
        this.resolver = resolver;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        long actressId = Long.parseLong(String.valueOf(inputs.values().get("actressId")));
        String iafdId  = inputs.getString("iafdId");

        io.phaseStart("resolve", "Apply IAFD pick");
        try {
            IafdResolverService.ApplyResult r = resolver.apply(actressId, iafdId);
            io.phaseEnd("resolve", "ok",
                    "Resolved " + r.stageName() + " → " + r.iafdId()
                            + " (" + (r.iafdTitleCount() != null ? r.iafdTitleCount() + " titles" : "no count")
                            + (r.headshotSaved() ? " · headshot saved" : "") + ")");
        } catch (Exception e) {
            io.phaseLog("resolve", "Resolve failed: " + e.getMessage());
            io.phaseEnd("resolve", "failed", e.getMessage());
        }
    }
}
