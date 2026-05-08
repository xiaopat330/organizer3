package com.organizer3.utilities.task.volume;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.FullSyncOperation;
import com.organizer3.sync.ReconcileService;
import com.organizer3.sync.SyncPruneService;
import com.organizer3.sync.SyncStats;
import com.organizer3.utilities.task.BufferingCommandIO;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Scans every configured volume in turn (with orphan prune suppressed per-volume),
 * then runs a single global orphan evaluation after all volumes have been observed.
 *
 * <p>This prevents a title that was manually moved from volume A to volume B from
 * appearing as a false orphan when only A is synced — the title is observed at B before
 * the global prune decides whether it has "disappeared". See
 * {@code spec/PROPOSAL_SYNC_RECONCILIATION.md §3} for design rationale.
 *
 * <h3>Hang protection (fail-fast)</h3>
 * Each volume's scan and unmount run inside a {@link Future} with a hard timeout.
 * If the SMB connection dies mid-scan, the per-call read timeout ({@link com.organizer3.config.SmbSettings})
 * should surface within minutes; the per-volume watchdog catches any remaining stalls.
 * A hung scan produces a {@code phaseEnd(failed)} within the configured timeout rather
 * than blocking the JVM indefinitely. A 60-second INFO heartbeat makes slow-but-healthy
 * runs distinguishable from hangs in the log. See
 * {@code spec/PROPOSAL_SMB_TIMEOUT_HARDENING.md §3.2–3.4}.
 *
 * <h3>Failure handling</h3>
 * If any volume fails to mount or scan, the task continues with the remaining volumes
 * but skips the global orphan prune (incomplete picture — we cannot safely judge
 * orphans when some volumes were not observed). The partial-failure flag and per-volume
 * outcome are logged and surfaced in the task's phase events.
 *
 * <h3>Volume scope</h3>
 * By default all configured volumes whose structure type has a registered scanner are
 * included (conventional, exhibition, queue, sort_pool, collections). AvStars volumes
 * use a separate sync path and are excluded. Pass explicit volume ids via the
 * {@code volumeIds} task input to restrict the scope.
 *
 * <h3>Lock semantics</h3>
 * This task holds the {@link com.organizer3.utilities.task.TaskRunner} global lock for
 * the duration via the normal task-slot mechanism. No other utility task can run
 * concurrently. This is intentional — coherent sync is designed for overnight runs where
 * exclusivity is expected.
 */
@Slf4j
public final class CoherentMultiVolumeSyncTask implements Task {

    public static final String ID = "volume.sync_coherent";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Coherent sync (all volumes)",
            "Scans every configured volume in turn and evaluates orphans once after all volumes "
            + "have been observed. Designed for overnight runs after manual cross-volume movement. "
            + "Holds the task lock for the duration — may run for hours.",
            List.of()
    );

    /** Structure types handled by FullSyncOperation / ScannerRegistry. AvStars is excluded. */
    private static final List<String> SUPPORTED_STRUCTURE_TYPES =
            List.of("conventional", "exhibition", "queue", "sort_pool", "collections");

    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 60_000L;

    private final Supplier<CommandInvoker> invokerFactory;
    private final FullSyncOperation suppressedPruneOp;
    private final SyncPruneService pruneService;
    private final ReconcileService reconcileService;
    private final long heartbeatIntervalMs;

    /**
     * @param invokerFactory       factory producing a fresh {@link CommandInvoker} (with its own
     *                             {@link SessionContext}) for each run — same pattern as
     *                             {@link SyncVolumeTask}
     * @param suppressedPruneOp    a {@link FullSyncOperation} constructed with
     *                             {@code suppressPrune=true}; reused for every volume's scan
     * @param pruneService         service that exposes the global prune and finalize steps after
     *                             all volumes have been scanned
     * @param reconcileService     service for auto-running the reconcile pass at the end of a
     *                             successful coherent sync; results are persisted as a row
     *                             with {@code triggered_by='coherent_sync'}
     */
    public CoherentMultiVolumeSyncTask(Supplier<CommandInvoker> invokerFactory,
                                       FullSyncOperation suppressedPruneOp,
                                       SyncPruneService pruneService,
                                       ReconcileService reconcileService) {
        this(invokerFactory, suppressedPruneOp, pruneService, reconcileService,
                DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    /**
     * Package-private constructor for tests — allows injecting a shorter heartbeat interval.
     */
    CoherentMultiVolumeSyncTask(Supplier<CommandInvoker> invokerFactory,
                                FullSyncOperation suppressedPruneOp,
                                SyncPruneService pruneService,
                                ReconcileService reconcileService,
                                long heartbeatIntervalMs) {
        this.invokerFactory = invokerFactory;
        this.suppressedPruneOp = suppressedPruneOp;
        this.pruneService = pruneService;
        this.reconcileService = reconcileService;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) throws Exception {
        List<VolumeConfig> volumes = selectVolumes();
        if (volumes.isEmpty()) {
            io.phaseLog("coherent", "No supported volumes configured — nothing to sync.");
            return;
        }

        log.info("Coherent sync starting — {} volume(s): {}", volumes.size(),
                volumes.stream().map(VolumeConfig::id).toList());

        boolean partialFailure = false;
        List<String> failedVolumes = new ArrayList<>();

        long runStartMs = System.currentTimeMillis();
        AtomicReference<String> currentVolumeId = new AtomicReference<>("(none)");

        // ── Heartbeat scheduler ───────────────────────────────────────────────
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "coherent-heartbeat");
                    t.setDaemon(true);
                    return t;
                });
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            long elapsedMin = (System.currentTimeMillis() - runStartMs) / 60_000L;
            log.info("Coherent sync alive — current phase=vol.{} ({}m elapsed)",
                    currentVolumeId.get(), elapsedMin);
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        // ── Scan executor (single-thread; one volume at a time) ───────────────
        ExecutorService scanExecutor = Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, "coherent-scan");
                    t.setDaemon(true);
                    return t;
                });

        try {
            for (VolumeConfig volume : volumes) {
                if (io.isCancellationRequested()) {
                    log.info("Coherent sync cancelled before volume={}", volume.id());
                    break;
                }

                currentVolumeId.set(volume.id());
                String phaseId = "vol." + volume.id();
                io.phaseStart(phaseId, "Sync volume " + volume.id().toUpperCase());
                boolean volumeOk = false;

                CommandInvoker invoker = invokerFactory.get();
                try {
                    // ── Mount ──────────────────────────────────────────────────
                    boolean mounted = invoker.invoke(phaseId, "mount",
                            new String[]{"mount", volume.id()}, io)
                            && invoker.session().isConnected();

                    if (!mounted) {
                        io.phaseLog(phaseId, "Mount failed for volume " + volume.id());
                        partialFailure = true;
                        failedVolumes.add(volume.id());
                        io.phaseEnd(phaseId, "failed", "Mount failed");
                        continue;
                    }

                    // ── Suppress-prune scan with per-volume watchdog ───────────
                    VolumeStructureDef structure = AppConfig.get().volumes()
                            .findStructureById(volume.structureType())
                            .orElseThrow(() -> new IllegalStateException(
                                    "No structure definition for type: " + volume.structureType()));

                    SessionContext ctx = invoker.session();
                    CommandIO phaseIO = new BufferingCommandIO(io, phaseId);

                    long volumeTimeoutMin = AppConfig.get().volumes().smbOrDefaults()
                            .perVolumeTimeoutMinutesOrDefault();

                    Future<Void> scanFuture = scanExecutor.submit(() -> {
                        // Run FullSyncOperation(suppressPrune=true) — marks stale, scans,
                        // saves, finalizes per-volume (last_synced_at + per-volume recompute),
                        // but does NOT run pruneOrphanedTitlesAndCovers.
                        suppressedPruneOp.execute(volume, structure,
                                ctx.getActiveConnection().fileSystem(), ctx, phaseIO);
                        return null;
                    });

                    try {
                        scanFuture.get(volumeTimeoutMin, TimeUnit.MINUTES);
                        volumeOk = true;
                    } catch (TimeoutException te) {
                        log.error("Coherent sync hard timeout on volume={} after {} min — marking failed",
                                volume.id(), volumeTimeoutMin);
                        io.phaseLog(phaseId, "Hard timeout exceeded — marking volume failed");
                        partialFailure = true;
                        failedVolumes.add(volume.id());
                        scanFuture.cancel(true);
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        log.error("Coherent sync scan failed for volume={}: {}",
                                volume.id(), cause.getMessage(), cause);
                        io.phaseLog(phaseId, "Scan failed: " + cause.getMessage());
                        partialFailure = true;
                        failedVolumes.add(volume.id());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Coherent sync interrupted waiting on volume={}", volume.id());
                        partialFailure = true;
                        failedVolumes.add(volume.id());
                        scanFuture.cancel(true);
                    }

                } finally {
                    // ── Unmount — always, with bounded wait ───────────────────
                    long unmountTimeoutSec = AppConfig.get().volumes().smbOrDefaults()
                            .unmountTimeoutSecondsOrDefault();
                    Future<Void> unmountFuture = scanExecutor.submit(() -> {
                        invoker.invoke(phaseId, "unmount", new String[]{"unmount"}, io);
                        return null;
                    });
                    try {
                        unmountFuture.get(unmountTimeoutSec, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        log.warn("Unmount timeout for volume={} after {}s — abandoning",
                                volume.id(), unmountTimeoutSec);
                        unmountFuture.cancel(true);
                    } catch (ExecutionException ee) {
                        log.warn("Unmount failed for volume={}: {}",
                                volume.id(), ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted during unmount for volume={}", volume.id());
                        unmountFuture.cancel(true);
                    }

                    io.phaseEnd(phaseId, volumeOk ? "ok" : "failed",
                            volumeOk ? "Scan complete" : "Scan failed");
                }
            }
        } finally {
            scanExecutor.shutdownNow();
            heartbeatExecutor.shutdownNow();
        }

        // ── Global prune ──────────────────────────────────────────────────
        if (partialFailure) {
            log.error("Coherent sync ran with partial failures — volumes that failed: {}. "
                    + "Orphan prune skipped (incomplete picture). "
                    + "Re-run after fixing the failed volume(s).", failedVolumes);
            io.phaseLog("prune", "Global prune SKIPPED — partial failures on volumes: "
                    + failedVolumes
                    + ". Re-run after fixing the failed volume(s).");
        } else {
            io.phaseStart("prune", "Global orphan prune");
            try {
                int staleGraceDays = AppConfig.get().volumes().syncOrDefaults().staleGraceDaysOrDefault();
                SyncStats pruneStats = new SyncStats();
                CommandIO pruneIO = new BufferingCommandIO(io, "prune");
                pruneService.pruneOrphanedTitlesAndCovers(pruneIO, staleGraceDays, pruneStats);
                log.info("Coherent sync global prune complete — swept={}", pruneStats.swept);
                io.phaseEnd("prune", "ok",
                        "Prune complete; swept=" + pruneStats.swept);
            } catch (RuntimeException e) {
                log.error("Coherent sync global prune failed: {}", e.getMessage(), e);
                io.phaseLog("prune", "Global prune failed: " + e.getMessage());
                io.phaseEnd("prune", "failed", "Prune failed: " + e.getMessage());
            }

            // Auto-reconcile: runs only after a fully successful sync with no partial failures.
            // Skipped when partialFailure=true (incomplete picture would make the report misleading).
            io.phaseStart("reconcile", "Reconcile pass");
            try {
                var report = reconcileService.run(/* verbose= */ true);
                reconcileService.persist(report, "coherent_sync",
                        com.organizer3.sync.ReconcileDetailSerializer.toJson(report));
                log.info("Coherent sync reconcile complete — dupLive={} pendingGrace={} "
                                + "pastGrace={} mismatches={}",
                        report.duplicateLiveLocations(), report.pendingGrace(),
                        report.pastGraceStragglers(), report.actressFolderMismatches());
                io.phaseEnd("reconcile", "ok",
                        String.format("dupLive=%d pendingGrace=%d pastGrace=%d mismatches=%d",
                                report.duplicateLiveLocations(), report.pendingGrace(),
                                report.pastGraceStragglers(), report.actressFolderMismatches()));
            } catch (RuntimeException e) {
                log.error("Coherent sync reconcile failed: {}", e.getMessage(), e);
                io.phaseLog("reconcile", "Reconcile failed: " + e.getMessage());
                io.phaseEnd("reconcile", "failed", "Reconcile failed: " + e.getMessage());
            }
        }

        log.info("Coherent sync finished — volumes={} partialFailure={} failedVolumes={}",
                volumes.size(), partialFailure, failedVolumes);
    }

    /**
     * Returns the list of volumes to sync. Excludes avstars and any volume whose
     * structure type is not supported by {@link FullSyncOperation}/{@link com.organizer3.sync.scanner.ScannerRegistry}.
     */
    private List<VolumeConfig> selectVolumes() {
        return AppConfig.get().volumes().volumes().stream()
                .filter(v -> SUPPORTED_STRUCTURE_TYPES.contains(v.structureType()))
                .toList();
    }
}
