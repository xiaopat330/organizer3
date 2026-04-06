package com.organizer3.shell.io;

import java.util.List;
import java.util.Optional;

/**
 * The single channel for all command output and status display.
 *
 * <p>Replaces {@code PrintWriter out} in the command pipeline. Commands use this interface
 * for all user-facing output so that the shell layer can route messages and status updates
 * to the appropriate terminal regions without commands knowing anything about the terminal.
 *
 * <p>There are two output paths:
 * <ul>
 *   <li><b>Message output</b> ({@link #println}) — scrolling text that accumulates in the
 *       terminal's scroll buffer above the prompt.</li>
 *   <li><b>Status area</b> ({@link #status}, {@link #startSpinner}, {@link #startProgress}) —
 *       a persistent line at the bottom of the terminal that is overwritten in-place.
 *       Only one activity (spinner or progress) can be active at a time.</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JLineCommandIO} — live terminal with animated spinner, progress bar,
 *       and persistent status via JLine3's {@code Status} facility.</li>
 *   <li>{@link PlainCommandIO} — plain {@code PrintWriter} wrapper; spinner and progress
 *       are no-ops. Used in tests and non-TTY contexts.</li>
 * </ul>
 */
public interface CommandIO {

    // -------------------------------------------------------------------------
    // Message output
    // -------------------------------------------------------------------------

    /** Prints a line to the scrolling output area. */
    void println(String message);

    /** Prints a blank line to the scrolling output area. */
    void println();

    /**
     * Prints a line that may contain ANSI escape sequences (e.g. {@code \033[32m}).
     * Live terminal implementations render the colors; plain implementations strip them.
     */
    default void printlnAnsi(String message) {
        println(message);
    }

    // -------------------------------------------------------------------------
    // Status area
    // -------------------------------------------------------------------------

    /**
     * Sets the persistent status line text. Replaces any previous status text.
     * Has no effect when a spinner or progress bar is active.
     */
    void status(String message);

    /** Clears the persistent status line. */
    void clearStatus();

    // -------------------------------------------------------------------------
    // Tracked activity (one active at a time)
    // -------------------------------------------------------------------------

    /**
     * Starts an indeterminate spinner in the status area with an initial label.
     * The spinner animates until {@link Spinner#close()} is called.
     *
     * <p>Use for operations where the total duration is unknown (e.g. network connects).
     * The caller updates the label via {@link Spinner#setStatus(String)} as phases change.
     *
     * <p>Must be closed before starting another activity.
     */
    Spinner startSpinner(String label);

    /**
     * Starts a determinate progress bar in the status area.
     * The bar updates on each {@link Progress#advance()} call.
     *
     * <p>Use for operations with a countable number of items (e.g. title folders during sync).
     *
     * <p>Must be closed before starting another activity.
     */
    Progress startProgress(String label, int total);

    /**
     * Presents an interactive list picker and returns the selected item.
     *
     * <p>On a live terminal, renders the items with a movable cursor; the user navigates
     * with arrow keys, confirms with Enter, and cancels with Escape or 'q'.
     *
     * <p>On plain/non-TTY terminals (tests, piped input), always returns empty.
     */
    Optional<String> pick(List<String> items);
}
