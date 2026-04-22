package com.organizer3.utilities.task.backup;

import com.organizer3.backup.RestoreResult;
import com.organizer3.backup.UserDataBackup;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.utilities.backup.BackupCatalogService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Restore user-altered DB state from a named snapshot. The snapshot name is resolved against
 * the configured backups directory — callers cannot target arbitrary filesystem paths.
 *
 * <p>Server-side re-reads and re-applies the file under the atomic task lock, so the preview
 * the user saw is authoritative for the duration of the confirmation, and concurrent writes
 * from another task can't interleave.
 */
public final class RestoreSnapshotTask implements Task {

    public static final String ID = "backup.restore";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Restore snapshot",
            "Overlay user-altered DB fields from a snapshot file onto the current database.",
            List.of(new TaskSpec.InputSpec(
                    "snapshotName", "Snapshot", TaskSpec.InputSpec.InputType.STRING, true))
    );

    private final UserDataBackupService service;
    private final BackupCatalogService catalog;

    public RestoreSnapshotTask(UserDataBackupService service, BackupCatalogService catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        String name = inputs.getString("snapshotName");
        io.phaseStart("restore", "Restore snapshot");
        Optional<Path> resolved = catalog.resolve(name);
        if (resolved.isEmpty()) {
            io.phaseLog("restore", "Unknown snapshot: " + name);
            io.phaseEnd("restore", "failed", "Snapshot not found: " + name);
            return;
        }
        try {
            UserDataBackup backup = service.read(resolved.get());
            RestoreResult r = service.restore(backup);
            io.phaseEnd("restore", "ok", summaryFor(r));
        } catch (Exception e) {
            io.phaseLog("restore", "Restore failed: " + e.getMessage());
            io.phaseEnd("restore", "failed", e.getMessage());
        }
    }

    private static String summaryFor(RestoreResult r) {
        StringBuilder s = new StringBuilder();
        s.append(r.actressesRestored()).append(" actresses · ")
         .append(r.titlesRestored()).append(" titles · ")
         .append(r.watchHistoryInserted()).append(" watch entries");
        if (r.avActressesRestored() > 0 || r.avVideosRestored() > 0) {
            s.append(" · ").append(r.avActressesRestored()).append(" av-actresses · ")
             .append(r.avVideosRestored()).append(" av-videos");
        }
        int skipped = r.actressesSkipped() + r.titlesSkipped()
                    + r.avActressesSkipped() + r.avVideosSkipped();
        if (skipped > 0) s.append(" · ").append(skipped).append(" skipped (no DB row)");
        return s.toString();
    }
}
