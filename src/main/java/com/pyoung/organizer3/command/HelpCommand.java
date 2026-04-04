package com.pyoung.organizer3.command;

import com.pyoung.organizer3.shell.SessionContext;

import java.io.PrintWriter;
import java.util.List;

public class HelpCommand implements Command {

    private final List<Command> allCommands;

    public HelpCommand(List<Command> allCommands) {
        this.allCommands = allCommands;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "List available commands";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, PrintWriter out) {
        out.println("Available commands:");
        allCommands.stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(cmd -> out.printf("  %-16s %s%n", cmd.name(), cmd.description()));
    }
}
