package com.organizer3.utilities.task;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts tasks on a background executor and tracks their runs. A single shared instance is
 * created at startup and injected into the web routes layer.
 *
 * <p>Runs are retained in memory so that clients reconnecting after the final event can still
 * fetch the terminal state and event history. In Phase 1 we don't bound this — the user is
 * expected to launch Utilities tasks occasionally, not in bulk. An eviction policy can be
 * added later if memory becomes a concern.
 */
@Slf4j
public final class TaskRunner {

    private final TaskRegistry registry;
    private final ExecutorService executor;
    private final Map<String, TaskRun> runs = new ConcurrentHashMap<>();
    /**
     * Single-slot global lock. Utility tasks are atomic — at most one run is in flight at any
     * time, across all tasks and all clients. The atomic reference ensures a clean compare-and-set
     * on {@link #start} and can't race with concurrent callers (browser tab + MCP + etc.).
     */
    private final java.util.concurrent.atomic.AtomicReference<TaskRun> activeRun =
            new java.util.concurrent.atomic.AtomicReference<>();

    public TaskRunner(TaskRegistry registry) {
        this.registry = registry;
        this.executor = Executors.newCachedThreadPool(namedDaemonFactory("utilities-task"));
    }

    /**
     * Starts the task identified by {@code taskId}. Returns the {@link TaskRun} that will
     * accumulate events; the caller can immediately inspect {@code runId()} and subscribe.
     *
     * <p>Fails with {@link TaskInFlightException} if any other utility task is already running.
     * Utility tasks are atomic by policy — see {@code feedback_utilities_atomic}. Relax the lock
     * only when truly independent task classes emerge.
     */
    public TaskRun start(String taskId, TaskInputs inputs) {
        Task task = registry.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
        TaskRun run = new TaskRun(taskId);
        if (!activeRun.compareAndSet(null, run)) {
            TaskRun current = activeRun.get();
            throw new TaskInFlightException(current != null ? current.taskId() : "unknown",
                    current != null ? current.runId() : "unknown");
        }
        runs.put(run.runId(), run);
        TaskIO io = new RecordingTaskIO(run, taskId);

        log.info("Task {} started (run={}, inputs={})", taskId, run.runId(), inputs.values());
        executor.submit(() -> executeSafely(task, inputs, run, io));
        return run;
    }

    /** True when any utility task is running. Exposed for state endpoints and UI polling. */
    public Optional<TaskRun> currentlyRunning() {
        return Optional.ofNullable(activeRun.get());
    }

    /** Thrown by {@link #start} when the single-task-at-a-time policy rejects a new task. */
    public static class TaskInFlightException extends RuntimeException {
        public final String runningTaskId;
        public final String runningRunId;
        public TaskInFlightException(String runningTaskId, String runningRunId) {
            super("A utility task (" + runningTaskId + ", run=" + runningRunId
                    + ") is already running. Wait for it to finish.");
            this.runningTaskId = runningTaskId;
            this.runningRunId = runningRunId;
        }
    }

    public Optional<TaskRun> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /**
     * Request cancellation of a run. Returns the outcome so the HTTP layer can map it to a
     * response: {@code NOT_FOUND} → 404, anything else → 204. Double-cancel and cancel of an
     * already-ended run are no-ops (idempotent).
     */
    public CancelOutcome cancel(String runId) {
        TaskRun run = runs.get(runId);
        if (run == null) return CancelOutcome.NOT_FOUND;
        if (run.status() != TaskRun.Status.RUNNING) return CancelOutcome.ALREADY_ENDED;
        boolean flipped = run.requestCancellation();
        if (flipped) {
            log.info("Task {} cancel requested (run={})", run.taskId(), run.runId());
        }
        return flipped ? CancelOutcome.REQUESTED : CancelOutcome.ALREADY_REQUESTED;
    }

    public enum CancelOutcome { REQUESTED, ALREADY_REQUESTED, ALREADY_ENDED, NOT_FOUND }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void executeSafely(Task task, TaskInputs inputs, TaskRun run, TaskIO io) {
        Instant t0 = Instant.now();
        run.append(new TaskEvent.TaskStarted(t0));
        try {
            task.run(inputs, io);
            TaskRun.Status finalStatus = inferTerminalStatus(run);
            Duration elapsed = Duration.between(t0, Instant.now());
            String summary = buildSummary(finalStatus, elapsed);
            run.append(new TaskEvent.TaskEnded(Instant.now(), statusWire(finalStatus), summary));
            run.markEnded(finalStatus, summary);
            log.info("Task {} ended status={} elapsed={} (run={})",
                    task.spec().id(), statusWire(finalStatus), formatElapsed(elapsed), run.runId());
        } catch (Exception e) {
            log.error("Task {} threw (run={})", task.spec().id(), run.runId(), e);
            String msg = "Task failed: " + e.getMessage();
            run.append(new TaskEvent.TaskEnded(Instant.now(), "failed", msg));
            run.markEnded(TaskRun.Status.FAILED, msg);
        } finally {
            // Release the atomic lock only if it still points to our run — defensive against
            // a hypothetical future change that clears it earlier.
            activeRun.compareAndSet(run, null);
        }
    }

    /** If any phase ended failed, mark task partial/failed. Otherwise ok. */
    private static TaskRun.Status inferTerminalStatus(TaskRun run) {
        boolean anyPhaseFailed = false;
        boolean anyPhaseOk = false;
        for (TaskEvent e : run.eventSnapshot()) {
            if (e instanceof TaskEvent.PhaseEnded pe) {
                if ("failed".equals(pe.status())) anyPhaseFailed = true;
                else if ("ok".equals(pe.status())) anyPhaseOk = true;
            }
        }
        // A cancel request overrides otherwise-clean outcomes. If phases also failed, FAILED wins
        // so the user sees what actually went wrong (e.g. unmount error) — cancellation-only is
        // CANCELLED, cancel-plus-failure is still FAILED.
        if (anyPhaseFailed) return anyPhaseOk ? TaskRun.Status.PARTIAL : TaskRun.Status.FAILED;
        if (run.isCancellationRequested()) return TaskRun.Status.CANCELLED;
        return TaskRun.Status.OK;
    }

    private static String buildSummary(TaskRun.Status s, Duration elapsed) {
        String label = switch (s) {
            case OK -> "Task complete";
            case FAILED -> "Task failed";
            case PARTIAL -> "Task completed with failures";
            case CANCELLED -> "Task cancelled";
            case RUNNING -> "Task still running";
        };
        return label + " (" + formatElapsed(elapsed) + ")";
    }

    private static String formatElapsed(Duration d) {
        long secs = d.toSeconds();
        if (secs < 60) return secs + "s";
        return (secs / 60) + "m " + (secs % 60) + "s";
    }

    private static String statusWire(TaskRun.Status s) {
        return switch (s) {
            case OK -> "ok";
            case FAILED -> "failed";
            case PARTIAL -> "partial";
            case CANCELLED -> "cancelled";
            case RUNNING -> "running";
        };
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    /** TaskIO implementation that appends events to a {@link TaskRun} and logs phase boundaries. */
    private static final class RecordingTaskIO implements TaskIO {
        private final TaskRun run;
        private final String taskId;
        private final Map<String, Instant> phaseStartedAt = new ConcurrentHashMap<>();
        /** Last logged decile (0–10) per phase, for throttled progress logging. */
        private final Map<String, Integer> lastLoggedDecile = new ConcurrentHashMap<>();

        RecordingTaskIO(TaskRun run, String taskId) {
            this.run = run;
            this.taskId = taskId;
        }

        @Override
        public void phaseStart(String phaseId, String label) {
            phaseStartedAt.put(phaseId, Instant.now());
            log.info("Task {} phase '{}' started (run={})", taskId, phaseId, run.runId());
            run.append(new TaskEvent.PhaseStarted(Instant.now(), phaseId, label));
        }

        @Override
        public void phaseProgress(String phaseId, int current, int total, String detail) {
            // Throttled progress log: one line per 10% advance, not per advance(1).
            if (total > 0) {
                int decile = Math.min(10, Math.max(0, (int) ((long) current * 10 / total)));
                Integer prev = lastLoggedDecile.get(phaseId);
                if (prev == null || decile > prev) {
                    lastLoggedDecile.put(phaseId, decile);
                    log.info("Task {} phase '{}' progress {}% ({}/{}) (run={})",
                            taskId, phaseId, decile * 10, current, total, run.runId());
                }
            }
            run.append(new TaskEvent.PhaseProgress(Instant.now(), phaseId, current, total,
                    detail == null ? "" : detail));
        }

        @Override
        public void phaseLog(String phaseId, String line) {
            run.append(new TaskEvent.PhaseLog(Instant.now(), phaseId, line == null ? "" : line));
        }

        @Override
        public boolean isCancellationRequested() { return run.isCancellationRequested(); }

        @Override
        public void phaseEnd(String phaseId, String status, String summary) {
            Instant started = phaseStartedAt.getOrDefault(phaseId, Instant.now());
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            log.info("Task {} phase '{}' ended status={} durationMs={} (run={})",
                    taskId, phaseId, status, durationMs, run.runId());
            run.append(new TaskEvent.PhaseEnded(Instant.now(), phaseId, status, durationMs,
                    summary == null ? "" : summary));
        }
    }
}
