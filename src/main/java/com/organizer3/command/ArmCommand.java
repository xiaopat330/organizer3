package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.util.List;

public class ArmCommand implements Command {

    @Override public String name() { return "arm"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Enable live mode — mutations will execute for real. Use 'test' to return to dry-run."; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (!ctx.isDryRun()) {
            io.println("Already in live mode.");
            return;
        }
        ctx.setDryRun(false);
        io.println("LIVE MODE ENABLED. Mutations will execute for real. Type 'test' to return to dry-run.");
    }
}
