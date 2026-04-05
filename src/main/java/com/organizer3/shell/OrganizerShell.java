package com.organizer3.shell;

import com.organizer3.command.Command;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * The interactive REPL shell.
 *
 * Wires JLine3 (terminal + line reader) to the command dispatcher.
 * Commands are registered by name and looked up on each input line.
 */
public class OrganizerShell {
    private static final Logger log = LoggerFactory.getLogger(OrganizerShell.class);

    private final SessionContext session;
    private final Map<String, Command> commands;
    private final PromptBuilder promptBuilder;

    public OrganizerShell(SessionContext session, List<Command> commands) {
        this.session = session;
        this.promptBuilder = new PromptBuilder();
        this.commands = new java.util.HashMap<>();
        for (Command cmd : commands) {
            this.commands.put(cmd.name(), cmd);
            cmd.aliases().forEach(alias -> this.commands.put(alias, cmd));
        }
    }

    public void run() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, ".organizer_history")
                    .build();

            PrintWriter out = terminal.writer();
            out.println("Organizer3 — type 'help' for available commands");
            out.flush();

            while (session.isRunning()) {
                String line;
                try {
                    line = reader.readLine(promptBuilder.build(session));
                } catch (UserInterruptException e) {
                    // Ctrl+C — cancel current line, loop again
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D — exit
                    break;
                }

                if (line == null || line.isBlank()) continue;

                dispatch(line.trim(), out);
                out.flush();
            }
        } catch (IOException e) {
            log.error("Terminal error", e);
        }
    }

    private void dispatch(String line, PrintWriter out) {
        String[] parts = line.split("\\s+");
        String name = parts[0].toLowerCase();

        Command cmd = commands.get(name);
        if (cmd != null) {
            try {
                cmd.execute(parts, session, out);
            } catch (Exception e) {
                out.println("Error: " + e.getMessage());
                log.error("Command '{}' threw an exception", name, e);
            }
        } else {
            out.println("Unknown command: " + name + "  (type 'help' for available commands)");
        }
    }
}
