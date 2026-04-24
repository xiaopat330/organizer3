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
import java.time.Instant;
import java.util.List;

/**
 * Schedules a set of trashed items for permanent deletion at a specified time.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code volumeId} — the volume on which the trash lives</li>
 *   <li>{@code sidecarPaths} — list of absolute sidecar paths (Strings) to schedule</li>
 *   <li>{@code scheduledAt} — ISO-8601 instant at which deletion becomes eligible</li>
 * </ul>
 */
@Slf4j
public final class TrashScheduleTask implements Task {

    public static final String ID = "trash.schedule";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Schedule trash items for deletion",
            "Sets a scheduled-deletion date on one or more trashed items.",
            List.of()
    );

    private final TrashService trashService;
    private final SmbConnectionFactory smbConnectionFactory;

    public TrashScheduleTask(TrashService trashService, SmbConnectionFactory smbConnectionFactory) {
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
        String scheduledAtStr = (String) inputs.values().get("scheduledAt");

        if (volumeId == null || rawPaths == null || scheduledAtStr == null) {
            io.phaseStart("schedule", "Schedule items");
            io.phaseEnd("schedule", "failed", "Missing required inputs: volumeId, sidecarPaths, scheduledAt");
            return;
        }

        List<Path> sidecarPaths = rawPaths.stream().map(Path::of).toList();
        Instant scheduledAt = Instant.parse(scheduledAtStr);

        io.phaseStart("schedule", "Schedule " + sidecarPaths.size() + " item(s) for deletion");
        try (SmbConnectionFactory.SmbShareHandle handle = smbConnectionFactory.open(volumeId)) {
            BatchResult result = trashService.scheduleForDeletion(handle.fileSystem(), sidecarPaths, scheduledAt);
            String summary = result.successes() + " scheduled"
                    + (result.hasFailures() ? " · " + result.failures().size() + " failed" : "");
            io.phaseEnd("schedule", result.hasFailures() ? "failed" : "ok", summary);
        } catch (Exception e) {
            log.error("TrashScheduleTask failed for volume {}", volumeId, e);
            io.phaseEnd("schedule", "failed", e.getMessage());
        }
    }
}
