package com.organizer3.utilities.task;

import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskRunnerTest {

    private static final TaskSpec SPEC = new TaskSpec("test.noop", "Noop", "for tests", List.of());

    @Test
    void runsTaskAndRecordsLifecycleEvents() throws Exception {
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) {
                io.phaseStart("p1", "Phase 1");
                io.phaseLog("p1", "hello");
                io.phaseEnd("p1", "ok", "done");
            }
        };
        TaskRegistry registry = new TaskRegistry(List.of(task));
        TaskRunner runner = new TaskRunner(registry);
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            awaitCompletion(run);

            assertEquals(TaskRun.Status.OK, run.status());
            List<TaskEvent> events = run.eventSnapshot();
            assertInstanceOf(TaskEvent.TaskStarted.class, events.get(0));
            assertInstanceOf(TaskEvent.PhaseStarted.class, events.get(1));
            assertInstanceOf(TaskEvent.PhaseLog.class,    events.get(2));
            assertInstanceOf(TaskEvent.PhaseEnded.class,  events.get(3));
            assertInstanceOf(TaskEvent.TaskEnded.class,   events.get(4));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void inferredStatusIsPartialWhenSomePhasesFail() throws Exception {
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) {
                io.phaseStart("a", "A"); io.phaseEnd("a", "ok", "");
                io.phaseStart("b", "B"); io.phaseEnd("b", "failed", "boom");
            }
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            awaitCompletion(run);
            assertEquals(TaskRun.Status.PARTIAL, run.status());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void uncaughtExceptionFailsTheTask() throws Exception {
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) {
                throw new RuntimeException("kaboom");
            }
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            awaitCompletion(run);
            assertEquals(TaskRun.Status.FAILED, run.status());
            assertTrue(run.summary().contains("kaboom"));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void subscribersReceiveEventsInOrder() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) throws InterruptedException {
                gate.await();
                io.phaseStart("p", "P");
                io.phaseEnd("p", "ok", "");
            }
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            List<TaskEvent> received = new ArrayList<>();
            run.subscribe(received::add);
            gate.countDown();
            awaitCompletion(run);
            assertTrue(received.size() >= 3, "expected start/phase/end events");
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void commandInvokerRoutesOutputIntoPhaseEvents() throws Exception {
        Command fakeCmd = new Command() {
            @Override public String name() { return "fake"; }
            @Override public String description() { return ""; }
            @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
                io.println("line-a");
                io.println("line-b");
            }
        };
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) {
                CommandInvoker invoker = new CommandInvoker(Map.of("fake", fakeCmd), new SessionContext());
                io.phaseStart("p1", "Phase 1");
                invoker.invoke("p1", "fake", new String[]{"fake"}, io);
                io.phaseEnd("p1", "ok", "");
            }
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            awaitCompletion(run);
            long logCount = run.eventSnapshot().stream()
                    .filter(e -> e instanceof TaskEvent.PhaseLog)
                    .count();
            assertEquals(2, logCount);
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void rejectsSecondTaskWhileFirstIsRunning() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Task slow = new Task() {
            @Override public TaskSpec spec() { return new TaskSpec("slow", "Slow", "", List.of()); }
            @Override public void run(TaskInputs inputs, TaskIO io) throws InterruptedException {
                releaseFirst.await();
            }
        };
        Task fast = new Task() {
            @Override public TaskSpec spec() { return new TaskSpec("fast", "Fast", "", List.of()); }
            @Override public void run(TaskInputs inputs, TaskIO io) {}
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(slow, fast)));
        try {
            TaskRun first = runner.start("slow", new TaskInputs(Map.of()));
            assertThrows(TaskRunner.TaskInFlightException.class,
                    () -> runner.start("fast", new TaskInputs(Map.of())));
            releaseFirst.countDown();
            awaitCompletion(first);
            // After completion the lock releases and the next start succeeds.
            TaskRun second = runner.start("fast", new TaskInputs(Map.of()));
            awaitCompletion(second);
            assertEquals(TaskRun.Status.OK, second.status());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void cooperativeCancelMarksTaskCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) throws InterruptedException {
                io.phaseStart("p", "P");
                started.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!io.isCancellationRequested()) {
                    if (System.nanoTime() > deadline) fail("cancel flag never flipped");
                    Thread.sleep(5);
                }
                io.phaseEnd("p", "ok", "cancelled at user request");
            }
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(TaskRunner.CancelOutcome.REQUESTED, runner.cancel(run.runId()));
            assertEquals(TaskRunner.CancelOutcome.ALREADY_REQUESTED, runner.cancel(run.runId()));
            awaitCompletion(run);
            assertEquals(TaskRun.Status.CANCELLED, run.status());
            assertTrue(run.isCancellationRequested());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void cancelOnFailedTaskPreservesFailedStatus() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) throws InterruptedException {
                io.phaseStart("p", "P");
                observed.await();
                io.phaseEnd("p", "failed", "boom");
            }
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            runner.cancel(run.runId());
            observed.countDown();
            awaitCompletion(run);
            // Cancel-plus-failure reports as FAILED so the real problem surfaces in the UI.
            assertEquals(TaskRun.Status.FAILED, run.status());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void cancelUnknownAndEndedRunsAreIdempotent() throws Exception {
        Task task = new Task() {
            @Override public TaskSpec spec() { return SPEC; }
            @Override public void run(TaskInputs inputs, TaskIO io) {}
        };
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            assertEquals(TaskRunner.CancelOutcome.NOT_FOUND, runner.cancel("does-not-exist"));
            TaskRun run = runner.start("test.noop", new TaskInputs(Map.of()));
            awaitCompletion(run);
            assertEquals(TaskRunner.CancelOutcome.ALREADY_ENDED, runner.cancel(run.runId()));
        } finally {
            runner.shutdown();
        }
    }

    private static void awaitCompletion(TaskRun run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (run.status() == TaskRun.Status.RUNNING) {
            if (System.nanoTime() > deadline) fail("Task did not complete in time");
            Thread.sleep(10);
        }
    }
}
