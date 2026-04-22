package com.organizer3.utilities.task.volume;

import com.organizer3.shell.SessionContext;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;
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
 */
public final class SyncVolumeTask implements Task {

    public static final String ID = "volume.sync";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Sync volume",
            "Bring a volume's index and covers fully up to date.",
            List.of(new TaskSpec.InputSpec("volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true))
    );

    private final Supplier<CommandInvoker> invokerFactory;

    public SyncVolumeTask(Supplier<CommandInvoker> invokerFactory) {
        this.invokerFactory = invokerFactory;
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
            runPhase(io, "syncTitles", "Sync titles", () ->
                    invoker.invoke("syncTitles", "sync all", new String[]{"sync all"}, io));

            runPhase(io, "syncCovers", "Sync covers", () ->
                    invoker.invoke("syncCovers", "sync covers", new String[]{"sync covers"}, io));
        } else {
            io.phaseLog("mount", "Skipping sync phases because mount failed.");
        }

        // Unmount runs unconditionally so we never leave a dangling connection.
        runPhase(io, "unmount", "Unmount volume", () ->
                invoker.invoke("unmount", "unmount", new String[]{"unmount"}, io));
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
