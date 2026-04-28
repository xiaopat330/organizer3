package com.organizer3.utilities.task.rating;

import com.organizer3.rating.ActressRatingCurveRecomputer;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Manual admin action: recomputes the actress rating curve from all enriched-title ratings
 * and re-stamps every qualifying actress's computed_grade. Mirrors
 * {@link RecomputeRatingCurveTask} on the actress side.
 */
public final class RecomputeActressRatingCurveTask implements Task {

    public static final String ID = "rating.recompute_actress_curve";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Recompute actress rating curve",
            "Aggregate per-actress title ratings into Bayesian-shrunken scores, build percentile cutoffs, stamp computed grades.",
            List.of()
    );

    private final ActressRatingCurveRecomputer recomputer;

    public RecomputeActressRatingCurveTask(ActressRatingCurveRecomputer recomputer) {
        this.recomputer = recomputer;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("recompute", "Recomputing actress rating curve");
        try {
            ActressRatingCurveRecomputer.RecomputeResult r = recomputer.recompute();
            String summary = String.format("qualifying=%d, stamped=%d, cleared=%d, total=%d",
                    r.qualifying(), r.stamped(), r.cleared(), r.totalActresses());
            io.phaseEnd("recompute", "ok", summary);
        } catch (Exception e) {
            io.phaseLog("recompute", "Recompute failed: " + e.getMessage());
            io.phaseEnd("recompute", "failed", e.getMessage());
        }
    }
}
