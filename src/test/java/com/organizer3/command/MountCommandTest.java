package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.smb.MountProgressListener;
import com.organizer3.smb.SmbConnectionException;
import com.organizer3.smb.SmbConnector;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.VolumeIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MountCommandTest {

    private static final ServerConfig SERVER = new ServerConfig("pandora", "patrick", "secret", null);
    private static final VolumeConfig VOLUME_A = new VolumeConfig(
            "a", "//pandora/jav_A", "conventional", "pandora");

    private SmbConnector smbConnector;
    private VolumeConnection connection;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private MountCommand command;

    @BeforeEach
    void setUp() {
        AppConfig.initializeForTest(new OrganizerConfig(
                null, null, List.of(SERVER), List.of(VOLUME_A), List.of(), List.of()));
        smbConnector = mock(SmbConnector.class);
        connection = mock(VolumeConnection.class);
        when(connection.isConnected()).thenReturn(true);
        IndexLoader indexLoader = mock(IndexLoader.class);
        when(indexLoader.load(any())).thenReturn(VolumeIndex.empty("a"));
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
        command = new MountCommand(smbConnector, indexLoader);
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void name_isMount() {
        assertEquals("mount", command.name());
    }

    @Test
    void missingArgument_printsUsage() {
        command.execute(new String[]{"mount"}, ctx, io);

        assertTrue(output.toString().contains("Usage:"));
        assertNull(ctx.getMountedVolume());
    }

    @Test
    void unknownVolumeId_printsError() {
        command.execute(new String[]{"mount", "zzz"}, ctx, io);

        assertTrue(output.toString().contains("Unknown volume: zzz"));
        assertNull(ctx.getMountedVolume());
    }

    @Test
    void unknownVolumeId_listsKnownVolumes() {
        command.execute(new String[]{"mount", "zzz"}, ctx, io);

        assertTrue(output.toString().contains("a"));
    }

    @Test
    void alreadyConnected_skipsReconnect() {
        ctx.setMountedVolume(VOLUME_A);
        ctx.setActiveConnection(connection);

        command.execute(new String[]{"mount", "a"}, ctx, io);

        verifyNoInteractions(smbConnector);
        assertTrue(output.toString().contains("already connected"));
    }

    @Test
    void successfulConnect_setsContextAndPrintsConfirmation() throws Exception {
        when(smbConnector.connect(eq(VOLUME_A), eq(SERVER), any(MountProgressListener.class)))
                .thenReturn(connection);

        command.execute(new String[]{"mount", "a"}, ctx, io);

        verify(smbConnector).connect(eq(VOLUME_A), eq(SERVER), any(MountProgressListener.class));
        assertEquals(VOLUME_A, ctx.getMountedVolume());
        assertEquals(connection, ctx.getActiveConnection());
        assertTrue(output.toString().contains("Connected"));
    }

    @Test
    void connectionFails_printsErrorAndDoesNotSetContext() throws Exception {
        when(smbConnector.connect(any(), any(), any(MountProgressListener.class)))
                .thenThrow(new SmbConnectionException("server unreachable"));

        command.execute(new String[]{"mount", "a"}, ctx, io);

        assertNull(ctx.getMountedVolume());
        assertNull(ctx.getActiveConnection());
        assertTrue(output.toString().contains("Connection failed"));
        assertTrue(output.toString().contains("server unreachable"));
    }

    @Test
    void switchingVolumes_closesExistingConnection() throws Exception {
        ServerConfig otherServer = new ServerConfig("other", "u", "p", null);
        VolumeConnection oldConnection = mock(VolumeConnection.class);
        when(oldConnection.isConnected()).thenReturn(true);
        ctx.setActiveConnection(oldConnection);
        ctx.setMountedVolume(new VolumeConfig("other", "//other/share", "queue", "other"));
        AppConfig.reset();
        AppConfig.initializeForTest(new OrganizerConfig(
                null, null, List.of(SERVER, otherServer), List.of(VOLUME_A), List.of(), List.of()));

        when(smbConnector.connect(eq(VOLUME_A), eq(SERVER), any(MountProgressListener.class)))
                .thenReturn(connection);

        command.execute(new String[]{"mount", "a"}, ctx, io);

        verify(oldConnection).close();
        assertEquals(connection, ctx.getActiveConnection());
    }

    @Test
    void successfulConnect_updatesPromptViaContext() throws Exception {
        when(smbConnector.connect(eq(VOLUME_A), eq(SERVER), any(MountProgressListener.class)))
                .thenReturn(connection);

        command.execute(new String[]{"mount", "a"}, ctx, io);

        assertEquals("a", ctx.getMountedVolumeId());
    }
}
