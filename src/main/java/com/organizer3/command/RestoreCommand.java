package com.organizer3.command;

import com.organizer3.backup.RestoreResult;
import com.organizer3.backup.UnsupportedBackupVersionException;
import com.organizer3.backup.UserDataBackup;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code restore [path]} — overlay user-altered fields from a backup file onto the
 * current database.
 *
 * <p>With no argument, reads from the configured default backup path.
 * An explicit path can be supplied as the first argument.
 *
 * <p>In dry-run mode the file is read and parsed and counts are reported, but nothing
 * is written to the database.
 *
 * <p>Skipped entries (actress/title not yet in the DB) are expected when restoring
 * before all volumes have been synced — run {@code sync all} on each volume and then
 * {@code restore} again to pick them up.
 */
@RequiredArgsConstructor
public class RestoreCommand implements Command {

    private final UserDataBackupService service;
    private final Path defaultBackupPath;

    @Override
    public String name() { return "restore"; }

    @Override
    public String description() { return "Restore user data from backup file (restore [path])"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        Path path;
        if (args.length >= 2) {
            path = Path.of(args[1]);
        } else {
            path = service.findLatestBackup(defaultBackupPath).orElse(null);
            if (path == null) {
                io.println("No backup file found at: " + defaultBackupPath.getParent());
                return;
            }
            io.println("Using backup: " + path.getFileName());
        }

        if (!Files.exists(path)) {
            io.println("Backup file not found: " + path);
            return;
        }

        UserDataBackup backup;
        try {
            backup = service.read(path);
        } catch (UnsupportedBackupVersionException e) {
            io.println("Error: " + e.getMessage());
            return;
        } catch (Exception e) {
            io.println("Failed to read backup file: " + e.getMessage());
            return;
        }

        if (ctx.isDryRun()) {
            io.println(String.format(
                    "[DRY RUN] Would restore %,d actress records, %,d title records, %,d watch history entries.",
                    backup.actresses().size(), backup.titles().size(), backup.watchHistory().size()));
            io.println("(Skipped counts require a real run — not all entities may be present yet.)");
            io.println("Run 'arm' to enable writing.");
            return;
        }

        try {
            RestoreResult result = service.restore(backup);
            io.println(String.format("Restored %,d actress records (%,d skipped — not found).",
                    result.actressesRestored(), result.actressesSkipped()));
            io.println(String.format("Restored %,d title records (%,d skipped — not found).",
                    result.titlesRestored(), result.titlesSkipped()));
            io.println(String.format("Inserted %,d watch history entries.", result.watchHistoryInserted()));
            if (result.actressesSkipped() > 0 || result.titlesSkipped() > 0) {
                io.println("Tip: sync remaining volumes and run 'restore' again to pick up skipped entries.");
            }
        } catch (Exception e) {
            io.println("Restore failed: " + e.getMessage());
        }
    }
}
