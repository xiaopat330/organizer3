package com.organizer3;

import com.organizer3.command.Command;
import com.organizer3.command.HelloCommand;
import com.organizer3.command.HelpCommand;
import com.organizer3.command.ShutdownCommand;
import com.organizer3.shell.OrganizerShell;
import com.organizer3.shell.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Wires all dependencies manually (no IoC container).
 *
 * This class is the composition root — the only place that knows about
 * all the concrete types. Everything else works against interfaces,
 * making each piece independently testable.
 */
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        log.info("Starting Organizer3");

        SessionContext session = new SessionContext();

        List<Command> commands = new ArrayList<>();
        commands.add(new HelloCommand());
        commands.add(new ShutdownCommand());
        commands.add(new HelpCommand(commands));

        OrganizerShell shell = new OrganizerShell(session, commands);
        shell.run();

        log.info("Organizer3 exiting");
    }
}
