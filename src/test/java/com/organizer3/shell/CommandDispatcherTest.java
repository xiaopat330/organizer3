package com.organizer3.shell;

import com.organizer3.command.Command;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CommandDispatcher: single-word commands, two-word commands,
 * case insensitivity, alias dispatch, argument passing, and error handling.
 */
class CommandDispatcherTest {

    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        ctx    = new SessionContext();
        output = new StringWriter();
        io     = new PlainCommandIO(new PrintWriter(output));
    }

    // ── Single-word dispatch ──────────────────────────────────────────────────

    @Test
    void dispatchesSingleWordCommand() {
        RecordingCommand help = new RecordingCommand("help", "Show help");
        dispatcherWith(List.of(help)).dispatch("help", ctx, io);

        assertTrue(help.wasExecuted);
        assertArrayEquals(new String[]{"help"}, help.receivedArgs);
    }

    @Test
    void dispatchIsCaseInsensitive() {
        RecordingCommand help = new RecordingCommand("help", "Show help");
        dispatcherWith(List.of(help)).dispatch("HELP", ctx, io);

        assertTrue(help.wasExecuted);
    }

    // ── Two-word dispatch ─────────────────────────────────────────────────────

    @Test
    void dispatchesTwoWordCommand() {
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        dispatcherWith(List.of(syncAll)).dispatch("sync all", ctx, io);

        assertTrue(syncAll.wasExecuted);
        assertArrayEquals(new String[]{"sync all"}, syncAll.receivedArgs);
    }

    @Test
    void twoWordCommandIsCaseInsensitive() {
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        dispatcherWith(List.of(syncAll)).dispatch("Sync ALL", ctx, io);

        assertTrue(syncAll.wasExecuted);
    }

    @Test
    void twoWordCommandPassesRemainingArgs() {
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        dispatcherWith(List.of(syncAll)).dispatch("sync all --verbose extra", ctx, io);

        assertTrue(syncAll.wasExecuted);
        assertArrayEquals(new String[]{"sync all", "--verbose", "extra"}, syncAll.receivedArgs);
    }

    // ── Two-word vs single-word precedence ────────────────────────────────────

    @Test
    void twoWordCommandTakesPrecedenceOverSingleWord() {
        RecordingCommand sync    = new RecordingCommand("sync",     "Default sync");
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        dispatcherWith(List.of(sync, syncAll)).dispatch("sync all", ctx, io);

        assertTrue(syncAll.wasExecuted, "Two-word command should match");
        assertFalse(sync.wasExecuted,   "Single-word command should not match");
    }

    @Test
    void singleWordCommandStillWorksWhenTwoWordExists() {
        RecordingCommand sync    = new RecordingCommand("sync",     "Default sync");
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        dispatcherWith(List.of(sync, syncAll)).dispatch("sync", ctx, io);

        assertTrue(sync.wasExecuted,     "Single-word 'sync' should dispatch");
        assertFalse(syncAll.wasExecuted);
    }

    @Test
    void singleWordWithNonMatchingSecondWordFallsBackToSingleWord() {
        RecordingCommand sync    = new RecordingCommand("sync",     "Default sync");
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        dispatcherWith(List.of(sync, syncAll)).dispatch("sync something", ctx, io);

        assertTrue(sync.wasExecuted);
        assertFalse(syncAll.wasExecuted);
        assertArrayEquals(new String[]{"sync", "something"}, sync.receivedArgs);
    }

    // ── Unknown commands ──────────────────────────────────────────────────────

    @Test
    void unknownCommandPrintsError() {
        dispatcherWith(List.of()).dispatch("bogus", ctx, io);

        assertTrue(output.toString().contains("Unknown command: bogus"));
    }

    // ── Alias dispatch ────────────────────────────────────────────────────────

    @Test
    void dispatchesViaAlias() {
        RecordingCommand cmd = new RecordingCommand("shutdown", "Shut down") {
            @Override public List<String> aliases() { return List.of("exit", "quit"); }
        };
        dispatcherWith(List.of(cmd)).dispatch("exit", ctx, io);

        assertTrue(cmd.wasExecuted);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void commandExceptionIsCaughtAndPrinted() {
        Command boom = new Command() {
            @Override public String name()        { return "boom"; }
            @Override public String description() { return "Explodes"; }
            @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
                throw new RuntimeException("kaboom");
            }
        };
        dispatcherWith(List.of(boom)).dispatch("boom", ctx, io);

        assertTrue(output.toString().contains("Error: kaboom"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CommandDispatcher dispatcherWith(List<Command> commands) {
        return new CommandDispatcher(commands);
    }

    private static class RecordingCommand implements Command {
        final String name;
        final String desc;
        boolean  wasExecuted = false;
        String[] receivedArgs;

        RecordingCommand(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override public String name()        { return name; }
        @Override public String description() { return desc; }

        @Override
        public void execute(String[] args, SessionContext ctx, CommandIO io) {
            wasExecuted  = true;
            receivedArgs = args;
        }
    }
}
