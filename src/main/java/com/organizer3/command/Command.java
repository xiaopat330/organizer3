package com.organizer3.command;

import com.organizer3.shell.SessionContext;

import java.io.PrintWriter;
import java.util.List;

/**
 * A single interactive shell command.
 *
 * Commands receive parsed args (args[0] is the command name), the current session,
 * and a writer for output. Using PrintWriter for output keeps commands testable —
 * tests can pass a StringWriter to capture output without a real terminal.
 */
public interface Command {
    String name();
    String description();
    void execute(String[] args, SessionContext ctx, PrintWriter out);

    default List<String> aliases() {
        return List.of();
    }
}
