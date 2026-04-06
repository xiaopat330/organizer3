package com.organizer3.shell.io;

/**
 * Handle for an active progress bar in the status area.
 *
 * <p>The bar re-renders on each {@link #advance()} call.
 * Close when work is complete to clear the status area.
 *
 * <p>Usage:
 * <pre>{@code
 * try (Progress progress = io.startProgress("Syncing", folders.size())) {
 *     for (Path folder : folders) {
 *         process(folder);
 *         progress.advance();
 *         progress.setLabel(folder.getFileName().toString());
 *     }
 * }
 * io.println("Done: " + count + " titles indexed.");
 * }</pre>
 */
public interface Progress extends AutoCloseable {

    /** Advances the counter by 1 and re-renders the bar. */
    void advance();

    /** Advances the counter by {@code n} and re-renders the bar. */
    void advance(int n);

    /**
     * Updates the description shown beside the bar and re-renders.
     * Useful for showing which item is currently being processed.
     */
    void setLabel(String label);

    /** Finishes the progress bar and clears the status area. */
    @Override
    void close();
}
