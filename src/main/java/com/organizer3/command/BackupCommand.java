package com.organizer3.command;

import com.organizer3.backup.UserDataBackup;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

/**
 * {@code backup} — export all user-altered fields to the configured backup file.
 *
 * <p>When {@code snapshotCount > 0}, writes a timestamped snapshot file and prunes
 * older snapshots beyond the configured count. Otherwise overwrites a single file.
 *
 * <p>In dry-run mode the counts are reported but the file is not written.
 */
@RequiredArgsConstructor
public class BackupCommand implements Command {

    private final UserDataBackupService service;
    private final Path backupPath;
    private final int snapshotCount;

    @Override
    public String name() { return "backup"; }

    @Override
    public String description() { return "Export user data (favorites, grades, visits) to backup file"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        UserDataBackup backup = service.export();

        int actresses    = backup.actresses().size();
        int titles       = backup.titles().size();
        int watchHistory = backup.watchHistory().size();

        if (ctx.isDryRun()) {
            io.println(String.format(
                    "[DRY RUN] Would export %,d actress records, %,d title records, %,d watch history entries.",
                    actresses, titles, watchHistory));
            io.println("Run 'arm' to enable writing.");
            return;
        }

        try {
            Path writtenPath;
            if (snapshotCount > 0) {
                writtenPath = service.exportAndWriteSnapshot(backupPath, snapshotCount);
            } else {
                service.write(backup, backupPath);
                writtenPath = backupPath;
            }
            io.println(String.format(
                    "Exported %,d actress records, %,d title records, %,d watch history entries.",
                    actresses, titles, watchHistory));
            io.println("Backup written to: " + writtenPath);
        } catch (Exception e) {
            io.println("Backup failed: " + e.getMessage());
        }
    }
}
