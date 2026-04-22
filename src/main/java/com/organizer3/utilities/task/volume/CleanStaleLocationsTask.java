package com.organizer3.utilities.task.volume;

import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import com.organizer3.utilities.volume.StaleLocationsService;

import java.util.List;

/**
 * Remove stale {@code title_locations} rows for a volume — rows whose file wasn't observed during
 * the most recent sync. First operation on the Volumes screen to exercise the visualize-then-confirm
 * lifecycle: the UI fetches {@link StaleLocationsService#preview} ahead of time and asks the user
 * to confirm; this task re-queries server-side under the same predicate so a stale preview can't
 * cause an unintended deletion.
 */
public final class CleanStaleLocationsTask implements Task {

    public static final String ID = "volume.clean_stale_locations";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Clean stale locations",
            "Remove title_locations rows whose file wasn't seen on the latest sync.",
            List.of(new TaskSpec.InputSpec(
                    "volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true))
    );

    private final StaleLocationsService staleLocations;

    public CleanStaleLocationsTask(StaleLocationsService staleLocations) {
        this.staleLocations = staleLocations;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        String volumeId = inputs.getString("volumeId");
        io.phaseStart("clean", "Clean stale locations");
        try {
            int deleted = staleLocations.delete(volumeId);
            io.phaseEnd("clean", "ok", deleted + " location(s) removed");
        } catch (RuntimeException e) {
            io.phaseLog("clean", "Delete failed: " + e.getMessage());
            io.phaseEnd("clean", "failed", e.getMessage());
        }
    }
}
