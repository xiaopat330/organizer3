package com.pyoung.organizer3.command;

import com.organizer3.command.ShutdownCommand;
import com.organizer3.shell.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownCommandTest {

    private ShutdownCommand command;
    private SessionContext session;
    private StringWriter output;
    private PrintWriter writer;

    @BeforeEach
    void setUp() {
        command = new ShutdownCommand();
        session = new SessionContext();
        output = new StringWriter();
        writer = new PrintWriter(output);
    }

    @Test
    void sessionIsRunningBeforeShutdown() {
        assertTrue(session.isRunning());
    }

    @Test
    void shutdownSetsRunningToFalse() {
        command.execute(new String[]{"shutdown"}, session, writer);
        assertFalse(session.isRunning());
    }

    @Test
    void shutdownPrintsGoodbye() {
        command.execute(new String[]{"shutdown"}, session, writer);
        assertTrue(output.toString().contains("Goodbye"));
    }
}
