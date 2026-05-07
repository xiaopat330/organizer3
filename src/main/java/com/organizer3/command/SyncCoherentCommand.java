package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Shell command: {@code sync coherent}
 *
 * <p>Enqueues {@link com.organizer3.utilities.task.volume.CoherentMultiVolumeSyncTask}, which
 * scans every configured volume in turn and defers the orphan-prune step until all volumes
 * have been observed. This prevents a title that was manually moved from volume A to volume B
 * from appearing as a false orphan when only A is synced.
 *
 * <p>Unlike the per-volume sync commands, this command does not require a volume to be mounted.
 * It manages its own mount/unmount lifecycle per volume via the task machinery.
 *
 * <p>The command prints the task run ID and returns immediately. Progress can be observed
 * via the web UI or {@code mcp__organizer3__get_task_run_status}.
 *
 * <p>A {@link Supplier} is used for the {@link TaskRunner} to allow the command to be
 * constructed before the TaskRunner is initialized (Application.java wiring order).
 */
@Slf4j
public class SyncCoherentCommand implements Command {

    private static final String TASK_ID =
            com.organizer3.utilities.task.volume.CoherentMultiVolumeSyncTask.ID;

    private final Supplier<TaskRunner> taskRunnerSupplier;

    public SyncCoherentCommand(Supplier<TaskRunner> taskRunnerSupplier) {
        this.taskRunnerSupplier = taskRunnerSupplier;
    }

    @Override
    public String name() { return "sync coherent"; }

    @Override
    public String description() {
        return "Enqueue a coherent multi-volume sync — scans every configured volume in turn "
             + "and evaluates orphans once after all volumes have been observed. "
             + "Does not require a mounted volume. Returns immediately with a task run ID.";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        TaskRunner taskRunner = taskRunnerSupplier.get();
        try {
            TaskRun run = taskRunner.start(TASK_ID, new TaskInputs(Map.of()));
            io.println("Coherent sync enqueued — run ID: " + run.runId());
            io.println("Monitor progress via the web UI (Tools → Volumes) or:");
            io.println("  mcp__organizer3__get_task_run_status runId=" + run.runId());
        } catch (TaskRunner.TaskInFlightException e) {
            io.println("Cannot start coherent sync: another utility task is already running.");
            io.println("  Running task: " + e.runningTaskId + " (run=" + e.runningRunId + ")");
            io.println("  Wait for it to finish, then retry.");
        } catch (IllegalArgumentException e) {
            // Task id not registered — should not happen at runtime
            log.error("Coherent sync task not registered: {}", e.getMessage(), e);
            io.println("Coherent sync is not available: " + e.getMessage());
        }
    }
}
