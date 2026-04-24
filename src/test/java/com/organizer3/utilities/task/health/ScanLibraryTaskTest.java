package com.organizer3.utilities.task.health;

import com.organizer3.utilities.health.LibraryHealthCheck;
import com.organizer3.utilities.health.LibraryHealthReportStore;
import com.organizer3.utilities.health.LibraryHealthService;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScanLibraryTaskTest {

    @TempDir Path tempDir;

    private LibraryHealthService service(LibraryHealthCheck... checks) {
        return new LibraryHealthService(List.of(checks),
                new LibraryHealthReportStore(tempDir.resolve("health.json")));
    }

    @Test
    void emitsOnePhasePerCheckAndCompletesOk() throws Exception {
        LibraryHealthCheck a = stub("a", 0);
        LibraryHealthCheck b = stub("b", 2);
        LibraryHealthService svc = service(a, b);
        Task task = new ScanLibraryTask(svc);
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(ScanLibraryTask.ID, new TaskInputs(Map.of()));
            awaitCompletion(run);
            assertEquals(TaskRun.Status.OK, run.status());

            long phasesStarted = run.eventSnapshot().stream()
                    .filter(e -> e instanceof TaskEvent.PhaseStarted).count();
            long phasesEnded = run.eventSnapshot().stream()
                    .filter(e -> e instanceof TaskEvent.PhaseEnded).count();
            assertEquals(2, phasesStarted);
            assertEquals(2, phasesEnded);

            // Service should have recorded a latest report with the expected totals.
            assertEquals(0, svc.latest().get().checks().get("a").result().total());
            assertEquals(2, svc.latest().get().checks().get("b").result().total());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void failingCheckMarksPhaseFailedButOtherChecksStillRun() throws Exception {
        LibraryHealthCheck boom = new LibraryHealthCheck() {
            @Override public String id() { return "boom"; }
            @Override public String label() { return "Boom"; }
            @Override public String description() { return ""; }
            @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }
            @Override public CheckResult run() { throw new RuntimeException("boom"); }
        };
        LibraryHealthCheck good = stub("good", 1);
        LibraryHealthService svc = service(boom, good);
        Task task = new ScanLibraryTask(svc);
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(ScanLibraryTask.ID, new TaskInputs(Map.of()));
            awaitCompletion(run);
            // One phase failed + one ok → PARTIAL at the task level.
            assertEquals(TaskRun.Status.PARTIAL, run.status());
            boolean anyFailed = run.eventSnapshot().stream()
                    .anyMatch(e -> e instanceof TaskEvent.PhaseEnded pe && "failed".equals(pe.status()));
            assertTrue(anyFailed);
        } finally {
            runner.shutdown();
        }
    }

    private static LibraryHealthCheck stub(String id, int total) {
        return new LibraryHealthCheck() {
            @Override public String id() { return id; }
            @Override public String label() { return id.toUpperCase(); }
            @Override public String description() { return ""; }
            @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }
            @Override public CheckResult run() {
                return new CheckResult(total, List.of());
            }
        };
    }

    private static void awaitCompletion(TaskRun run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (run.status() == TaskRun.Status.RUNNING) {
            if (System.nanoTime() > deadline) fail("task did not complete");
            Thread.sleep(10);
        }
    }
}
