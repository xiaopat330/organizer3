package com.organizer3.utilities.task;

import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;
import com.organizer3.shell.io.Spinner;

import java.util.List;
import java.util.Optional;

/**
 * A {@link CommandIO} that forwards every call into a phase of a {@link TaskIO} stream.
 *
 * <p>Used by {@link CommandInvoker} to run existing commands inside the task-layer event
 * pipeline without modifying them. Commands emit {@code println} and progress calls exactly
 * as they do in the shell; this adapter translates them into {@code TaskEvent.PhaseLog} and
 * {@code TaskEvent.PhaseProgress} events attached to a caller-supplied phase id.
 *
 * <p>Interactive {@link #pick} returns empty, matching {@link com.organizer3.shell.io.PlainCommandIO}.
 * Tasks must not invoke commands that prompt the user.
 */
public final class BufferingCommandIO implements CommandIO {

    private final TaskIO taskIO;
    private final String phaseId;

    public BufferingCommandIO(TaskIO taskIO, String phaseId) {
        this.taskIO = taskIO;
        this.phaseId = phaseId;
    }

    @Override
    public void println(String message) {
        taskIO.phaseLog(phaseId, message == null ? "" : message);
    }

    @Override
    public void println() {
        taskIO.phaseLog(phaseId, "");
    }

    @Override
    public void printlnAnsi(String message) {
        taskIO.phaseLog(phaseId, stripAnsi(message));
    }

    @Override
    public void status(String message) {
        // Status updates in the shell overwrite a single persistent line; in the task layer
        // they are meaningful progress signals. Emit as indeterminate progress with detail.
        taskIO.phaseProgress(phaseId, 0, -1, message);
    }

    @Override
    public void clearStatus() {
        // No-op: there's no persistent status line to clear in the task model.
    }

    @Override
    public Spinner startSpinner(String label) {
        taskIO.phaseProgress(phaseId, 0, -1, label);
        return new Spinner() {
            @Override public void setStatus(String message) {
                taskIO.phaseProgress(phaseId, 0, -1, message);
            }
            @Override public void close() { /* no end-of-spinner event; next progress/log supersedes */ }
        };
    }

    @Override
    public Progress startProgress(String label, int total) {
        taskIO.phaseProgress(phaseId, 0, total, label);
        return new Progress() {
            int current = 0;
            String currentLabel = label;
            @Override public void advance() { advance(1); }
            @Override public void advance(int n) {
                current += n;
                taskIO.phaseProgress(phaseId, current, total, currentLabel);
            }
            @Override public void setLabel(String newLabel) {
                currentLabel = newLabel;
                taskIO.phaseProgress(phaseId, current, total, newLabel);
            }
            @Override public void close() { /* no explicit close event */ }
        };
    }

    @Override
    public Optional<String> pick(List<String> items) {
        return Optional.empty();
    }

    private static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\\[[;\\d]*m", "");
    }
}
