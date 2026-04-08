package com.organizer3.shell;

import com.organizer3.command.Command;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.JLineCommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;

/**
 * The interactive REPL shell.
 *
 * Wires JLine3 (terminal + line reader) to the command dispatcher.
 * Commands are registered by name and looked up on each input line.
 *
 * On a real TTY, commands receive a {@link JLineCommandIO} that routes output above
 * a persistent status bar and supports animated spinner/progress display.
 * On a dumb terminal or non-TTY (e.g. tests piping stdin), commands receive a
 * {@link PlainCommandIO} that writes directly to the terminal writer.
 */
@Slf4j
public class OrganizerShell {
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
                    .completer(buildCompleter())
                    .build();

            CommandIO io = buildCommandIO(terminal, reader);
            PrintWriter out = terminal.writer();

            out.println("Organizer3 — type 'help' for available commands");
            out.flush();

            while (session.isRunning()) {
                String line;
                try {
                    line = reader.readLine(promptBuilder.build(session).toAnsi(terminal));
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (line == null || line.isBlank()) continue;

                dispatch(line.trim(), io);
                out.flush();
            }
        } catch (IOException e) {
            log.error("Terminal error", e);
        }
    }

    private Completer buildCompleter() {
        Set<String> singleWords = new TreeSet<>();
        Map<String, Set<String>> twoWordGroups = new java.util.LinkedHashMap<>();

        for (String key : commands.keySet()) {
            if (key.contains(" ")) {
                String[] parts = key.split(" ", 2);
                twoWordGroups.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[1]);
            } else {
                singleWords.add(key);
            }
        }

        List<Completer> completers = new ArrayList<>();
        for (var entry : twoWordGroups.entrySet()) {
            completers.add(new ArgumentCompleter(
                    new StringsCompleter(entry.getKey()),
                    new StringsCompleter(entry.getValue()),
                    NullCompleter.INSTANCE));
        }
        completers.add(new ArgumentCompleter(
                new StringsCompleter(singleWords),
                NullCompleter.INSTANCE));

        return new AggregateCompleter(completers);
    }

    private CommandIO buildCommandIO(Terminal terminal, LineReader reader) {
        if (Terminal.TYPE_DUMB.equals(terminal.getType())) {
            return new PlainCommandIO(terminal.writer());
        }
        return new JLineCommandIO(terminal, reader);
    }

    private void dispatch(String line, CommandIO io) {
        String[] parts = line.split("\\s+");
        String name = parts[0].toLowerCase();

        // Try two-word command first (e.g. "sync all", "sync queue")
        Command cmd = null;
        String[] cmdArgs = parts;
        if (parts.length >= 2) {
            String twoWord = name + " " + parts[1].toLowerCase();
            cmd = commands.get(twoWord);
            if (cmd != null) {
                name = twoWord;
                // Rebuild args: merge first two tokens into one, keep the rest
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
                cmd.execute(cmdArgs, session, io);
            } catch (Exception e) {
                io.println("Error: " + e.getMessage());
                log.error("Command '{}' threw an exception", name, e);
            }
        } else {
            io.println("Unknown command: " + name + "  (type 'help' for available commands)");
        }
    }
}
