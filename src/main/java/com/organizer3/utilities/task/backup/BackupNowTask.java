package com.organizer3.utilities.task.backup;

import com.organizer3.backup.UserDataBackup;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * Write a timestamped snapshot of user-altered DB state. Non-destructive (only creates a new
 * file; prunes oldest above the retention cap). No visualize step.
 */
public final class BackupNowTask implements Task {

    public static final String ID = "backup.run_now";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Back up now",
            "Write a snapshot of user-altered DB state to disk.",
            List.of()
    );

    private final UserDataBackupService service;
    private final Path basePath;
    private final int snapshotCount;

    public BackupNowTask(UserDataBackupService service, Path basePath, int snapshotCount) {
        this.service = service;
        this.basePath = basePath;
        this.snapshotCount = snapshotCount;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("export", "Write snapshot");
        try {
            UserDataBackup backup = service.export();
            Path written = service.exportAndWriteSnapshot(basePath,
                    snapshotCount > 0 ? snapshotCount : Integer.MAX_VALUE);
            int av = backup.avActresses() != null ? backup.avActresses().size() : 0;
            int avv = backup.avVideos() != null ? backup.avVideos().size() : 0;
            io.phaseEnd("export", "ok",
                    backup.actresses().size() + " actresses · "
                  + backup.titles().size() + " titles · "
                  + backup.watchHistory().size() + " watch entries"
                  + (av > 0 || avv > 0 ? " · " + av + " av-actresses · " + avv + " av-videos" : "")
                  + " → " + written.getFileName());
        } catch (Exception e) {
            io.phaseLog("export", "Backup failed: " + e.getMessage());
            io.phaseEnd("export", "failed", e.getMessage());
        }
    }
}
