package com.organizer3.smb;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Probes each NAS host on SMB port 445 at a fixed interval and caches the result.
 *
 * <p>Background tasks (trash sweep, thumbnail generation) consult this monitor before
 * attempting SMB operations so they can pause gracefully when a NAS is unreachable
 * rather than flooding logs with connection errors.
 *
 * <p>{@link #start()} performs a synchronous initial probe pass before returning so
 * callers have accurate state immediately — important because
 * {@code TrashSweepScheduler} fires its first sweep at delay=0.
 *
 * <p>Status changes are logged at INFO/WARN; repeated unavailability is silent.
 */
@Slf4j
public class NasAvailabilityMonitor {

    private static final int PROBE_PORT = 445;
    private static final int PROBE_TIMEOUT_MS = 2_000;
    private static final long POLL_INTERVAL_SEC = 30;

    @FunctionalInterface
    public interface Probe {
        boolean isReachable(String host);
    }

    private final Map<String, String> volumeToHost; // volumeId -> hostname
    private final Set<String> hosts;
    private final ConcurrentHashMap<String, Boolean> hostStatus = new ConcurrentHashMap<>();
    private final Probe probe;
    private final boolean sentinel; // alwaysAvailable mode
    private volatile ScheduledExecutorService executor;

    /** Production constructor — probes via TCP connect to port 445. */
    public NasAvailabilityMonitor(OrganizerConfig config) {
        this(config, NasAvailabilityMonitor::tcpProbe);
    }

    /** Testable constructor with injectable probe — visible within package and via {@link #withProbe}. */
    NasAvailabilityMonitor(OrganizerConfig config, Probe probe) {
        this.probe = probe;
        this.sentinel = false;
        Map<String, String> v2h = new HashMap<>();
        for (VolumeConfig vol : config.volumes()) {
            String host = extractHost(vol.smbPath());
            if (host != null) v2h.put(vol.id(), host);
        }
        this.volumeToHost = Map.copyOf(v2h);
        this.hosts = Set.copyOf(new HashSet<>(v2h.values()));
        // Default all hosts to unavailable; first probe pass in start() corrects this.
        hosts.forEach(h -> hostStatus.put(h, false));
    }

    /** Sentinel mode — always reports all hosts as available (for tests). */
    private NasAvailabilityMonitor() {
        this.probe = h -> true;
        this.sentinel = true;
        this.volumeToHost = Map.of();
        this.hosts = Set.of();
    }

    public static NasAvailabilityMonitor alwaysAvailable() {
        return new NasAvailabilityMonitor();
    }

    /** For tests outside the {@code smb} package that need a custom probe. */
    public static NasAvailabilityMonitor withProbe(OrganizerConfig config, Probe probe) {
        return new NasAvailabilityMonitor(config, probe);
    }

    /**
     * Performs a blocking initial probe pass then starts background polling.
     * Returns only after the first pass completes so callers have accurate state.
     */
    public void start() {
        if (sentinel) return;
        probeAll();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nas-monitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::probeAll, POLL_INTERVAL_SEC, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("NAS availability monitor started — watching {} host(s): {}", hosts.size(), hosts);
    }

    public void stop() {
        if (sentinel || executor == null) return;
        executor.shutdown();
    }

    /** Returns true if the volume's NAS host is currently reachable (or volume is unknown). */
    public boolean isVolumeAvailable(String volumeId) {
        if (sentinel) return true;
        String host = volumeToHost.get(volumeId);
        if (host == null) return true;
        return hostStatus.getOrDefault(host, false);
    }

    /** Returns true if all monitored NAS hosts are currently reachable. */
    public boolean areAllHostsAvailable() {
        if (sentinel) return true;
        if (hostStatus.isEmpty()) return true;
        return hostStatus.values().stream().allMatch(Boolean::booleanValue);
    }

    /** Package-private — used by SmbConnectionFactory to check by raw hostname. */
    boolean isHostAvailable(String host) {
        if (sentinel) return true;
        return hostStatus.getOrDefault(host, true); // unknown host → don't block
    }

    private void probeAll() {
        for (String host : hosts) {
            boolean available = probe.isReachable(host);
            Boolean prev = hostStatus.put(host, available);
            if (prev == null || prev != available) {
                if (available) {
                    log.info("NAS host '{}' is now reachable", host);
                } else {
                    log.warn("NAS host '{}' is unreachable — background NAS operations will pause", host);
                }
            }
        }
    }

    private static boolean tcpProbe(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PROBE_PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Extracts the hostname from a smbPath like {@code //pandora/jav_A/sub}. */
    static String extractHost(String smbPath) {
        if (smbPath == null || !smbPath.startsWith("//")) return null;
        String rest = smbPath.substring(2);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
