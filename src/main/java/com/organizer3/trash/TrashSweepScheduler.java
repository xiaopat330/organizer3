package com.organizer3.trash;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.smb.NasAvailabilityMonitor;
import com.organizer3.smb.SmbConnectionFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically sweeps all volumes for expired trash items and permanently deletes them.
 *
 * <p>The first sweep fires immediately at startup so items that expired while the app
 * was offline are cleaned up without waiting a full interval. Subsequent sweeps run
 * every {@code intervalHours} hours.
 *
 * <p>Only volumes whose server has a {@code trash:} folder configured are swept.
 * SMB errors on individual volumes are logged and skipped — a failure on one volume
 * does not abort the sweep of others.
 */
@Slf4j
public class TrashSweepScheduler {

    private final TrashService trashService;
    private final SmbConnectionFactory smbConnectionFactory;
    private final OrganizerConfig config;
    private final NasAvailabilityMonitor monitor;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "trash-sweep");
        t.setDaemon(true);
        return t;
    });

    public TrashSweepScheduler(TrashService trashService,
                                SmbConnectionFactory smbConnectionFactory,
                                OrganizerConfig config,
                                NasAvailabilityMonitor monitor) {
        this.trashService = trashService;
        this.smbConnectionFactory = smbConnectionFactory;
        this.config = config;
        this.monitor = monitor;
    }

    /**
     * Starts the sweep scheduler. The first sweep fires immediately (delay = 0),
     * then repeats every {@code intervalHours} hours.
     */
    public void start(long intervalHours) {
        log.info("Trash sweep scheduler starting — interval={}h", intervalHours);
        executor.scheduleAtFixedRate(this::sweepAll, 0, intervalHours, TimeUnit.HOURS);
    }

    /** Stops the scheduler, waiting up to 10 seconds for an in-flight sweep to finish. */
    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    void sweepAll() {
        Instant now = Instant.now();
        List<VolumeConfig> volumes = config.volumes();
        log.info("Trash sweep starting — {} volume(s) to check", volumes.size());

        int totalDeleted = 0, totalErrors = 0;
        for (VolumeConfig volume : volumes) {
            Optional<ServerConfig> serverOpt = config.findServerById(volume.server());
            if (serverOpt.isEmpty() || serverOpt.get().trash() == null) continue;

            if (!monitor.isVolumeAvailable(volume.id())) {
                log.debug("Trash sweep skipping volume {} — NAS host is unreachable", volume.id());
                continue;
            }

            String trashFolder = serverOpt.get().trash();
            Path trashRoot = Path.of("/").resolve(trashFolder);
            try {
                SweepReport report = smbConnectionFactory.withRetry(volume.id(),
                        handle -> trashService.sweepExpired(handle.fileSystem(), volume.id(), trashRoot, now));
                if (report.deleted() > 0 || report.errors() > 0) {
                    log.info("Trash sweep [{}]: deleted={} skipped={} errors={}",
                            volume.id(), report.deleted(), report.skipped(), report.errors());
                }
                totalDeleted += report.deleted();
                totalErrors  += report.errors();
            } catch (Exception e) {
                log.warn("Trash sweep failed for volume {}: {}", volume.id(), e.getMessage());
                totalErrors++;
            }
        }

        if (totalDeleted > 0 || totalErrors > 0) {
            log.info("Trash sweep complete — deleted={} errors={}", totalDeleted, totalErrors);
        } else {
            log.debug("Trash sweep complete — nothing to delete");
        }
    }
}
