package com.organizer3.trash;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.smb.NasAvailabilityMonitor;
import com.organizer3.smb.SmbConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrashSweepSchedulerTest {

    private TrashService trashService;
    private SmbConnectionFactory smbFactory;
    private SmbConnectionFactory.SmbShareHandle mockHandle;
    private VolumeFileSystem mockFs;

    @BeforeEach
    void setUp() throws IOException {
        trashService = mock(TrashService.class);
        smbFactory   = mock(SmbConnectionFactory.class);
        mockHandle   = mock(SmbConnectionFactory.SmbShareHandle.class);
        mockFs       = mock(VolumeFileSystem.class);

        when(mockHandle.fileSystem()).thenReturn(mockFs);

        // Configure withRetry to execute the operation immediately with the mock handle
        when(smbFactory.withRetry(anyString(), any())).thenAnswer(inv -> {
            SmbConnectionFactory.SmbOperation<?> op = inv.getArgument(1);
            return op.execute(mockHandle);
        });
    }

    @Test
    void skipsVolumesWithNoTrashConfigured() throws Exception {
        ServerConfig withTrash    = new ServerConfig("srv",  "u", "p", "", "_trash", null);
        ServerConfig withoutTrash = new ServerConfig("srv2", "u", "p", "", null, null);
        VolumeConfig volA = new VolumeConfig("a", "//h/jav_A", "standard", "srv",  null);
        VolumeConfig volB = new VolumeConfig("b", "//h/jav_B", "standard", "srv2", null);

        when(trashService.sweepExpired(any(), eq("a"), any(), any()))
                .thenReturn(new SweepReport("a", 0, 0, 0));

        sweep(withTrash, withoutTrash, volA, volB);

        verify(trashService).sweepExpired(any(), eq("a"), any(), any());
        verify(trashService, never()).sweepExpired(any(), eq("b"), any(), any());
    }

    @Test
    void passesServerTrashFolderAsTrashRoot() throws Exception {
        ServerConfig server = new ServerConfig("srv", "u", "p", "", "custom_trash", null);
        VolumeConfig vol    = new VolumeConfig("a", "//h/jav_A", "standard", "srv", null);

        when(trashService.sweepExpired(any(), any(), any(), any()))
                .thenReturn(new SweepReport("a", 0, 0, 0));

        sweep(server, vol);

        verify(trashService).sweepExpired(eq(mockFs), eq("a"), eq(Path.of("/custom_trash")), any(Instant.class));
    }

    @Test
    void skipsVolumeWhenNasIsOffline() throws Exception {
        ServerConfig server = new ServerConfig("srv", "u", "p", "", "_trash", null);
        // Two volumes on different hosts so we can take one host down independently
        VolumeConfig volA = new VolumeConfig("a", "//atlas/jav_A", "standard", "srv", null);
        VolumeConfig volB = new VolumeConfig("b", "//pandora/jav_B", "standard", "srv", null);
        OrganizerConfig config = configOf(server, volA, volB);

        // atlas is down, pandora is up
        NasAvailabilityMonitor monitor = NasAvailabilityMonitor.withProbe(config, host -> host.equals("pandora"));
        monitor.start();

        when(trashService.sweepExpired(any(), eq("b"), any(), any()))
                .thenReturn(new SweepReport("b", 1, 0, 0));

        sweepWith(config, monitor);

        verify(trashService, never()).sweepExpired(any(), eq("a"), any(), any());
        verify(trashService).sweepExpired(any(), eq("b"), any(), any());
        monitor.stop();
    }

    @Test
    void continuesAfterPerVolumeFailure() throws Exception {
        ServerConfig server = new ServerConfig("srv", "u", "p", "", "_trash", null);
        VolumeConfig volA   = new VolumeConfig("a", "//h/jav_A", "standard", "srv", null);
        VolumeConfig volB   = new VolumeConfig("b", "//h/jav_B", "standard", "srv", null);

        // Volume A throws; volume B should still be swept
        when(smbFactory.withRetry(eq("a"), any())).thenThrow(new IOException("broken pipe"));
        when(trashService.sweepExpired(any(), eq("b"), any(), any()))
                .thenReturn(new SweepReport("b", 1, 2, 0));

        sweep(server, volA, volB);

        verify(trashService, never()).sweepExpired(any(), eq("a"), any(), any());
        verify(trashService).sweepExpired(any(), eq("b"), any(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sweep(ServerConfig server, VolumeConfig... volumes) {
        sweep(new ServerConfig[]{ server }, volumes);
    }

    private void sweep(ServerConfig s1, ServerConfig s2, VolumeConfig... volumes) {
        sweep(new ServerConfig[]{ s1, s2 }, volumes);
    }

    private void sweep(ServerConfig[] servers, VolumeConfig[] volumes) {
        OrganizerConfig config = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(servers), List.of(volumes),
                null, null, null);
        sweepWith(config, NasAvailabilityMonitor.alwaysAvailable());
    }

    private void sweepWith(OrganizerConfig config, NasAvailabilityMonitor monitor) {
        new TrashSweepScheduler(trashService, smbFactory, config, monitor).sweepAll();
    }

    private static OrganizerConfig configOf(ServerConfig server, VolumeConfig... volumes) {
        return new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(server), List.of(volumes),
                null, null, null);
    }
}
