package com.organizer3.avstars.command;

import com.organizer3.avstars.sync.AvStarsSyncOperation;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvSyncCommandTest {

    @Mock AvStarsSyncOperation syncOp;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvSyncCommand command;

    @BeforeEach
    void setUp() {
        command = new AvSyncCommand(syncOp);
    }

    @Test
    void noMountedVolumePrintsGuidance() {
        when(ctx.getMountedVolume()).thenReturn(null);

        command.execute(new String[]{"av sync"}, ctx, io);

        verify(io).println(contains("No volume mounted"));
        verifyNoInteractions(syncOp);
    }

    @Test
    void wrongStructureTypeIsRejected() {
        VolumeConfig vol = new VolumeConfig("a", "//p/jav_A", "conventional", "pandora", null);
        when(ctx.getMountedVolume()).thenReturn(vol);

        command.execute(new String[]{"av sync"}, ctx, io);

        verify(io).println(contains("requires an avstars volume"));
        verifyNoInteractions(syncOp);
    }

    @Test
    void disconnectedVolumeIsRejected() {
        VolumeConfig vol = new VolumeConfig("qnap_av", "//q/AV", "avstars", "qnap2", null);
        when(ctx.getMountedVolume()).thenReturn(vol);
        when(ctx.isConnected()).thenReturn(false);

        command.execute(new String[]{"av sync"}, ctx, io);

        verify(io).println(contains("not connected"));
        verifyNoInteractions(syncOp);
    }
}
