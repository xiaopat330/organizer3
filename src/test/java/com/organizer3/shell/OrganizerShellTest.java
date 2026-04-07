package com.organizer3.shell;

import com.organizer3.command.Command;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OrganizerShell's command dispatch logic: single-word commands, two-word commands,
 * unknown commands, case sensitivity, and argument passing.
 *
 * <p>Uses reflection to call the private {@code dispatch} method directly, avoiding the
 * need for a JLine terminal in tests.
 */
class OrganizerShellTest {

    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    // --- Single-word command dispatch ---

    @Test
    void dispatchesSingleWordCommand() throws Exception {
        RecordingCommand help = new RecordingCommand("help", "Show help");
        OrganizerShell shell = shellWith(List.of(help));

        dispatch(shell, "help");

        assertTrue(help.wasExecuted);
        assertArrayEquals(new String[]{"help"}, help.receivedArgs);
    }

    @Test
    void dispatchIsCaseInsensitive() throws Exception {
        RecordingCommand help = new RecordingCommand("help", "Show help");
        OrganizerShell shell = shellWith(List.of(help));

        dispatch(shell, "HELP");

        assertTrue(help.wasExecuted);
    }

    // --- Two-word command dispatch ---

    @Test
    void dispatchesTwoWordCommand() throws Exception {
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        OrganizerShell shell = shellWith(List.of(syncAll));

        dispatch(shell, "sync all");

        assertTrue(syncAll.wasExecuted);
        assertArrayEquals(new String[]{"sync all"}, syncAll.receivedArgs);
    }

    @Test
    void twoWordCommandIsCaseInsensitive() throws Exception {
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        OrganizerShell shell = shellWith(List.of(syncAll));

        dispatch(shell, "Sync ALL");

        assertTrue(syncAll.wasExecuted);
    }

    @Test
    void twoWordCommandPassesRemainingArgs() throws Exception {
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        OrganizerShell shell = shellWith(List.of(syncAll));

        dispatch(shell, "sync all --verbose extra");

        assertTrue(syncAll.wasExecuted);
        assertArrayEquals(new String[]{"sync all", "--verbose", "extra"}, syncAll.receivedArgs);
    }

    // --- Two-word vs single-word precedence ---

    @Test
    void twoWordCommandTakesPrecedenceOverSingleWord() throws Exception {
        RecordingCommand sync = new RecordingCommand("sync", "Default sync");
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        OrganizerShell shell = shellWith(List.of(sync, syncAll));

        dispatch(shell, "sync all");

        assertTrue(syncAll.wasExecuted, "Two-word command should match");
        assertFalse(sync.wasExecuted, "Single-word command should not match");
    }

    @Test
    void singleWordCommandStillWorksWhenTwoWordExists() throws Exception {
        RecordingCommand sync = new RecordingCommand("sync", "Default sync");
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        OrganizerShell shell = shellWith(List.of(sync, syncAll));

        dispatch(shell, "sync");

        assertTrue(sync.wasExecuted, "Single-word 'sync' should dispatch");
        assertFalse(syncAll.wasExecuted);
    }

    @Test
    void singleWordWithNonMatchingSecondWordFallsBackToSingleWord() throws Exception {
        RecordingCommand sync = new RecordingCommand("sync", "Default sync");
        RecordingCommand syncAll = new RecordingCommand("sync all", "Full sync");
        OrganizerShell shell = shellWith(List.of(sync, syncAll));

        dispatch(shell, "sync something");

        assertTrue(sync.wasExecuted);
        assertFalse(syncAll.wasExecuted);
        assertArrayEquals(new String[]{"sync", "something"}, sync.receivedArgs);
    }

    // --- Unknown commands ---

    @Test
    void unknownCommandPrintsError() throws Exception {
        OrganizerShell shell = shellWith(List.of());

        dispatch(shell, "bogus");

        assertTrue(output.toString().contains("Unknown command: bogus"));
    }

    // --- Alias dispatch ---

    @Test
    void dispatchesViaAlias() throws Exception {
        RecordingCommand cmd = new RecordingCommand("shutdown", "Shut down") {
            @Override
            public List<String> aliases() { return List.of("exit", "quit"); }
        };
        OrganizerShell shell = shellWith(List.of(cmd));

        dispatch(shell, "exit");

        assertTrue(cmd.wasExecuted);
    }

    // --- Error handling ---

    @Test
    void commandExceptionIsCaughtAndPrinted() throws Exception {
        Command boom = new Command() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "Explodes"; }
            @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
                throw new RuntimeException("kaboom");
            }
        };
        OrganizerShell shell = shellWith(List.of(boom));

        dispatch(shell, "boom");

        assertTrue(output.toString().contains("Error: kaboom"));
    }

    // --- Helpers ---

    private OrganizerShell shellWith(List<Command> commands) {
        return new OrganizerShell(ctx, commands);
    }

    /** Calls the private dispatch(String, CommandIO) method via reflection. */
    private void dispatch(OrganizerShell shell, String line) throws Exception {
        Method m = OrganizerShell.class.getDeclaredMethod("dispatch", String.class, CommandIO.class);
        m.setAccessible(true);
        m.invoke(shell, line, io);
    }

    /** A Command that records whether it was executed and what args it received. */
    private static class RecordingCommand implements Command {
        final String name;
        final String desc;
        boolean wasExecuted = false;
        String[] receivedArgs;

        RecordingCommand(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override public String name() { return name; }
        @Override public String description() { return desc; }

        @Override
        public void execute(String[] args, SessionContext ctx, CommandIO io) {
            wasExecuted = true;
            receivedArgs = args;
        }
    }
}
