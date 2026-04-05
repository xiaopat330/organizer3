package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.mount.CredentialLookup;
import com.organizer3.mount.CredentialNotFoundException;
import com.organizer3.mount.MountException;
import com.organizer3.mount.SmbMounter;
import com.organizer3.shell.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MountCommandTest {

    private static final VolumeConfig VOLUME_A = new VolumeConfig(
            "a", "//pandora/jav_A", Path.of("/Volumes/jav_A"), "conventional", "pandora", "patrick");

    private CredentialLookup credentialLookup;
    private SmbMounter smbMounter;
    private SessionContext ctx;
    private StringWriter output;
    private PrintWriter out;
    private MountCommand command;

    @BeforeEach
    void setUp() {
        AppConfig.initializeForTest(new OrganizerConfig(List.of(VOLUME_A)));
        credentialLookup = mock(CredentialLookup.class);
        smbMounter = mock(SmbMounter.class);
        ctx = new SessionContext();
        output = new StringWriter();
        out = new PrintWriter(output);
        command = new MountCommand(credentialLookup, smbMounter);
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void name_isMounted() {
        assertEquals("mount", command.name());
    }

    @Test
    void missingArgument_printsUsage() {
        command.execute(new String[]{"mount"}, ctx, out);

        assertTrue(output.toString().contains("Usage:"));
        assertNull(ctx.getMountedVolume());
    }

    @Test
    void unknownVolumeId_printsError() {
        command.execute(new String[]{"mount", "zzz"}, ctx, out);

        assertTrue(output.toString().contains("Unknown volume: zzz"));
        assertNull(ctx.getMountedVolume());
    }

    @Test
    void unknownVolumeId_listsKnownVolumes() {
        command.execute(new String[]{"mount", "zzz"}, ctx, out);

        assertTrue(output.toString().contains("a"));
    }

    @Test
    void alreadyMounted_setsContextWithoutCallingMountSmbfs() {
        when(smbMounter.isMounted(VOLUME_A.mountPoint())).thenReturn(true);

        command.execute(new String[]{"mount", "a"}, ctx, out);

        assertEquals(VOLUME_A, ctx.getMountedVolume());
        verify(smbMounter, never()).mount(any(), any());
        verify(credentialLookup, never()).getPassword(any(), any());
        assertTrue(output.toString().contains("already mounted"));
    }

    @Test
    void successfulMount_setsContextAndPrintsConfirmation() {
        when(smbMounter.isMounted(VOLUME_A.mountPoint())).thenReturn(false);
        when(credentialLookup.getPassword("pandora", "patrick")).thenReturn("secret");

        command.execute(new String[]{"mount", "a"}, ctx, out);

        verify(smbMounter).mount(VOLUME_A, "secret");
        assertEquals(VOLUME_A, ctx.getMountedVolume());
        assertTrue(output.toString().contains("Mounted"));
    }

    @Test
    void credentialNotFound_printsHelpfulMessage() {
        when(smbMounter.isMounted(VOLUME_A.mountPoint())).thenReturn(false);
        when(credentialLookup.getPassword("pandora", "patrick"))
                .thenThrow(new CredentialNotFoundException("pandora", "patrick"));

        command.execute(new String[]{"mount", "a"}, ctx, out);

        assertNull(ctx.getMountedVolume());
        verify(smbMounter, never()).mount(any(), any());
        String log = output.toString();
        assertTrue(log.contains("Error:"));
        assertTrue(log.contains("security add-internet-password"));
    }

    @Test
    void mountFails_printsErrorAndDoesNotSetContext() {
        when(smbMounter.isMounted(VOLUME_A.mountPoint())).thenReturn(false);
        when(credentialLookup.getPassword("pandora", "patrick")).thenReturn("secret");
        doThrow(new MountException("server unreachable")).when(smbMounter).mount(any(), any());

        command.execute(new String[]{"mount", "a"}, ctx, out);

        assertNull(ctx.getMountedVolume());
        assertTrue(output.toString().contains("Mount failed"));
        assertTrue(output.toString().contains("server unreachable"));
    }

    @Test
    void successfulMount_updatesPromptViaContext() {
        when(smbMounter.isMounted(VOLUME_A.mountPoint())).thenReturn(false);
        when(credentialLookup.getPassword("pandora", "patrick")).thenReturn("secret");

        command.execute(new String[]{"mount", "a"}, ctx, out);

        assertEquals("a", ctx.getMountedVolumeId());
    }
}
