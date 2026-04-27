package com.organizer3.utilities.task.rating;

import com.organizer3.rating.RatingCurveRecomputer;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;

/**
 * Manual admin action: recomputes the rating curve from all enriched title ratings and
 * re-stamps every non-manual title grade. Runs as a one-phase atomic Utilities task.
 */
public final class RecomputeRatingCurveTask implements Task {

    public static final String ID = "rating.recompute_curve";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Recompute rating curve",
            "Recompute the Bayesian rating curve from all enriched title ratings and re-stamp grades.",
            List.of()
    );

    private final RatingCurveRecomputer recomputer;

    public RecomputeRatingCurveTask(RatingCurveRecomputer recomputer) {
        this.recomputer = recomputer;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("recompute", "Recomputing rating curve");
        try {
            RatingCurveRecomputer.RecomputeResult result = recomputer.recompute();
            String summary = String.format("updated=%d, skipped-manual=%d, no-grade=%d",
                    result.updatedCount(), result.skippedManualCount(), result.noGradeCount());
            io.phaseEnd("recompute", "ok", summary);
        } catch (Exception e) {
            io.phaseLog("recompute", "Recompute failed: " + e.getMessage());
            io.phaseEnd("recompute", "failed", e.getMessage());
        }
    }
}
