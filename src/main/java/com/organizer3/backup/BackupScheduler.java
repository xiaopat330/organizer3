package com.organizer3.backup;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires periodic automatic backups on a configurable interval.
 *
 * <p>Runs on a single daemon thread so the JVM can exit cleanly without waiting for
 * a pending tick. Auto-backup always writes regardless of the shell's dry-run mode —
 * dry-run protects the media library from file operations, not the user's own
 * preference data from being saved locally.
 */
@Slf4j
public class BackupScheduler {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backup-scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * Schedule periodic backups every {@code intervalMinutes} minutes.
     * The first tick fires after one full interval (not immediately at startup).
     *
     * <p>When {@code snapshotCount > 0}, each tick writes a new timestamped snapshot
     * and prunes the oldest ones beyond the count. When {@code snapshotCount <= 0},
     * a single file at {@code backupPath} is overwritten each time.
     */
    public void start(UserDataBackupService service, Path backupPath,
                      long intervalMinutes, int snapshotCount) {
        if (snapshotCount > 0) {
            log.info("Auto-backup scheduled every {} minute(s) → {} (keeping {} snapshots)",
                    intervalMinutes, backupPath.getParent(), snapshotCount);
        } else {
            log.info("Auto-backup scheduled every {} minute(s) → {}", intervalMinutes, backupPath);
        }
        executor.scheduleAtFixedRate(() -> {
            try {
                if (snapshotCount > 0) {
                    service.exportAndWriteSnapshot(backupPath, snapshotCount);
                } else {
                    service.exportAndWrite(backupPath);
                }
            } catch (Exception e) {
                log.error("Auto-backup failed", e);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    /** Stop the scheduler, waiting up to 5 seconds for any in-flight tick to finish. */
    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
