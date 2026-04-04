package com.organizer3.shell;

/**
 * Holds mutable session state for the current interactive session.
 *
 * Passed to commands so they can read and update state (mounted volume,
 * dry-run mode, running flag). Designed to be injected — never accessed
 * as a singleton — so tests can construct isolated instances freely.
 */
public class SessionContext {
    private boolean dryRun = true;
    private String mountedVolumeId = null;
    private boolean running = true;

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getMountedVolumeId() {
        return mountedVolumeId;
    }

    public void setMountedVolumeId(String id) {
        this.mountedVolumeId = id;
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        this.running = false;
    }
}
