package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.util.List;

/**
 * A single interactive shell command.
 *
 * Commands receive parsed args (args[0] is the command name), the current session,
 * and a {@link CommandIO} for output. CommandIO routes scrolling messages above the
 * persistent status bar and provides spinner/progress primitives for long-running work.
 */
public interface Command {
    String name();
    String description();
    void execute(String[] args, SessionContext ctx, CommandIO io);

    default List<String> aliases() {
        return List.of();
    }
}
