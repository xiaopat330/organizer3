package com.organizer3.utilities.task.trash;

import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.trash.BatchResult;
import com.organizer3.trash.TrashService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/**
 * Removes the scheduled-deletion date from a set of trashed items.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code volumeId} — the volume on which the trash lives</li>
 *   <li>{@code sidecarPaths} — list of absolute sidecar paths (Strings) to unschedule</li>
 * </ul>
 */
@Slf4j
public final class TrashUnscheduleTask implements Task {

    public static final String ID = "trash.unschedule";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Unschedule trash items",
            "Removes the scheduled-deletion date from one or more trashed items.",
            List.of()
    );

    private final TrashService trashService;
    private final SmbConnectionFactory smbConnectionFactory;

    public TrashUnscheduleTask(TrashService trashService, SmbConnectionFactory smbConnectionFactory) {
        this.trashService = trashService;
        this.smbConnectionFactory = smbConnectionFactory;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        String volumeId = (String) inputs.values().get("volumeId");
        @SuppressWarnings("unchecked")
        List<String> rawPaths = (List<String>) inputs.values().get("sidecarPaths");

        if (volumeId == null || rawPaths == null) {
            io.phaseStart("unschedule", "Unschedule items");
            io.phaseEnd("unschedule", "failed", "Missing required inputs: volumeId, sidecarPaths");
            return;
        }

        List<Path> sidecarPaths = rawPaths.stream().map(Path::of).toList();

        io.phaseStart("unschedule", "Unschedule " + sidecarPaths.size() + " item(s)");
        try {
            BatchResult result = smbConnectionFactory.withRetry(volumeId,
                    handle -> trashService.unschedule(handle.fileSystem(), sidecarPaths));
            String summary = result.successes() + " unscheduled"
                    + (result.hasFailures() ? " · " + result.failures().size() + " failed" : "");
            io.phaseEnd("unschedule", result.hasFailures() ? "failed" : "ok", summary);
        } catch (Exception e) {
            log.error("TrashUnscheduleTask failed for volume {}", volumeId, e);
            io.phaseEnd("unschedule", "failed", e.getMessage());
        }
    }
}
