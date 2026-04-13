package com.organizer3.shell;

import com.organizer3.command.Command;
import com.organizer3.shell.io.CommandIO;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and executes commands by name, shared between the interactive shell and
 * the web terminal.
 *
 * <p>Supports two-word command names (e.g. {@code "sync all"}, {@code "scan errors"}).
 * The first two tokens are checked as a compound key before falling back to the first
 * token alone.
 */
@Slf4j
public class CommandDispatcher {

    private final Map<String, Command> commands;

    public CommandDispatcher(List<Command> commandList) {
        this.commands = new HashMap<>();
        for (Command cmd : commandList) {
            commands.put(cmd.name(), cmd);
            cmd.aliases().forEach(alias -> commands.put(alias, cmd));
        }
    }

    /** The set of registered command names (including aliases and two-word names). */
    public Set<String> commandNames() {
        return commands.keySet();
    }

    /**
     * Parses {@code line}, resolves the command, and executes it.
     * Unknown commands print an error via {@code io}; exceptions are caught and logged.
     */
    public void dispatch(String line, SessionContext ctx, CommandIO io) {
        String[] parts = line.split("\\s+");
        String name = parts[0].toLowerCase();

        // Try two-word command first (e.g. "sync all", "scan errors")
        Command cmd = null;
        String[] cmdArgs = parts;
        if (parts.length >= 2) {
            String twoWord = name + " " + parts[1].toLowerCase();
            cmd = commands.get(twoWord);
            if (cmd != null) {
                name = twoWord;
                // Merge first two tokens into one arg, keep the rest
                cmdArgs = new String[parts.length - 1];
                cmdArgs[0] = twoWord;
                System.arraycopy(parts, 2, cmdArgs, 1, parts.length - 2);
            }
        }

        if (cmd == null) {
            cmd = commands.get(name);
        }

        if (cmd != null) {
            try {
                cmd.execute(cmdArgs, ctx, io);
            } catch (Exception e) {
                io.println("Error: " + e.getMessage());
                log.error("Command '{}' threw an exception", name, e);
            }
        } else {
            io.println("Unknown command: " + name + "  (type 'help' for available commands)");
        }
    }
}
