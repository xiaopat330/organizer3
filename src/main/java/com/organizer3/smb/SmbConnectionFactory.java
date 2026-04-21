package com.organizer3.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Pooled smbj connection factory for the web layer.
 *
 * <p>Maintains a single long-lived {@link SMBClient} plus a per-volume cache of
 * {@link Connection}/{@link Session}/{@link DiskShare} triples. smbj is explicitly
 * designed for client reuse, and a {@code DiskShare} is thread-safe for
 * concurrent {@code openFile} calls — so many concurrent web requests for the
 * same volume share the underlying TCP/SMB session instead of dialling a fresh
 * one each time.
 *
 * <p>Returned {@link SmbShareHandle} objects reference the pooled share but do
 * not own it — their {@code close()} is a no-op on the shared resources. The
 * handle still auto-closes any {@link File} handles the caller opens through it.
 *
 * <p>If a cached connection goes stale (NAS reboot, network blip), the next
 * {@link #open(String)} detects {@code !connection.isConnected()}, evicts, and
 * reconnects once. Operation-time failures bubble up to the caller unchanged.
 */
@Slf4j
public class SmbConnectionFactory {

    private final OrganizerConfig config;
    private final SMBClient client;
    private final Map<String, PooledShare> pool = new ConcurrentHashMap<>();

    public SmbConnectionFactory(OrganizerConfig config) {
        this(config, new SMBClient());
    }

    /** Visible for testing. */
    SmbConnectionFactory(OrganizerConfig config, SMBClient client) {
        this.config = config;
        this.client = client;
    }

    /**
     * Returns a handle backed by the pooled share for this volume, connecting
     * on first use and reusing subsequently.
     */
    public SmbShareHandle open(String volumeId) throws IOException {
        PooledShare pooled = acquire(volumeId);
        return new SmbShareHandle(pooled.share, pooled.subPath);
    }

    /** Closes all pooled connections and the underlying SMBClient. */
    public synchronized void shutdown() {
        for (PooledShare p : pool.values()) p.closeQuietly();
        pool.clear();
        try { client.close(); } catch (Exception e) { /* ignore */ }
    }

    private PooledShare acquire(String volumeId) throws IOException {
        PooledShare existing = pool.get(volumeId);
        if (existing != null && existing.isHealthy()) return existing;

        synchronized (this) {
            existing = pool.get(volumeId);
            if (existing != null && existing.isHealthy()) return existing;
            if (existing != null) {
                log.info("SMB connection to volume {} is stale; reconnecting", volumeId);
                existing.closeQuietly();
                pool.remove(volumeId);
            }
            PooledShare fresh = dial(volumeId);
            pool.put(volumeId, fresh);
            return fresh;
        }
    }

    private PooledShare dial(String volumeId) throws IOException {
        VolumeConfig volume = config.findById(volumeId)
                .orElseThrow(() -> new IOException("Unknown volume: " + volumeId));
        ServerConfig server = config.findServerById(volume.server())
                .orElseThrow(() -> new IOException("Unknown server for volume: " + volumeId));

        ParsedSmbPath parsed = parseSmbPath(volume.smbPath());

        Connection connection = null;
        try {
            connection = client.connect(parsed.host);
            AuthenticationContext auth = new AuthenticationContext(
                    server.username(), server.password().toCharArray(), server.domainOrEmpty());
            Session session = connection.authenticate(auth);
            DiskShare share = (DiskShare) session.connectShare(parsed.share);
            log.info("SMB connection opened for volume {} -> //{}/{}",
                    volumeId, parsed.host, parsed.share);
            return new PooledShare(connection, session, share, parsed.subPath);
        } catch (Exception e) {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { /* ignore */ }
            }
            log.warn("SMB dial failed for volume {} (host={}, share={}): {} [{}]",
                    volumeId, parsed.host, parsed.share, e.getMessage(), e.getClass().getName(), e);
            throw new IOException("Failed to connect to volume " + volumeId + ": " + e.getMessage(), e);
        }
    }

    /**
     * A short-lived handle onto a pooled SMB share. Closing the handle does
     * <strong>not</strong> tear down the underlying share/session/connection —
     * those are owned by the factory.
     */
    public static class SmbShareHandle implements AutoCloseable {
        private final DiskShare share;
        private final String subPath;

        SmbShareHandle(DiskShare share, String subPath) {
            this.share = share;
            this.subPath = subPath;
        }

        /** Lists immediate children of the given path (non-recursive). */
        public List<String> listDirectory(String relativePath) throws IOException {
            String smbPath = toSmbPath(relativePath);
            List<String> result = new ArrayList<>();
            try {
                for (FileIdBothDirectoryInformation info : share.list(smbPath)) {
                    String name = info.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    result.add(name);
                }
            } catch (Exception e) {
                throw new IOException("Failed to list: " + relativePath, e);
            }
            return result;
        }

        public boolean folderExists(String relativePath) {
            return share.folderExists(toSmbPath(relativePath));
        }

        public boolean fileExists(String relativePath) {
            return share.fileExists(toSmbPath(relativePath));
        }

        /** Returns the file size in bytes. */
        public long fileSize(String relativePath) throws IOException {
            String smbPath = toSmbPath(relativePath);
            try (File f = share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions.class))) {
                return f.getFileInformation(FileStandardInformation.class).getEndOfFile();
            } catch (Exception e) {
                throw new IOException("Failed to get file size: " + relativePath, e);
            }
        }

        /** Opens a file for reading at offset 0 and returns the full InputStream. */
        public InputStream openFile(String relativePath) throws IOException {
            String smbPath = toSmbPath(relativePath);
            try {
                File f = share.openFile(
                        smbPath,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions.class));
                return f.getInputStream();
            } catch (Exception e) {
                throw new IOException("Failed to open file: " + relativePath, e);
            }
        }

        /**
         * Expose the share as a {@link com.organizer3.filesystem.VolumeFileSystem}.
         * The returned filesystem is backed by the pooled share and remains
         * usable for as long as the factory keeps the connection alive.
         */
        public com.organizer3.filesystem.VolumeFileSystem fileSystem() {
            return new SmbFileSystem(share, subPath);
        }

        public File openFileHandle(String relativePath) throws IOException {
            String smbPath = toSmbPath(relativePath);
            try {
                return share.openFile(
                        smbPath,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions.class));
            } catch (Exception e) {
                throw new IOException("Failed to open file handle: " + relativePath, e);
            }
        }

        private String toSmbPath(String relativePath) {
            String s = relativePath.replace('/', '\\');
            if (s.startsWith("\\")) s = s.substring(1);
            if (subPath.isEmpty()) return s;
            return s.isEmpty() ? subPath : subPath + '\\' + s;
        }

        /** No-op: the factory owns share/session/connection lifecycle. */
        @Override
        public void close() {
            // Intentionally empty — pooled share stays open for the next caller.
        }
    }

    private static final class PooledShare {
        final Connection connection;
        final Session session;
        final DiskShare share;
        final String subPath;

        PooledShare(Connection connection, Session session, DiskShare share, String subPath) {
            this.connection = connection;
            this.session = session;
            this.share = share;
            this.subPath = subPath;
        }

        boolean isHealthy() {
            return connection.isConnected();
        }

        void closeQuietly() {
            try { share.close(); } catch (Exception ignored) { /* ignore */ }
            try { session.close(); } catch (Exception ignored) { /* ignore */ }
            try { connection.close(); } catch (Exception ignored) { /* ignore */ }
        }
    }

    private static ParsedSmbPath parseSmbPath(String smbPath) {
        if (!smbPath.startsWith("//")) {
            throw new SmbConnectionException("Invalid smbPath (must start with //): " + smbPath);
        }
        String withoutPrefix = smbPath.substring(2);
        int hostSep = withoutPrefix.indexOf('/');
        if (hostSep < 0) {
            throw new SmbConnectionException("Invalid smbPath (no share name): " + smbPath);
        }
        String host = withoutPrefix.substring(0, hostSep);
        String remainder = withoutPrefix.substring(hostSep + 1);
        int shareSep = remainder.indexOf('/');
        if (shareSep < 0) {
            return new ParsedSmbPath(host, remainder, "");
        }
        String share = remainder.substring(0, shareSep);
        String sub = remainder.substring(shareSep + 1).replace('/', '\\');
        return new ParsedSmbPath(host, share, sub);
    }

    private record ParsedSmbPath(String host, String share, String subPath) {}
}
