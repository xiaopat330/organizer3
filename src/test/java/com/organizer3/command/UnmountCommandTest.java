package com.organizer3.command;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.VolumeIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnmountCommandTest {

    private static final VolumeConfig VOLUME_A = new VolumeConfig(
            "a", "//pandora/jav_A", "conventional", "pandora", null);

    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private UnmountCommand command;

    @BeforeEach
    void setUp() {
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
        command = new UnmountCommand();
    }

    @Test
    void name_isUnmount() {
        assertEquals("unmount", command.name());
    }

    @Test
    void nothingMounted_printsMessage() {
        command.execute(new String[]{"unmount"}, ctx, io);

        assertTrue(output.toString().contains("No volume is currently mounted"));
        assertNull(ctx.getMountedVolume());
    }

    @Test
    void mounted_closesConnectionAndClearsContext() {
        VolumeConnection connection = mock(VolumeConnection.class);
        when(connection.isConnected()).thenReturn(true);
        ctx.setMountedVolume(VOLUME_A);
        ctx.setActiveConnection(connection);
        ctx.setIndex(VolumeIndex.empty("a"));

        command.execute(new String[]{"unmount"}, ctx, io);

        verify(connection).close();
        assertNull(ctx.getMountedVolume());
        assertNull(ctx.getActiveConnection());
        assertNull(ctx.getIndex());
        assertTrue(output.toString().contains("Disconnected from volume 'a'"));
    }

    @Test
    void mounted_withNoActiveConnection_clearsContext() {
        ctx.setMountedVolume(VOLUME_A);
        // no connection set

        command.execute(new String[]{"unmount"}, ctx, io);

        assertNull(ctx.getMountedVolume());
        assertTrue(output.toString().contains("Disconnected from volume 'a'"));
    }
}
