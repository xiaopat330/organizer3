package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

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
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        io.println("Available commands:");
        allCommands.stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(cmd -> io.println(String.format("  %-16s %s", cmd.name(), cmd.description())));
    }
}
