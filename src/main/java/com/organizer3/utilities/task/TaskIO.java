package com.organizer3.utilities.task;

/**
 * The task-layer analog of {@link com.organizer3.shell.io.CommandIO}. A {@link Task} emits
 * structured events through this interface; the framework routes them to subscribers (the web
 * UI over SSE, the MCP response buffer, test assertions, etc.).
 *
 * <p>Unlike {@code CommandIO}, this interface is deliberately narrow: no terminal concerns,
 * no ANSI, no interactive picks. Tasks compose commands via {@code CommandInvoker}, which
 * adapts the command's {@code CommandIO} calls into {@code TaskIO} events.
 */
public interface TaskIO {

    /** Opens a named phase. All subsequent log/progress events until {@link #phaseEnd} attach to it. */
    void phaseStart(String phaseId, String label);

    /** Optional progress update for the active phase. Pass {@code total = -1} for indeterminate. */
    void phaseProgress(String phaseId, int current, int total, String detail);

    /** A line of raw output from the phase (typically a command's {@code println}). */
    void phaseLog(String phaseId, String line);

    /** Closes the active phase. {@code status} is {@code "ok"} or {@code "failed"}. */
    void phaseEnd(String phaseId, String status, String summary);
}
