package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class ArmCommandTest {

    private ArmCommand command;
    private TestCommand testCommand;
    private SessionContext session;
    private StringWriter output;
    private PlainCommandIO io;

    @BeforeEach
    void setUp() {
        command = new ArmCommand();
        testCommand = new TestCommand();
        session = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @Test
    void startsInDryRunMode() {
        assertTrue(session.isDryRun());
    }

    @Test
    void armEnablesLiveMode() {
        command.execute(new String[]{"arm"}, session, io);
        assertFalse(session.isDryRun());
        assertTrue(output.toString().contains("LIVE MODE"));
    }

    @Test
    void armWhenAlreadyArmedIsIdempotent() {
        command.execute(new String[]{"arm"}, session, io);
        output.getBuffer().setLength(0);
        command.execute(new String[]{"arm"}, session, io);
        assertFalse(session.isDryRun());
        assertTrue(output.toString().contains("Already"));
    }

    @Test
    void testRestoresDryRunMode() {
        command.execute(new String[]{"arm"}, session, io);
        assertFalse(session.isDryRun());
        output.getBuffer().setLength(0);
        testCommand.execute(new String[]{"test"}, session, io);
        assertTrue(session.isDryRun());
        assertTrue(output.toString().toLowerCase().contains("dry-run"));
    }

    @Test
    void testWhenAlreadyInDryRunIsIdempotent() {
        testCommand.execute(new String[]{"test"}, session, io);
        assertTrue(session.isDryRun());
        assertTrue(output.toString().contains("Already"));
    }
}
