package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class RebuildCommandTest {

    @Test
    void name_isRebuild() {
        Command syncAll    = mock(Command.class);
        Command scanCovers = mock(Command.class);
        RebuildCommand cmd = new RebuildCommand(syncAll, scanCovers);

        assert "rebuild".equals(cmd.name());
    }

    @Test
    void execute_runsSyncAllThenScanCovers_inOrder() {
        Command syncAll    = mock(Command.class);
        Command scanCovers = mock(Command.class);
        SessionContext ctx = mock(SessionContext.class);
        CommandIO io       = mock(CommandIO.class);
        String[] args      = new String[0];

        RebuildCommand cmd = new RebuildCommand(syncAll, scanCovers);
        cmd.execute(args, ctx, io);

        var order = inOrder(syncAll, scanCovers);
        order.verify(syncAll).execute(args, ctx, io);
        order.verify(scanCovers).execute(args, ctx, io);
    }
}
