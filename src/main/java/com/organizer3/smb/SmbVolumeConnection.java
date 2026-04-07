package com.organizer3.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds the live smbj resources for an active SMB connection and exposes them
 * as a {@link VolumeFileSystem}.
 *
 * <p>Resources are closed in order: share → session → connection → client.
 */
@Slf4j
class SmbVolumeConnection implements VolumeConnection {

    private final SMBClient client;
    private final Connection connection;
    private final Session session;
    private final DiskShare share;
    private final SmbFileSystem fileSystem;

    SmbVolumeConnection(SMBClient client, Connection connection, Session session,
                        DiskShare share, String subPath) {
        this.client = client;
        this.connection = connection;
        this.session = session;
        this.share = share;
        this.fileSystem = new SmbFileSystem(share, subPath);
    }

    @Override
    public VolumeFileSystem fileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isConnected() {
        return share.isConnected();
    }

    @Override
    public void close() {
        closeQuietly("share", share);
        closeQuietly("session", session);
        closeQuietly("connection", connection);
        closeQuietly("client", client);
    }

    private void closeQuietly(String name, AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("Error closing SMB {}: {}", name, e.getMessage());
        }
    }
}
