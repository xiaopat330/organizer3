package com.organizer3.shell.io;

/**
 * Handle for an active indeterminate spinner in the status area.
 *
 * <p>The spinner animates automatically until {@link #close()} is called.
 * Update the label as the operation progresses through phases.
 *
 * <p>Usage:
 * <pre>{@code
 * try (Spinner spinner = io.startSpinner("Connecting...")) {
 *     connector.connect(host, spinner::setStatus);
 * }
 * io.println("Connected.");
 * }</pre>
 */
public interface Spinner extends AutoCloseable {

    /**
     * Updates the label shown next to the spinner frame.
     * Safe to call from any thread.
     */
    void setStatus(String message);

    /** Stops the spinner and clears the status area. */
    @Override
    void close();
}
