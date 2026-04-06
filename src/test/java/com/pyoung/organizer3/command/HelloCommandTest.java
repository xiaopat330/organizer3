package com.pyoung.organizer3.command;

import com.organizer3.command.HelloCommand;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloCommandTest {

    private HelloCommand command;
    private SessionContext session;
    private StringWriter output;
    private PlainCommandIO io;

    @BeforeEach
    void setUp() {
        command = new HelloCommand();
        session = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @Test
    void defaultGreetingUsesWorld() {
        command.execute(new String[]{"hello"}, session, io);
        assertTrue(output.toString().contains("Hello, world!"));
    }

    @Test
    void greetingUsesNameArgumentWhenProvided() {
        command.execute(new String[]{"hello", "Alice"}, session, io);
        assertTrue(output.toString().contains("Hello, Alice!"));
    }

    @Test
    void outputIncludesDryRunStatus() {
        command.execute(new String[]{"hello"}, session, io);
        assertTrue(output.toString().contains("Dry-run mode: ON"));
    }

    @Test
    void outputReflectsArmedMode() {
        session.setDryRun(false);
        command.execute(new String[]{"hello"}, session, io);
        assertTrue(output.toString().contains("Dry-run mode: OFF"));
    }
}
