package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.util.List;

public class ShutdownCommand implements Command {

    @Override
    public String name() {
        return "shutdown";
    }

    @Override
    public List<String> aliases() {
        return List.of("quit", "exit");
    }

    @Override
    public String description() {
        return "Exit the application";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        io.println("Goodbye.");
        ctx.shutdown();
    }
}
