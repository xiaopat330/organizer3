package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.util.List;

public class TestCommand implements Command {

    @Override public String name() { return "test"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Return to dry-run mode — mutations are simulated, not executed."; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (ctx.isDryRun()) {
            io.println("Already in dry-run mode.");
            return;
        }
        ctx.setDryRun(true);
        io.println("Dry-run mode restored. Mutations will be simulated.");
    }
}
