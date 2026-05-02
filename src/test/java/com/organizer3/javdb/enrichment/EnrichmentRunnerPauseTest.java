package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbConfig;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Phase 6 task-aware pause mechanism in {@link EnrichmentRunner}.
 *
 * <p>The runner's {@link EnrichmentRunner#isPaused()} returns {@code true} when either:
 * <ul>
 *   <li>The manual {@code paused} flag is set (existing behaviour), or
 *   <li>A pause-issuing Utilities task (currently {@code enrichment.bulk_enrich_to_draft})
 *       is currently RUNNING as reported by {@link TaskRunner#currentlyRunning()}.
 * </ul>
 */
class EnrichmentRunnerPauseTest {

    private static final JavdbConfig CFG = JavdbConfig.DEFAULTS;

    private TaskRunner taskRunner;
    private EnrichmentRunner runner;

    @BeforeEach
    void setUp() {
        taskRunner = mock(TaskRunner.class);
        // Construct with null deps — isPaused() only accesses paused + taskRunner.
        runner = new EnrichmentRunner(
                CFG, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null);
        runner.setTaskRunner(taskRunner);
    }

    // ── 1. No pauses active ───────────────────────────────────────────────────

    @Test
    void notPausedByDefault() {
        when(taskRunner.currentlyRunning()).thenReturn(Optional.empty());

        assertFalse(runner.isPaused());
    }

    // ── 2. Manual pause ────────────────────────────────────────────────────────

    @Test
    void manualPause_pausesWhenNoTaskRunning() {
        when(taskRunner.currentlyRunning()).thenReturn(Optional.empty());

        runner.setPaused(true);

        assertTrue(runner.isPaused());
    }

    // ── 3. Task-induced pause ─────────────────────────────────────────────────

    @Test
    void taskInducedPause_whenBulkEnrichTaskIsRunning() {
        TaskRun fakeRun = mock(TaskRun.class);
        when(fakeRun.taskId()).thenReturn("enrichment.bulk_enrich_to_draft");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(fakeRun));

        // Manual paused flag is false, but task runner reports bulk-enrich is RUNNING → paused.
        assertTrue(runner.isPaused());
    }

    @Test
    void taskInducedPause_doesNotActivateForUnrelatedTask() {
        TaskRun unrelated = mock(TaskRun.class);
        when(unrelated.taskId()).thenReturn("volume.sync");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(unrelated));

        assertFalse(runner.isPaused());
    }

    // ── 4. setPaused(false) does not override an active bulk-enrich task ───────

    @Test
    void manualUnpause_doesNotOverrideTaskInducedPause() {
        // First manually pause the runner.
        runner.setPaused(true);
        assertTrue(runner.isPaused(), "Should be paused after setPaused(true)");

        // Now a bulk-enrich task starts running.
        TaskRun fakeRun = mock(TaskRun.class);
        when(fakeRun.taskId()).thenReturn("enrichment.bulk_enrich_to_draft");
        when(taskRunner.currentlyRunning()).thenReturn(Optional.of(fakeRun));

        // User tries to manually unpause — task is still running, so still paused.
        runner.setPaused(false);

        assertTrue(runner.isPaused(),
                "Runner must stay paused while bulk-enrich task is running, even after setPaused(false)");
    }

    // ── 5. Self-heal: task completes, runner resumes ───────────────────────────

    @Test
    void selfHeals_whenTaskCompletesAndCurrentlyRunningBecomesEmpty() {
        TaskRun fakeRun = mock(TaskRun.class);
        when(fakeRun.taskId()).thenReturn("enrichment.bulk_enrich_to_draft");
        when(taskRunner.currentlyRunning())
                .thenReturn(Optional.of(fakeRun))  // first call: task running
                .thenReturn(Optional.empty());       // second call: task done

        assertTrue(runner.isPaused(),  "Should be paused while task running");
        assertFalse(runner.isPaused(), "Should be unpaused after task ends");
    }

    // ── 6. Null taskRunner (before injection) ────────────────────────────────

    @Test
    void noTaskRunner_doesNotPause() {
        // A fresh runner with no setTaskRunner call.
        EnrichmentRunner runnerNoTr = new EnrichmentRunner(
                CFG, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null);
        // taskRunner is null → no NPE, no task-induced pause.
        assertFalse(runnerNoTr.isPaused());
    }
}
