package com.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.VolumeIndex;
import lombok.Getter;
import lombok.Setter;

/**
 * Holds mutable session state for the current interactive session.
 *
 * Passed to commands so they can read and update state (mounted volume,
 * dry-run mode, running flag). Designed to be injected — never accessed
 * as a singleton — so tests can construct isolated instances freely.
 */
@Getter
public class SessionContext {
    @Setter private boolean dryRun = true;
    @Setter private VolumeConfig mountedVolume = null;
    @Setter private VolumeConnection activeConnection = null;
    @Setter private VolumeIndex index = null;
    private boolean running = true;

    /** Returns the ID of the currently mounted volume, or {@code null} if none. */
    public String getMountedVolumeId() {
        return mountedVolume != null ? mountedVolume.id() : null;
    }

    public boolean isConnected() {
        return activeConnection != null && activeConnection.isConnected();
    }

    public void shutdown() {
        if (activeConnection != null) {
            activeConnection.close();
            activeConnection = null;
        }
        this.running = false;
    }
}
