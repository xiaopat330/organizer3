package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

/**
 * Convenience command that runs sync all then sync covers in sequence.
 * Intended for fresh starts or full rebuilds of the local index and covers.
 */
@RequiredArgsConstructor
public class RebuildCommand implements Command {

    private final Command syncAll;
    private final Command scanCovers;

    @Override
    public String name() {
        return "rebuild";
    }

    @Override
    public String description() {
        return "Run sync all then sync covers (full rebuild from scratch)";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        io.println("=== Step 1/2: sync all ===");
        syncAll.execute(args, ctx, io);

        io.println("=== Step 2/2: sync covers ===");
        scanCovers.execute(args, ctx, io);
    }
}
