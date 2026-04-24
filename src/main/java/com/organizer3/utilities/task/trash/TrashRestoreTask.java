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
 * Restores a set of trashed items to their original paths.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code volumeId} — the volume on which the trash lives</li>
 *   <li>{@code sidecarPaths} — list of absolute sidecar paths (Strings) to restore</li>
 * </ul>
 *
 * <p>Note: restore does not update the database — the next sync will re-index
 * the restored items automatically.
 */
@Slf4j
public final class TrashRestoreTask implements Task {

    public static final String ID = "trash.restore";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Restore trash items",
            "Moves trashed items back to their original paths on the volume.",
            List.of()
    );

    private final TrashService trashService;
    private final SmbConnectionFactory smbConnectionFactory;

    public TrashRestoreTask(TrashService trashService, SmbConnectionFactory smbConnectionFactory) {
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
            io.phaseStart("restore", "Restore items");
            io.phaseEnd("restore", "failed", "Missing required inputs: volumeId, sidecarPaths");
            return;
        }

        List<Path> sidecarPaths = rawPaths.stream().map(Path::of).toList();
        Path trashRoot = Path.of("/_trash");

        io.phaseStart("restore", "Restore " + sidecarPaths.size() + " item(s)");
        try {
            BatchResult result = smbConnectionFactory.withRetry(volumeId,
                    handle -> trashService.restore(handle.fileSystem(), trashRoot, sidecarPaths));
            String summary = result.successes() + " restored"
                    + (result.hasFailures() ? " · " + result.failures().size() + " failed" : "");
            io.phaseEnd("restore", result.hasFailures() ? "failed" : "ok", summary);
        } catch (Exception e) {
            log.error("TrashRestoreTask failed for volume {}", volumeId, e);
            io.phaseEnd("restore", "failed", e.getMessage());
        }
    }
}
