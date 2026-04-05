package com.organizer3.smb;

import com.organizer3.filesystem.VolumeFileSystem;

/**
 * An active connection to an SMB share, providing filesystem access.
 *
 * <p>The connection is established by {@link SmbConnector#connect} and lives for
 * the duration of the session. It must be closed when the session ends or when
 * switching to a different volume.
 */
public interface VolumeConnection extends AutoCloseable {

    VolumeFileSystem fileSystem();

    boolean isConnected();

    @Override
    void close();
}
