package com.organizer3.smb;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NasAvailabilityMonitorTest {

    // ── Host extraction ────────────────────────────────────────────────────────

    @Test
    void extractsHostFromStandardSmbPath() {
        assertEquals("pandora", NasAvailabilityMonitor.extractHost("//pandora/jav_A"));
    }

    @Test
    void extractsHostFromSmbPathWithSubPath() {
        assertEquals("atlas", NasAvailabilityMonitor.extractHost("//atlas/jav_B/sub/path"));
    }

    @Test
    void returnsNullForInvalidSmbPath() {
        assertNull(NasAvailabilityMonitor.extractHost("not-a-smb-path"));
        assertNull(NasAvailabilityMonitor.extractHost(null));
    }

    // ── Status tracking ────────────────────────────────────────────────────────

    @Test
    void reportsVolumeAvailableWhenProbeSucceeds() {
        OrganizerConfig config = configWith(
                server("srv"),
                volume("volA", "//pandora/jav_A", "srv"));

        NasAvailabilityMonitor monitor = new NasAvailabilityMonitor(config, host -> true);
        monitor.start();

        assertTrue(monitor.isVolumeAvailable("volA"));
        assertTrue(monitor.areAllHostsAvailable());

        monitor.stop();
    }

    @Test
    void reportsVolumeUnavailableWhenProbeFails() {
        OrganizerConfig config = configWith(
                server("srv"),
                volume("volA", "//pandora/jav_A", "srv"));

        NasAvailabilityMonitor monitor = new NasAvailabilityMonitor(config, host -> false);
        monitor.start();

        assertFalse(monitor.isVolumeAvailable("volA"));
        assertFalse(monitor.areAllHostsAvailable());

        monitor.stop();
    }

    @Test
    void deduplicatesHostsAcrossVolumesOnSameServer() {
        OrganizerConfig config = configWith(
                server("srv"),
                volume("volA", "//pandora/jav_A", "srv"),
                volume("volB", "//pandora/jav_B", "srv"));

        AtomicInteger probeCount = new AtomicInteger();
        NasAvailabilityMonitor monitor = new NasAvailabilityMonitor(config, host -> {
            probeCount.incrementAndGet();
            return true;
        });
        monitor.start();

        // Two volumes on the same host — should probe pandora only once
        assertEquals(1, probeCount.get());
        assertTrue(monitor.isVolumeAvailable("volA"));
        assertTrue(monitor.isVolumeAvailable("volB"));

        monitor.stop();
    }

    @Test
    void areAllHostsAvailableReturnsFalseWhenAnyHostDown() {
        OrganizerConfig config = configWith(
                server("srv"),
                volume("volA", "//pandora/jav_A", "srv"),
                volume("volB", "//atlas/jav_B", "srv"));

        // pandora up, atlas down
        NasAvailabilityMonitor monitor = new NasAvailabilityMonitor(config,
                host -> host.equals("pandora"));
        monitor.start();

        assertTrue(monitor.isVolumeAvailable("volA"));
        assertFalse(monitor.isVolumeAvailable("volB"));
        assertFalse(monitor.areAllHostsAvailable());

        monitor.stop();
    }

    @Test
    void unknownVolumeIsConsideredAvailable() {
        OrganizerConfig config = configWith(server("srv"), volume("volA", "//pandora/jav_A", "srv"));
        NasAvailabilityMonitor monitor = new NasAvailabilityMonitor(config, host -> false);
        monitor.start();

        assertTrue(monitor.isVolumeAvailable("no-such-volume"));

        monitor.stop();
    }

    // ── alwaysAvailable sentinel ───────────────────────────────────────────────

    @Test
    void alwaysAvailableSentinelReportsAllAvailable() {
        NasAvailabilityMonitor sentinel = NasAvailabilityMonitor.alwaysAvailable();
        assertTrue(sentinel.isVolumeAvailable("anything"));
        assertTrue(sentinel.areAllHostsAvailable());
        // start/stop are no-ops — should not throw
        sentinel.start();
        sentinel.stop();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static OrganizerConfig configWith(ServerConfig server, VolumeConfig... volumes) {
        return new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(server), List.of(volumes),
                null, null, null);
    }

    private static ServerConfig server(String id) {
        return new ServerConfig(id, "user", "pass", "");
    }

    private static VolumeConfig volume(String id, String smbPath, String server) {
        return new VolumeConfig(id, smbPath, "standard", server, null);
    }
}
