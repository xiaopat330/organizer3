package com.organizer3.shell.io;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/**
 * Plain-text implementation of {@link CommandIO} for tests and non-TTY contexts.
 *
 * <p>Message output goes directly to the wrapped {@link PrintWriter}.
 * Status, spinner, and progress calls are all no-ops — there is no terminal to animate.
 * This keeps tests simple: capture output with a {@code StringWriter}, assert on text content.
 */
public class PlainCommandIO implements CommandIO {

    private static final Spinner  NO_OP_SPINNER  = new NoOpSpinner();
    private static final Progress NO_OP_PROGRESS = new NoOpProgress();

    private final PrintWriter out;

    public PlainCommandIO(PrintWriter out) {
        this.out = out;
    }

    @Override
    public void println(String message) {
        out.println(message);
    }

    @Override
    public void printlnAnsi(String message) {
        // Strip ANSI escape codes for non-terminal output
        out.println(message.replaceAll("\033\\[[0-9;]*m", ""));
    }

    @Override
    public void println() {
        out.println();
    }

    @Override
    public void status(String message) {
        // no-op
    }

    @Override
    public void clearStatus() {
        // no-op
    }

    @Override
    public Spinner startSpinner(String label) {
        return NO_OP_SPINNER;
    }

    @Override
    public Progress startProgress(String label, int total) {
        return NO_OP_PROGRESS;
    }

    @Override
    public Optional<String> pick(List<String> items) {
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // No-op implementations (singletons — stateless)
    // -------------------------------------------------------------------------

    private static final class NoOpSpinner implements Spinner {
        @Override public void setStatus(String message) {}
        @Override public void close() {}
    }

    private static final class NoOpProgress implements Progress {
        @Override public void advance() {}
        @Override public void advance(int n) {}
        @Override public void setLabel(String label) {}
        @Override public void close() {}
    }
}
