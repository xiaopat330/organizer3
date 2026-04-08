package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.sync.SyncCommandDef;
import com.organizer3.config.sync.SyncOperationType;
import com.organizer3.config.sync.StructureSyncConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.SyncOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncCommandTest {

    private static final VolumeConfig CONVENTIONAL_VOL = new VolumeConfig(
            "a", "//pandora/jav_A", "conventional", "pandora", null);

    private static final VolumeConfig QUEUE_VOL = new VolumeConfig(
            "unsorted", "//pandora/jav_unsorted", "queue", "pandora", null);

    private static final VolumeStructureDef CONVENTIONAL_STRUCTURE = new VolumeStructureDef(
            "conventional",
            List.of(new PartitionDef("queue", "queue")),
            new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
    );

    private SyncOperation operation;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private VolumeConnection connection;

    @BeforeEach
    void setUp() {
        AppConfig.initializeForTest(new OrganizerConfig(
                null, null, null, null, List.of(),
                List.of(CONVENTIONAL_VOL, QUEUE_VOL),
                List.of(CONVENTIONAL_STRUCTURE,
                        new VolumeStructureDef("queue",
                                List.of(new PartitionDef("queue", "fresh")), null)),
                List.of(
                        new StructureSyncConfig("conventional", List.of(
                                new SyncCommandDef("sync queue", SyncOperationType.PARTITION, List.of("queue")),
                                new SyncCommandDef("sync all", SyncOperationType.FULL, null)
                        )),
                        new StructureSyncConfig("queue", List.of(
                                new SyncCommandDef("sync", SyncOperationType.FULL, null)
                        ))
                )
        ));
        operation = mock(SyncOperation.class);
        connection = mock(VolumeConnection.class);
        when(connection.isConnected()).thenReturn(true);
        when(connection.fileSystem()).thenReturn(mock(VolumeFileSystem.class));
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void noVolumeMounted_printsError() {
        SyncCommand cmd = new SyncCommand("sync all", Set.of("conventional"), operation);
        cmd.execute(new String[]{"sync all"}, ctx, io);

        assertTrue(output.toString().contains("No volume mounted"));
        verifyNoInteractions(operation);
    }

    @Test
    void volumeMountedButNotConnected_printsError() {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        SyncCommand cmd = new SyncCommand("sync all", Set.of("conventional"), operation);

        cmd.execute(new String[]{"sync all"}, ctx, io);

        assertTrue(output.toString().contains("not connected"));
        verifyNoInteractions(operation);
    }

    @Test
    void wrongStructureType_printsError() {
        ctx.setMountedVolume(QUEUE_VOL);
        ctx.setActiveConnection(connection);
        SyncCommand cmd = new SyncCommand("sync queue", Set.of("conventional"), operation);

        cmd.execute(new String[]{"sync queue"}, ctx, io);

        assertTrue(output.toString().contains("not available"));
        verifyNoInteractions(operation);
    }

    @Test
    void correctStructureType_delegatesToOperation() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        SyncCommand cmd = new SyncCommand("sync all", Set.of("conventional"), operation);

        cmd.execute(new String[]{"sync all"}, ctx, io);

        verify(operation).execute(eq(CONVENTIONAL_VOL), eq(CONVENTIONAL_STRUCTURE),
                any(VolumeFileSystem.class), eq(ctx), any(CommandIO.class));
    }

    @Test
    void operationThrowsIOException_printsError() throws IOException {
        ctx.setMountedVolume(CONVENTIONAL_VOL);
        ctx.setActiveConnection(connection);
        SyncCommand cmd = new SyncCommand("sync all", Set.of("conventional"), operation);
        doThrow(new IOException("disk read error"))
                .when(operation).execute(any(), any(), any(), any(), any());

        cmd.execute(new String[]{"sync all"}, ctx, io);

        assertTrue(output.toString().contains("Sync failed"));
        assertTrue(output.toString().contains("disk read error"));
    }

    @Test
    void name_returnsConfiguredTerm() {
        SyncCommand cmd = new SyncCommand("sync queue", Set.of("conventional"), operation);
        assertEquals("sync queue", cmd.name());
    }
}
