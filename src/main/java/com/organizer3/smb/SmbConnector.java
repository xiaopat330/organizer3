package com.organizer3.smb;

import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;

/**
 * Opens an authenticated connection to an SMB share described by a {@link VolumeConfig}.
 */
public interface SmbConnector {

    /**
     * Connects to the share and returns an active {@link VolumeConnection}.
     *
     * @throws SmbConnectionException if the connection or authentication fails
     */
    VolumeConnection connect(VolumeConfig volume, ServerConfig server) throws SmbConnectionException;
}
