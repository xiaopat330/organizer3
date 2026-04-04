package com.organizer3.command;

import com.organizer3.shell.SessionContext;

import java.io.PrintWriter;

public class ShutdownCommand implements Command {

    @Override
    public String name() {
        return "shutdown";
    }

    @Override
    public String description() {
        return "Exit the application";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, PrintWriter out) {
        out.println("Goodbye.");
        ctx.shutdown();
    }
}
