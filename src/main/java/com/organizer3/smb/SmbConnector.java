package com.organizer3.smb;

import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;

/**
 * Opens an authenticated connection to an SMB share described by a {@link VolumeConfig}.
 */
public interface SmbConnector {

    /**
     * Connects to the share and returns an active {@link VolumeConnection}.
     * Reports each connection phase (host resolution, auth, share connect) to the listener.
     *
     * @throws SmbConnectionException if the connection or authentication fails
     */
    VolumeConnection connect(VolumeConfig volume, ServerConfig server, MountProgressListener progress)
            throws SmbConnectionException;

    /**
     * Convenience overload with a no-op progress listener.
     */
    default VolumeConnection connect(VolumeConfig volume, ServerConfig server) throws SmbConnectionException {
        return connect(volume, server, msg -> {});
    }
}
