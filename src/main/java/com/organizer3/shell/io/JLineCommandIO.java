package com.organizer3.shell.io;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

import java.util.List;

/**
 * Live terminal implementation of {@link CommandIO}.
 *
 * <p>Routes output through JLine3 so that scrolling messages and the persistent
 * status bar coexist cleanly:
 * <ul>
 *   <li>Message output ({@link #println}) → {@code terminal.writer().println()}, which
 *       JLine3 keeps above the status area when scroll regions are supported.</li>
 *   <li>Status area → {@code org.jline.utils.Status}, which reserves the bottom line
 *       of the terminal and redraws it whenever the terminal scrolls or resizes.</li>
 * </ul>
 *
 * <p>{@link #renderStatus} and {@link #clearStatusBar} are package-private so that
 * {@link JLineSpinner} and {@link JLineProgress} can update the status bar directly
 * without going through the public API.
 *
 * <p>Status writes are synchronized on {@code this} to allow the spinner's daemon thread
 * to call {@link #renderStatus} concurrently with the main command thread.
 */
public class JLineCommandIO implements CommandIO {

    private final Terminal terminal;
    private final Status status;

    public JLineCommandIO(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        // Status.getStatus creates (or returns an existing) status bar for this terminal.
        // Passing 'true' creates it if absent; may return null on dumb/non-ANSI terminals.
        this.status = Status.getStatus(terminal, true);
    }

    // -------------------------------------------------------------------------
    // Message output
    // -------------------------------------------------------------------------

    @Override
    public void println(String message) {
        synchronized (terminal) {
            terminal.writer().println(message);
            terminal.writer().flush();
        }
    }

    @Override
    public void printlnAnsi(String message) {
        synchronized (terminal) {
            AttributedString.fromAnsi(message).println(terminal);
            terminal.writer().flush();
        }
    }

    @Override
    public void println() {
        println("");
    }

    // -------------------------------------------------------------------------
    // Status area
    // -------------------------------------------------------------------------

    @Override
    public void status(String message) {
        renderStatus(List.of(message));
    }

    @Override
    public void clearStatus() {
        clearStatusBar();
    }

    // -------------------------------------------------------------------------
    // Tracked activity
    // -------------------------------------------------------------------------

    @Override
    public Spinner startSpinner(String label) {
        return new JLineSpinner(this, label);
    }

    @Override
    public Progress startProgress(String label, int total) {
        return new JLineProgress(this, label, total);
    }

    // -------------------------------------------------------------------------
    // Package-private: called by JLineSpinner and JLineProgress
    // -------------------------------------------------------------------------

    synchronized void renderStatus(List<String> lines) {
        if (status != null) {
            status.update(lines.stream()
                    .map(AttributedString::new)
                    .toList());
        }
    }

    synchronized void renderStatusAttributed(List<AttributedString> lines) {
        if (status != null) {
            status.update(lines);
        }
    }

    synchronized void clearStatusBar() {
        if (status != null) {
            status.update(List.of());
        }
    }
}
