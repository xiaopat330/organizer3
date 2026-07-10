package com.organizer3.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.organizer3.config.AppConfig;
import com.organizer3.config.SmbSettings;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens an authenticated SMB2/3 connection using smbj.
 *
 * <p>Parses {@code smbPath} (e.g. {@code //qnap2/jav}) to extract the hostname
 * and share name, then authenticates with the username and password from config.
 *
 * <p>The {@link SMBClient} is constructed with explicit read/write/transact timeouts
 * from the {@code smb:} config block (see {@link SmbSettings}). This ensures a dead TCP
 * connection is detected within minutes rather than hanging forever — the root cause of
 * the 2026-05-07 coherent sync hang. See {@code spec/PROPOSAL_SMB_TIMEOUT_HARDENING.md §3.1}.
 */
@Slf4j
public class SmbjConnector implements SmbConnector {

    // TODO: plumb through SmbSettings once a connectTimeoutSeconds field exists.
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 15;

    private final int connectTimeoutSeconds;

    public SmbjConnector() {
        this(DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    public SmbjConnector(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    @Override
    public VolumeConnection connect(VolumeConfig volume, ServerConfig server, MountProgressListener progress)
            throws SmbConnectionException {
        ParsedSmbPath parsed = parseSmbPath(volume.smbPath());

        log.info("Connecting to \\\\{}\\{} as {}", parsed.host, parsed.share, server.username());

        SmbSettings settings = AppConfig.get().volumes().smbOrDefaults();
        SMBClient client = new SMBClient(buildSmbConfig(settings));
        try {
            progress.onStep("Connecting to " + parsed.host);
            Connection connection = connectWithTimeout(
                    () -> client.connect(parsed.host), parsed.host, connectTimeoutSeconds);

            progress.onStep("Authenticating as " + server.username());
            AuthenticationContext auth = new AuthenticationContext(
                    server.username(), server.password().toCharArray(), server.domainOrEmpty());
            Session session = connection.authenticate(auth);

            progress.onStep("Opening share " + parsed.share);
            DiskShare share = (DiskShare) session.connectShare(parsed.share);

            log.info("Connected to \\\\{}\\{}", parsed.host, parsed.share);
            return new SmbVolumeConnection(client, connection, session, share, parsed.subPath);
        } catch (SmbConnectionException e) {
            closeQuietly(client);
            throw e;
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

    @FunctionalInterface
    interface ConnectOp {
        Connection call() throws IOException;
    }

    static Connection connectWithTimeout(ConnectOp op, String host, int timeoutSeconds)
            throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smbj-connect-" + host);
            t.setDaemon(true);
            return t;
        });
        Future<Connection> future = executor.submit(op::call);
        executor.shutdown();
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            executor.shutdownNow();
            throw new SmbConnectionException(
                    "SMB connect to " + host + " timed out after " + timeoutSeconds + "s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new SmbConnectionException("SMB connect to " + host + " interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new SmbConnectionException(
                    "SMB connect to " + host + " failed: " + cause.getMessage(), cause);
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

    static SmbConfig buildSmbConfig(SmbSettings settings) {
        return SmbConfig.builder()
                .withReadTimeout(settings.readTimeoutSecondsOrDefault(), TimeUnit.SECONDS)
                .withWriteTimeout(settings.writeTimeoutMinutesOrDefault(), TimeUnit.MINUTES)
                .withTransactTimeout(settings.transactTimeoutSecondsOrDefault(), TimeUnit.SECONDS)
                .withSoTimeout(settings.readTimeoutMinutesOrDefault(), TimeUnit.MINUTES)
                .build();
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
