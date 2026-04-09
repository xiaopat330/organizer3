package com.organizer3.smb;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmbConnectionFactoryTest {

    @Test
    void throwsForUnknownVolume() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.findById("nonexistent")).thenReturn(Optional.empty());

        SmbConnectionFactory factory = new SmbConnectionFactory(config);

        IOException ex = assertThrows(IOException.class, () -> factory.open("nonexistent"));
        assertTrue(ex.getMessage().contains("Unknown volume"));
    }

    @Test
    void throwsForUnknownServer() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        VolumeConfig volume = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        when(config.findById("a")).thenReturn(Optional.of(volume));
        when(config.findServerById("pandora")).thenReturn(Optional.empty());

        SmbConnectionFactory factory = new SmbConnectionFactory(config);

        IOException ex = assertThrows(IOException.class, () -> factory.open("a"));
        assertTrue(ex.getMessage().contains("Unknown server"));
    }

    @Test
    void throwsForInvalidSmbPath() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        VolumeConfig volume = new VolumeConfig("a", "not-a-smb-path", "conventional", "pandora", null);
        ServerConfig server = new ServerConfig("pandora", "user", "pass", null);
        when(config.findById("a")).thenReturn(Optional.of(volume));
        when(config.findServerById("pandora")).thenReturn(Optional.of(server));

        SmbConnectionFactory factory = new SmbConnectionFactory(config);

        // Should throw due to invalid smbPath format
        assertThrows(Exception.class, () -> factory.open("a"));
    }
}
