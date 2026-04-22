package com.organizer3.utilities.task;

import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;

import java.util.Map;

/**
 * Looks up a registered command by name and invokes it inside a task phase, routing its
 * {@code CommandIO} output into the surrounding {@link TaskIO} stream. This is the only
 * way a task runs a command — it never calls {@link Command#execute} directly, so all
 * output is captured and every invocation is wrapped in a phase lifecycle.
 *
 * <p>The invoker holds its own {@link SessionContext} for the duration of a task run. The
 * task must set up session state (mount state, dry-run flag) via the commands it invokes
 * rather than reaching into the context directly; keeping the context private to the
 * invoker ensures tasks don't leak state to the user's live shell session.
 */
public final class CommandInvoker {

    private final Map<String, Command> commandsByName;
    private final SessionContext session;

    public CommandInvoker(Map<String, Command> commandsByName, SessionContext session) {
        this.commandsByName = Map.copyOf(commandsByName);
        this.session = session;
    }

    public SessionContext session() {
        return session;
    }

    /**
     * Resolves {@code commandName} (possibly multi-word, e.g. {@code "sync all"}) and runs it
     * inside the phase identified by {@code phaseId}. Returns {@code true} on success, {@code
     * false} on missing command or exception. Emits a {@code PhaseLog} line describing any
     * exception; the caller is responsible for phaseStart / phaseEnd.
     *
     * <p>{@code args} should include the command token(s) at the head, matching the contract
     * of {@link Command#execute} (e.g., {@code {"mount", "a"}} for {@code mount a}).
     */
    public boolean invoke(String phaseId, String commandName, String[] args, TaskIO io) {
        Command cmd = commandsByName.get(commandName);
        if (cmd == null) {
            io.phaseLog(phaseId, "Unknown command: " + commandName);
            return false;
        }
        BufferingCommandIO bufferingIO = new BufferingCommandIO(io, phaseId);
        try {
            cmd.execute(args, session, bufferingIO);
            return true;
        } catch (RuntimeException e) {
            io.phaseLog(phaseId, "Exception in " + commandName + ": " + e.getMessage());
            return false;
        }
    }
}
