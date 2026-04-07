package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Volume;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.smb.VolumeConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for VolumesCommand — lists configured volumes with sync/connection status.
 */
class VolumesCommandTest {

    private static final VolumeConfig VOL_A = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora");
    private static final VolumeConfig VOL_B = new VolumeConfig("b", "//pandora/jav_B", "stars-flat", "pandora");

    private VolumeRepository volumeRepo;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        AppConfig.initializeForTest(new OrganizerConfig(
                null, null, List.of(),
                List.of(VOL_A, VOL_B),
                List.of(),
                List.of()
        ));
        volumeRepo = mock(VolumeRepository.class);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));

        when(volumeRepo.findAll()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void nameIsVolumes() {
        assertEquals("volumes", new VolumesCommand(volumeRepo).name());
    }

    @Test
    void listsAllConfiguredVolumes() {
        VolumesCommand cmd = new VolumesCommand(volumeRepo);
        cmd.execute(new String[]{"volumes"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("a"));
        assertTrue(out.contains("conventional"));
        assertTrue(out.contains("b"));
        assertTrue(out.contains("stars-flat"));
    }

    @Test
    void showsNeverWhenVolumeNotSynced() {
        VolumesCommand cmd = new VolumesCommand(volumeRepo);
        cmd.execute(new String[]{"volumes"}, ctx, io);

        assertTrue(output.toString().contains("never"));
    }

    @Test
    void showsSyncTimestampWhenAvailable() {
        Volume dbVol = new Volume("a", "conventional");
        dbVol.setLastSyncedAt(LocalDateTime.of(2025, 3, 15, 10, 30));
        when(volumeRepo.findAll()).thenReturn(List.of(dbVol));

        VolumesCommand cmd = new VolumesCommand(volumeRepo);
        cmd.execute(new String[]{"volumes"}, ctx, io);

        assertTrue(output.toString().contains("2025-03-15 10:30"));
    }

    @Test
    void showsConnectedForActiveVolume() {
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        ctx.setMountedVolume(VOL_A);
        ctx.setActiveConnection(conn);

        VolumesCommand cmd = new VolumesCommand(volumeRepo);
        cmd.execute(new String[]{"volumes"}, ctx, io);

        String out = output.toString();
        // The line for volume "a" should show "yes"
        String[] lines = out.split("\n");
        String lineA = java.util.Arrays.stream(lines)
                .filter(l -> l.startsWith("a"))
                .findFirst().orElse("");
        assertTrue(lineA.contains("yes"));
    }

    @Test
    void showsDashForDisconnectedVolume() {
        VolumesCommand cmd = new VolumesCommand(volumeRepo);
        cmd.execute(new String[]{"volumes"}, ctx, io);

        String out = output.toString();
        // Both volumes should show "-" for connection status
        String[] lines = out.split("\n");
        long dashCount = java.util.Arrays.stream(lines)
                .filter(l -> !l.startsWith("ID") && !l.startsWith("-"))
                .filter(l -> l.contains("-  "))
                .count();
        assertTrue(dashCount >= 2, "Disconnected volumes should show '-'");
    }
}
