package com.organizer3.utilities.task;

/**
 * A user-facing maintenance workflow — the unit of work behind a button on a Utilities screen
 * and the unit of exposure for MCP. Tasks compose existing commands; they do not duplicate
 * command logic.
 *
 * <p>Tasks run on a background thread managed by {@link TaskRunner}; implementations must not
 * assume thread affinity with any callers. All output must flow through {@link TaskIO} — tasks
 * never write to stdout, never touch {@code CommandIO} directly (use {@code CommandInvoker}),
 * and never interact with the user's live shell session.
 */
public interface Task {

    TaskSpec spec();

    /**
     * Runs the task end-to-end. Any exception thrown propagates to {@link TaskRunner} which
     * records the task as failed and emits a {@code TaskEnded} event. Tasks should prefer
     * per-phase try/catch so one phase's failure can be reported while subsequent phases
     * still run (e.g., unmount-after-sync-failure).
     */
    void run(TaskInputs inputs, TaskIO io) throws Exception;
}
