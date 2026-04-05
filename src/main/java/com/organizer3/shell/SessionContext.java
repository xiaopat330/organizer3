package com.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.sync.VolumeIndex;

/**
 * Holds mutable session state for the current interactive session.
 *
 * Passed to commands so they can read and update state (mounted volume,
 * dry-run mode, running flag). Designed to be injected — never accessed
 * as a singleton — so tests can construct isolated instances freely.
 */
public class SessionContext {
    private boolean dryRun = true;
    private VolumeConfig mountedVolume = null;
    private VolumeIndex index = null;
    private boolean running = true;

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public VolumeConfig getMountedVolume() {
        return mountedVolume;
    }

    /** Returns the ID of the currently mounted volume, or {@code null} if none. */
    public String getMountedVolumeId() {
        return mountedVolume != null ? mountedVolume.id() : null;
    }

    public void setMountedVolume(VolumeConfig volume) {
        this.mountedVolume = volume;
    }

    public VolumeIndex getIndex() {
        return index;
    }

    public void setIndex(VolumeIndex index) {
        this.index = index;
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        this.running = false;
    }
}
