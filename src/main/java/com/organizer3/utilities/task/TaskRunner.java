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

    public TaskRunner(TaskRegistry registry) {
        this.registry = registry;
        this.executor = Executors.newCachedThreadPool(namedDaemonFactory("utilities-task"));
    }

    /**
     * Starts the task identified by {@code taskId}. Returns the {@link TaskRun} that will
     * accumulate events; the caller can immediately inspect {@code runId()} and subscribe.
     * Throws {@link IllegalArgumentException} if no such task is registered.
     */
    public TaskRun start(String taskId, TaskInputs inputs) {
        Task task = registry.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
        TaskRun run = new TaskRun(taskId);
        runs.put(run.runId(), run);
        TaskIO io = new RecordingTaskIO(run, taskId);

        log.info("Task {} started (run={}, inputs={})", taskId, run.runId(), inputs.values());
        executor.submit(() -> executeSafely(task, inputs, run, io));
        return run;
    }

    public Optional<TaskRun> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

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
        if (anyPhaseFailed && anyPhaseOk) return TaskRun.Status.PARTIAL;
        if (anyPhaseFailed) return TaskRun.Status.FAILED;
        return TaskRun.Status.OK;
    }

    private static String buildSummary(TaskRun.Status s, Duration elapsed) {
        String label = switch (s) {
            case OK -> "Task complete";
            case FAILED -> "Task failed";
            case PARTIAL -> "Task completed with failures";
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
            run.append(new TaskEvent.PhaseProgress(Instant.now(), phaseId, current, total,
                    detail == null ? "" : detail));
        }

        @Override
        public void phaseLog(String phaseId, String line) {
            run.append(new TaskEvent.PhaseLog(Instant.now(), phaseId, line == null ? "" : line));
        }

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
