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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens short-lived smbj connections for the web layer.
 *
 * <p>Decoupled from the shell's {@code SessionContext} — the web layer can access
 * any volume regardless of what the shell has mounted. Each call opens a fresh
 * connection and returns a closeable handle.
 */
@Slf4j
@RequiredArgsConstructor
public class SmbConnectionFactory {

    private final OrganizerConfig config;

    /**
     * Opens an SMB share for the given volume and returns a handle that provides
     * directory listing and file access. The caller must close it when done.
     */
    public SmbShareHandle open(String volumeId) throws IOException {
        VolumeConfig volume = config.findById(volumeId)
                .orElseThrow(() -> new IOException("Unknown volume: " + volumeId));
        ServerConfig server = config.findServerById(volume.server())
                .orElseThrow(() -> new IOException("Unknown server for volume: " + volumeId));

        ParsedSmbPath parsed = parseSmbPath(volume.smbPath());

        SMBClient client = new SMBClient();
        try {
            Connection connection = client.connect(parsed.host);
            AuthenticationContext auth = new AuthenticationContext(
                    server.username(), server.password().toCharArray(), server.domainOrEmpty());
            Session session = connection.authenticate(auth);
            DiskShare share = (DiskShare) session.connectShare(parsed.share);
            return new SmbShareHandle(client, connection, session, share, parsed.subPath);
        } catch (Exception e) {
            closeQuietly(client);
            throw new IOException("Failed to connect to volume " + volumeId + ": " + e.getMessage(), e);
        }
    }

    /**
     * A short-lived handle to an open SMB share, providing directory listing
     * and file streaming.
     */
    public static class SmbShareHandle implements AutoCloseable {
        private final SMBClient client;
        private final Connection connection;
        private final Session session;
        private final DiskShare share;
        private final String subPath;

        SmbShareHandle(SMBClient client, Connection connection, Session session,
                       DiskShare share, String subPath) {
            this.client = client;
            this.connection = connection;
            this.session = session;
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
         * Opens a file for random-access reading. The returned smbj {@link File} handle
         * supports {@code read(byte[], long, int, int)} for offset-based reads.
         * The caller must close the returned handle.
         */
        /**
         * Expose the share as a {@link com.organizer3.filesystem.VolumeFileSystem} so write
         * paths (move, createDirectories, writeFile) are available from the web layer.
         * The returned filesystem is backed by the same {@link DiskShare} as this handle —
         * closing the handle closes the filesystem.
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

        @Override
        public void close() {
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
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

    private static void closeQuietly(AutoCloseable resource) {
        try { resource.close(); } catch (Exception e) { /* ignore */ }
    }
}
