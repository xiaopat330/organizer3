package com.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.VolumeIndex;
import lombok.Getter;
import lombok.Setter;

/**
 * Holds mutable session state for the current interactive session.
 *
 * <p>Passed to commands so they can read and update state (mounted volume,
 * dry-run mode, running flag). Designed to be injected — never accessed
 * as a singleton — so tests can construct isolated instances freely.
 *
 * <p>Mount-related fields ({@code mountedVolume}, {@code activeConnection},
 * {@code index}) are guarded by {@code this} monitor. The MCP harness dispatches
 * tool calls on Javalin worker threads; many tools read mountedVolumeId and
 * activeConnection together and assume they refer to the same mount. The
 * {@link #setMount(VolumeConfig, VolumeConnection, VolumeIndex)} atomic swap
 * guarantees readers never observe a torn state where the id and connection
 * disagree.
 */
public class SessionContext {
    @Getter @Setter private volatile boolean dryRun = true;
    private VolumeConfig mountedVolume = null;
    private VolumeConnection activeConnection = null;
    private VolumeIndex index = null;
    @Getter private volatile boolean running = true;

    public synchronized VolumeConfig getMountedVolume() {
        return mountedVolume;
    }

    public synchronized VolumeConnection getActiveConnection() {
        return activeConnection;
    }

    public synchronized VolumeIndex getIndex() {
        return index;
    }

    public synchronized void setMountedVolume(VolumeConfig mountedVolume) {
        this.mountedVolume = mountedVolume;
    }

    public synchronized void setActiveConnection(VolumeConnection activeConnection) {
        this.activeConnection = activeConnection;
    }

    public synchronized void setIndex(VolumeIndex index) {
        this.index = index;
    }

    /**
     * Atomically install a new mount triple. Any concurrent reader observes either
     * the prior values together or the new values together — never a mix.
     */
    public synchronized void setMount(VolumeConfig volume, VolumeConnection connection, VolumeIndex index) {
        this.mountedVolume = volume;
        this.activeConnection = connection;
        this.index = index;
    }

    /** Atomically clear the mount triple. */
    public synchronized void clearMount() {
        this.mountedVolume = null;
        this.activeConnection = null;
        this.index = null;
    }

    /** Returns the ID of the currently mounted volume, or {@code null} if none. */
    public synchronized String getMountedVolumeId() {
        return mountedVolume != null ? mountedVolume.id() : null;
    }

    public synchronized boolean isConnected() {
        return activeConnection != null && activeConnection.isConnected();
    }

    public synchronized void shutdown() {
        if (activeConnection != null) {
            activeConnection.close();
            activeConnection = null;
        }
        this.running = false;
    }
}
