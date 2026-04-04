package com.organizer3.command;

import com.organizer3.shell.SessionContext;

import java.io.PrintWriter;

/**
 * Starter command — placeholder to verify the shell wiring works.
 * Replace or remove once real commands are implemented.
 */
public class HelloCommand implements Command {

    @Override
    public String name() {
        return "hello";
    }

    @Override
    public String description() {
        return "Say hello (starter command)";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, PrintWriter out) {
        String target = args.length > 1 ? args[1] : "world";
        out.println("Hello, " + target + "!");
        out.println("Dry-run mode: " + (ctx.isDryRun() ? "ON" : "OFF"));
    }
}
