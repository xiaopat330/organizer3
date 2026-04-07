package com.organizer3.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens an authenticated SMB2/3 connection using smbj.
 *
 * <p>Parses {@code smbPath} (e.g. {@code //qnap2/jav}) to extract the hostname
 * and share name, then authenticates with the username and password from config.
 */
@Slf4j
public class SmbjConnector implements SmbConnector {

    @Override
    public VolumeConnection connect(VolumeConfig volume, ServerConfig server, MountProgressListener progress)
            throws SmbConnectionException {
        ParsedSmbPath parsed = parseSmbPath(volume.smbPath());

        log.info("Connecting to \\\\{}\\{} as {}", parsed.host, parsed.share, server.username());

        SMBClient client = new SMBClient();
        try {
            progress.onStep("Connecting to " + parsed.host);
            Connection connection = client.connect(parsed.host);

            progress.onStep("Authenticating as " + server.username());
            AuthenticationContext auth = new AuthenticationContext(
                    server.username(), server.password().toCharArray(), server.domainOrEmpty());
            Session session = connection.authenticate(auth);

            progress.onStep("Opening share " + parsed.share);
            DiskShare share = (DiskShare) session.connectShare(parsed.share);

            log.info("Connected to \\\\{}\\{}", parsed.host, parsed.share);
            return new SmbVolumeConnection(client, connection, session, share, parsed.subPath);
        } catch (IOException e) {
            closeQuietly(client);
            throw new SmbConnectionException(
                    "Failed to connect to " + volume.smbPath() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            closeQuietly(client);
            throw new SmbConnectionException(
                    "Authentication failed for " + volume.smbPath() + " as " + server.username()
                            + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses {@code //host/share} or {@code //host/share/sub/path} into its components.
     * The share name is always the first path segment; anything after is the sub-path.
     */
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
        // Convert remaining Unix path segments to Windows-style sub-path
        String subPath = remainder.substring(shareSep + 1).replace('/', '\\');
        return new ParsedSmbPath(host, share, subPath);
    }

    private record ParsedSmbPath(String host, String share, String subPath) {}

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
