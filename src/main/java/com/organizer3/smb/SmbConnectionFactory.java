package com.organizer3.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.organizer3.config.SmbSettings;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    private final NasAvailabilityMonitor monitor;
    private final Map<String, PooledShare> pool = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PooledShare>> inFlightDials = new ConcurrentHashMap<>();
    private final ExecutorService dialExecutor;
    private volatile long dialTimeoutMillis;
    // Visible for testing.
    void setDialTimeoutMillisForTesting(long millis) { this.dialTimeoutMillis = millis; }
    // Visible for testing — read back the effective dial timeout after construction.
    long dialTimeoutMillisForTesting() { return this.dialTimeoutMillis; }
    private volatile boolean shutdown = false;

    public SmbConnectionFactory(OrganizerConfig config) {
        this(config, new SMBClient(buildSmbConfig(config.smbOrDefaults())), null);
    }

    public SmbConnectionFactory(OrganizerConfig config, NasAvailabilityMonitor monitor) {
        this(config, new SMBClient(buildSmbConfig(config.smbOrDefaults())), monitor);
    }

    /** Visible for testing. */
    SmbConnectionFactory(OrganizerConfig config, SMBClient client) {
        this(config, client, null);
    }

    SmbConnectionFactory(OrganizerConfig config, SMBClient client, NasAvailabilityMonitor monitor) {
        this.config = config;
        this.client = client;
        this.monitor = monitor;
        this.dialExecutor = newDialExecutor();
        // Outer dial budget — guards against TCP-connect + SMB session-setup hanging forever
        // when the NAS host is reachable by ping but its SMB service is wedged. This is kept
        // intentionally short (default 10 s) and distinct from the read/write/transact timeouts
        // (which govern ongoing SMB I/O and stay in minutes). The Future wrapper is the backstop
        // when smbj's own transactTimeout fails to surface during authentication.
        this.dialTimeoutMillis = TimeUnit.SECONDS.toMillis(
                config.smbOrDefaults().dialTimeoutSecondsOrDefault());
    }

    private static ExecutorService newDialExecutor() {
        AtomicLong seq = new AtomicLong();
        return new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "smb-dial-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Operation over an SMB share that may produce a result and may throw IOException. */
    @FunctionalInterface
    public interface SmbOperation<T> {
        T execute(SmbShareHandle handle) throws IOException;
    }

    /**
     * Returns a handle backed by the pooled share for this volume, connecting
     * on first use and reusing subsequently.
     */
    public SmbShareHandle open(String volumeId) throws IOException {
        PooledShare pooled = acquire(volumeId);
        return new SmbShareHandle(pooled.share, pooled.subPath);
    }

    /**
     * Executes {@code op} with the pooled share for {@code volumeId}. If the
     * operation fails with a broken-pipe / transport error (NAS dropped the idle
     * TCP connection), the stale entry is evicted, a fresh connection is dialled,
     * and the operation is retried exactly once.
     *
     * <p>Catches both {@link IOException} and unchecked {@code SMBRuntimeException}
     * (thrown by methods like {@code share.folderExists()} that don't declare a
     * checked exception) so that broken-pipe detection fires regardless of which
     * SMB call fails first.
     */
    public <T> T withRetry(String volumeId, SmbOperation<T> op) throws IOException {
        try {
            return op.execute(open(volumeId));
        } catch (IOException | RuntimeException e) {
            if (isBrokenPipe(e)) {
                log.info("SMB broken pipe on volume {}; evicting and retrying", volumeId);
                evict(volumeId);
                return op.execute(open(volumeId));
            }
            if (e instanceof IOException ioe) throw ioe;
            throw (RuntimeException) e;
        }
    }

    /**
     * Like {@link #withRetry} but retries on <em>any</em> exception, not just
     * broken-pipe / transport errors. Use this for short, idempotent operations
     * where the cost of one wasted retry is negligible — e.g. video-stream
     * setup (fileSize + openFileHandle).
     *
     * <p>This sidesteps the {@link #isBrokenPipe} heuristic, which doesn't
     * recognize SMB-side session-state failures (SMBApiException with status
     * codes like STATUS_USER_SESSION_DELETED) since those are wrapped in
     * generic IOExceptions by callers like {@link SmbShareHandle#fileSize}.
     */
    public <T> T withForceRetry(String volumeId, SmbOperation<T> op) throws IOException {
        try {
            return op.execute(open(volumeId));
        } catch (IOException | RuntimeException e) {
            log.info("SMB op failed on volume {} (exception={}); evicting and retrying once",
                    volumeId, e.getClass().getSimpleName());
            evict(volumeId);
            return op.execute(open(volumeId));
        }
    }

    /**
     * Returns {@code true} if the given volume's NAS host is currently considered reachable
     * by the availability monitor, or {@code true} when no monitor is configured (fail-open).
     *
     * <p>Use this to short-circuit SMB operations before dialling when a fast availability
     * check is cheaper than a full dial. Note that the monitor tracks host-level reachability
     * (via ping), not SMB-service-level reachability — a "ping-up but SMB-wedged" host will
     * still return {@code true} here; the dial timeout is the backstop for that case.
     */
    public boolean isVolumeAvailable(String volumeId) {
        if (monitor == null) return true;
        return monitor.isVolumeAvailable(volumeId);
    }

    /** Removes a volume's pooled connection so the next {@link #open} dials fresh. */
    public void evict(String volumeId) {
        PooledShare stale = pool.remove(volumeId);
        if (stale != null) {
            stale.closeQuietly();
            log.info("Evicted SMB pool entry for volume {}", volumeId);
        }
    }

    /** Closes all pooled connections and the underlying SMBClient. */
    public void shutdown() {
        shutdown = true;
        // Cancel any in-flight dials so callers waiting on them unblock.
        for (CompletableFuture<PooledShare> f : inFlightDials.values()) {
            f.cancel(true);
        }
        inFlightDials.clear();
        dialExecutor.shutdownNow();
        for (PooledShare p : pool.values()) p.closeQuietly();
        pool.clear();
        try { client.close(); } catch (Exception e) { /* ignore */ }
    }

    private PooledShare acquire(String volumeId) throws IOException {
        PooledShare existing = pool.get(volumeId);
        if (existing != null && existing.isHealthy()) return existing;

        // Coalesce concurrent dials for the same volume; different volumes never block each other.
        CompletableFuture<PooledShare> mine = new CompletableFuture<>();
        CompletableFuture<PooledShare> inFlight = inFlightDials.putIfAbsent(volumeId, mine);
        if (inFlight != null) {
            return awaitInFlight(volumeId, inFlight);
        }
        try {
            // Re-check under our own slot (avoid duplicate dial if a previous owner just finished).
            existing = pool.get(volumeId);
            if (existing != null && existing.isHealthy()) {
                mine.complete(existing);
                return existing;
            }
            if (existing != null) {
                log.info("SMB connection to volume {} is stale; reconnecting", volumeId);
                existing.closeQuietly();
                pool.remove(volumeId, existing);
            }
            if (shutdown) throw new IOException("SmbConnectionFactory is shut down");
            PooledShare fresh = dialWithTimeout(volumeId);
            pool.put(volumeId, fresh);
            mine.complete(fresh);
            return fresh;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            if (t instanceof IOException ioe) throw ioe;
            if (t instanceof RuntimeException re) throw re;
            throw new IOException("Dial failed for volume " + volumeId + ": " + t.getMessage(), t);
        } finally {
            inFlightDials.remove(volumeId, mine);
        }
    }

    private PooledShare awaitInFlight(String volumeId, CompletableFuture<PooledShare> inFlight)
            throws IOException {
        try {
            return inFlight.get(dialTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Timed out waiting for in-flight dial of volume " + volumeId, e);
        } catch (CancellationException e) {
            throw new IOException("In-flight dial of volume " + volumeId + " was cancelled", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException ioe) throw ioe;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException("Dial failed for volume " + volumeId + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for dial of volume " + volumeId, e);
        }
    }

    private PooledShare dialWithTimeout(String volumeId) throws IOException {
        Future<PooledShare> future = dialExecutor.submit(() -> dial(volumeId));
        try {
            return future.get(dialTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("SMB dial for volume {} timed out after {} ms; cancelled", volumeId, dialTimeoutMillis);
            throw new IOException("Dial timed out for volume " + volumeId
                    + " after " + dialTimeoutMillis + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException ioe) throw ioe;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException("Dial failed for volume " + volumeId + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while dialling volume " + volumeId, e);
        }
    }

    /** Visible for testing — override to simulate dial outcomes. */
    protected PooledShare dial(String volumeId) throws IOException {
        VolumeConfig volume = config.findById(volumeId)
                .orElseThrow(() -> new IOException("Unknown volume: " + volumeId));
        ServerConfig server = config.findServerById(volume.server())
                .orElseThrow(() -> new IOException("Unknown server for volume: " + volumeId));

        ParsedSmbPath parsed = parseSmbPath(volume.smbPath());

        if (monitor != null && !monitor.isHostAvailable(parsed.host)) {
            throw new IOException("NAS host '" + parsed.host + "' is currently unreachable");
        }

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

    static class PooledShare {
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

        /** Test-only constructor — for stubbing in unit tests without real smbj objects. */
        PooledShare(String subPath) {
            this.connection = null;
            this.session = null;
            this.share = null;
            this.subPath = subPath;
        }

        boolean isHealthy() {
            return connection != null && connection.isConnected();
        }

        void closeQuietly() {
            if (share != null)      try { share.close();      } catch (Exception ignored) { /* ignore */ }
            if (session != null)    try { session.close();    } catch (Exception ignored) { /* ignore */ }
            if (connection != null) try { connection.close(); } catch (Exception ignored) { /* ignore */ }
        }
    }

    /** Walks the cause chain to detect broken-pipe / transport-level failures. */
    private static boolean isBrokenPipe(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketException) {
                String msg = cause.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                    return true;
                }
            }
            String cn = cause.getClass().getName();
            if (cn.contains("TransportException") || cn.contains("SMBRuntimeException")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds an {@link SmbConfig} from the given {@link SmbSettings}.
     *
     * <p>Sets read/write/transact timeouts so a dead TCP connection is detected within
     * minutes rather than hanging forever. The socket-level {@code soTimeout} mirrors the
     * read timeout as a backstop for cases where the smbj protocol layer does not surface
     * the timeout itself.
     *
     * @see <a href="spec/PROPOSAL_SMB_TIMEOUT_HARDENING.md">SMB Timeout Hardening §3.1</a>
     */
    static SmbConfig buildSmbConfig(SmbSettings settings) {
        return SmbConfig.builder()
                .withReadTimeout(settings.readTimeoutSecondsOrDefault(), TimeUnit.SECONDS)
                .withWriteTimeout(settings.writeTimeoutMinutesOrDefault(), TimeUnit.MINUTES)
                .withTransactTimeout(settings.transactTimeoutSecondsOrDefault(), TimeUnit.SECONDS)
                .withSoTimeout(settings.readTimeoutMinutesOrDefault(), TimeUnit.MINUTES)
                .build();
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
