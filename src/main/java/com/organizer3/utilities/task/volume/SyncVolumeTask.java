package com.organizer3.utilities.task.volume;

import com.organizer3.config.AppConfig;
import com.organizer3.shell.SessionContext;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Brings a volume's local index and covers fully up to date. End-to-end equivalent of
 * {@code mount → sync all → sync covers → unmount}, surfaced as a single user action.
 *
 * <p>Each step runs as a discrete phase so the UI can render per-step progress. Unmount
 * executes regardless of earlier phase outcomes, so a failed sync never leaves a dangling
 * mount. Phase statuses drive the task's terminal status (see
 * {@link com.organizer3.utilities.task.TaskRunner#inferTerminalStatus}).
 *
 * <p>The task does not reach into the user's live shell session: it receives a
 * {@link Supplier} that produces a fresh {@link CommandInvoker} (with its own
 * {@link SessionContext}) for each run.
 *
 * <h3>Unmount hang protection</h3>
 * The unmount phase runs with a bounded timeout (from {@link SmbSettings#unmountTimeoutSeconds}).
 * A dead SMB connection's unmount call can hang the same way as a dead scan; the timeout
 * prevents a single volume task from blocking indefinitely on cleanup.
 * See {@code spec/PROPOSAL_SMB_TIMEOUT_HARDENING.md §3.3}.
 */
@Slf4j
public final class SyncVolumeTask implements Task {

    public static final String ID = "volume.sync";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Sync volume",
            "Bring a volume's index and covers fully up to date.",
            List.of(new TaskSpec.InputSpec("volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true))
    );

    private final Supplier<CommandInvoker> invokerFactory;
    /** Unmount timeout in seconds. Defaults to {@link SmbSettings#unmountTimeoutSecondsOrDefault()} at runtime. */
    private final long unmountTimeoutSec;

    public SyncVolumeTask(Supplier<CommandInvoker> invokerFactory) {
        this.invokerFactory = invokerFactory;
        this.unmountTimeoutSec = -1; // sentinel: read from AppConfig at runtime
    }

    /** Package-private constructor for tests — allows injecting a fixed unmount timeout. */
    SyncVolumeTask(Supplier<CommandInvoker> invokerFactory, long unmountTimeoutSec) {
        this.invokerFactory = invokerFactory;
        this.unmountTimeoutSec = unmountTimeoutSec;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        String volumeId = inputs.getString("volumeId");
        CommandInvoker invoker = invokerFactory.get();

        boolean mounted = runPhase(io, "mount", "Mount volume", () ->
                invoker.invoke("mount", "mount", new String[]{"mount", volumeId}, io)
                        && invoker.session().isConnected());

        if (mounted) {
            if (!io.isCancellationRequested()) {
                runPhase(io, "syncTitles", "Sync titles", () ->
                        invoker.invoke("syncTitles", "sync all", new String[]{"sync all"}, io));
            }
            if (!io.isCancellationRequested()) {
                runPhase(io, "syncCovers", "Sync covers", () ->
                        invoker.invoke("syncCovers", "sync covers", new String[]{"sync covers"}, io));
            }
        } else {
            io.phaseLog("mount", "Skipping sync phases because mount failed.");
        }

        // Unmount runs unconditionally — even on cancel — so we never leave a dangling connection.
        // A dead connection can hang unmount; bound the wait to avoid a permanent stall.
        long effectiveUnmountTimeoutSec = unmountTimeoutSec >= 0
                ? unmountTimeoutSec
                : AppConfig.get().volumes().smbOrDefaults().unmountTimeoutSecondsOrDefault();
        runBoundedUnmount(io, volumeId, invoker, effectiveUnmountTimeoutSec);
    }

    /**
     * Runs the unmount phase with a hard timeout. If unmount does not complete within
     * {@code unmountTimeoutSec} seconds, it is abandoned (logged as a warning) and the
     * phase is marked failed. The task continues normally regardless — a failed unmount
     * is non-blocking.
     */
    private static void runBoundedUnmount(TaskIO io, String volumeId, CommandInvoker invoker,
                                          long unmountTimeoutSec) {
        io.phaseStart("unmount", "Unmount volume");

        ExecutorService unmountExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sync-volume-unmount");
            t.setDaemon(true);
            return t;
        });

        boolean ok = false;
        try {
            Future<Boolean> future = unmountExecutor.submit(() ->
                    invoker.invoke("unmount", "unmount", new String[]{"unmount"}, io));
            try {
                ok = Boolean.TRUE.equals(future.get(unmountTimeoutSec, TimeUnit.SECONDS));
            } catch (TimeoutException te) {
                log.warn("Unmount timeout for volume={} after {}s — abandoning", volumeId, unmountTimeoutSec);
                future.cancel(true);
            } catch (ExecutionException ee) {
                log.warn("Unmount failed for volume={}: {}", volumeId,
                        ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during unmount for volume={}", volumeId);
                future.cancel(true);
            }
        } finally {
            unmountExecutor.shutdownNow();
        }

        io.phaseEnd("unmount", ok ? "ok" : "failed", "");
    }

    /** Opens a phase, runs the body, closes with ok/failed based on the returned success flag. */
    private static boolean runPhase(TaskIO io, String id, String label, Supplier<Boolean> body) {
        io.phaseStart(id, label);
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(body.get());
        } catch (RuntimeException e) {
            io.phaseLog(id, "Phase threw: " + e.getMessage());
            ok = false;
        }
        io.phaseEnd(id, ok ? "ok" : "failed", "");
        return ok;
    }
}
