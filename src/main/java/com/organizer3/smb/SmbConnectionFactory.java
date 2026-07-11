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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
    // Bounded-close worker: teardown of a pooled connection (share/session/connection close)
    // runs here off the caller/sweeper thread so a wedged socket can never block the caller
    // beyond closeTimeoutMillis. Mirrors dialExecutor: cached, unbounded, daemon threads.
    private final ExecutorService closeExecutor;
    // Dedicated bounded-probe worker (Wave 3). A liveness share-stat is itself an SMB round-trip that
    // can hang on a wedged socket; running it here (off the sweep thread) with probeTimeoutMillis keeps
    // a hung probe from ever blocking the sweep. Kept separate from closeExecutor so a storm of probes
    // never starves the teardown worker. Cached, unbounded, daemon threads (mirrors dialExecutor).
    private final ExecutorService probeExecutor;
    // Background pool-sweep scheduler (Wave 3), created lazily by startPoolSweep() from Application to
    // avoid a `this`-escape from the constructor. Null until started; stopped by shutdown().
    private volatile ScheduledExecutorService sweepScheduler;
    private volatile long dialTimeoutMillis;
    private volatile long closeTimeoutMillis;
    // Wave 3 tunables (seconds in config, millis here). The sweep probes each pooled connection with a
    // bounded share-stat every poolSweepIntervalMillis, evicts dead ones, and — when EVERY pooled host
    // fails one pass at once (the Stage-0 multi-host network-change signature) — tears down all
    // connections via invalidateAll(), debounced to once per event.
    private volatile long poolSweepIntervalMillis;
    private volatile long probeTimeoutMillis;
    private volatile boolean networkChangeTeardownEnabled;
    // Sensor debounce (Wave 3). Event-based, not time-windowed: armed → the all-hosts-down sensor may
    // fire invalidateAll(); firing disarms it (fire once per event); any later pass that sees ≥1 pooled
    // connection probe OK (recovery) re-arms it. An empty-pool pass neither fires nor re-arms.
    private volatile boolean sensorArmed = true;
    // Injectable wall-clock source (foundational seam for later waves' TTL/idle/age logic).
    // Read on the hot path from Wave 2 on — the breaker times cooldowns/windows through it.
    private volatile LongSupplier nowMillis = System::currentTimeMillis;
    // Per-host dial circuit-breaker (Wave 2). A VPN switch downs a whole host; the breaker
    // fast-fails every volume on that host after a few dial failures and allows one half-open
    // probe per cooldown, killing the reconciler's N-items x dial-timeout thrash. Reads the
    // clock through the live nowMillis field so setNowMillisForTesting (set after construction)
    // reaches it. Volatile so setDialBackoffForTesting can rebuild it.
    private volatile HostDialBreaker breaker;
    // Visible for testing.
    void setDialTimeoutMillisForTesting(long millis) { this.dialTimeoutMillis = millis; }
    // Visible for testing — read back the effective dial timeout after construction.
    long dialTimeoutMillisForTesting() { return this.dialTimeoutMillis; }
    // Visible for testing.
    void setCloseTimeoutMillisForTesting(long millis) { this.closeTimeoutMillis = millis; }
    // Visible for testing — override the wall-clock source.
    void setNowMillisForTesting(LongSupplier supplier) { this.nowMillis = supplier; }
    // Visible for testing — read the current wall-clock value through the injectable source.
    long nowMillisForTesting() { return this.nowMillis.getAsLong(); }
    // Visible for testing — rebuild the breaker with explicit params (keeps the live clock supplier).
    void setDialBackoffForTesting(int threshold, long windowMillis, long cooldownMillis) {
        this.breaker = new HostDialBreaker(() -> nowMillis.getAsLong(), threshold, windowMillis, cooldownMillis);
    }
    // Visible for testing — the breaker instance (for direct state assertions).
    HostDialBreaker dialBreakerForTesting() { return this.breaker; }
    // Visible for testing — override the liveness-probe timeout (millis).
    void setProbeTimeoutMillisForTesting(long millis) { this.probeTimeoutMillis = millis; }
    // Visible for testing — gate the auto-teardown fire independently of the dead-eviction sweep.
    void setNetworkChangeTeardownEnabledForTesting(boolean enabled) { this.networkChangeTeardownEnabled = enabled; }
    // Visible for testing — read/prime the sensor arm state.
    void setSensorArmedForTesting(boolean armed) { this.sensorArmed = armed; }
    boolean sensorArmedForTesting() { return this.sensorArmed; }
    // Visible for testing — current number of pooled entries.
    int poolSizeForTesting() { return this.pool.size(); }
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
        this.dialExecutor = newDaemonExecutor("smb-dial-");
        this.closeExecutor = newDaemonExecutor("smb-close-");
        this.probeExecutor = newDaemonExecutor("smb-probe-");
        // Outer dial budget — guards against TCP-connect + SMB session-setup hanging forever
        // when the NAS host is reachable by ping but its SMB service is wedged. This is kept
        // intentionally short (default 10 s) and distinct from the read/write/transact timeouts
        // (which govern ongoing SMB I/O and stay in minutes). The Future wrapper is the backstop
        // when smbj's own transactTimeout fails to surface during authentication.
        this.dialTimeoutMillis = TimeUnit.SECONDS.toMillis(
                config.smbOrDefaults().dialTimeoutSecondsOrDefault());
        // Close budget — a share/session/connection teardown is itself a chain of SMB round-trips
        // that can hang on a wedged socket; this bounds how long a caller (evict/shutdown) waits
        // before abandoning the teardown to the daemon close worker.
        this.closeTimeoutMillis = TimeUnit.SECONDS.toMillis(
                config.smbOrDefaults().closeTimeoutSecondsOrDefault());
        // Per-host dial breaker (Wave 2). Window/cooldown are seconds in config, millis here.
        // The clock is supplied as a live deref of nowMillis so tests that swap nowMillis after
        // construction (setNowMillisForTesting) still drive the breaker's timing.
        SmbSettings smb = config.smbOrDefaults();
        this.breaker = new HostDialBreaker(
                () -> nowMillis.getAsLong(),
                smb.dialBackoffThresholdOrDefault(),
                TimeUnit.SECONDS.toMillis(smb.dialBackoffWindowSecondsOrDefault()),
                TimeUnit.SECONDS.toMillis(smb.dialBackoffCooldownSecondsOrDefault()));
        // Wave 3 sweep/probe budgets and the network-change auto-teardown flag.
        this.poolSweepIntervalMillis = TimeUnit.SECONDS.toMillis(smb.poolSweepIntervalSecondsOrDefault());
        this.probeTimeoutMillis = TimeUnit.SECONDS.toMillis(smb.livenessProbeTimeoutSecondsOrDefault());
        this.networkChangeTeardownEnabled = smb.networkChangeTeardownEnabledOrDefault();
    }

    private static ExecutorService newDaemonExecutor(String namePrefix) {
        AtomicLong seq = new AtomicLong();
        return new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, namePrefix + seq.incrementAndGet());
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
            // Entry is already gone from the pool; the teardown is bounded and never blocks us.
            closeBounded(stale);
            log.info("Evicted SMB pool entry for volume {}", volumeId);
        }
    }

    /** Closes all pooled connections and the underlying SMBClient. */
    public void shutdown() {
        shutdown = true;
        // Stop the background sweep first so it can't re-populate or fire during teardown.
        ScheduledExecutorService s = sweepScheduler;
        if (s != null) s.shutdownNow();
        // Cancel any in-flight dials so callers waiting on them unblock.
        for (CompletableFuture<PooledShare> f : inFlightDials.values()) {
            f.cancel(true);
        }
        inFlightDials.clear();
        dialExecutor.shutdownNow();
        // Best-effort drain: bounded per entry, so a single wedged socket cannot stall shutdown.
        for (PooledShare p : pool.values()) closeBounded(p);
        pool.clear();
        closeExecutor.shutdownNow();
        probeExecutor.shutdownNow();
        try { client.close(); } catch (Exception e) { /* ignore */ }
    }

    /**
     * "Network has re-settled — start clean." Cancels every in-flight dial (so awaiting callers
     * unblock), bounded-closes and removes <em>every</em> pooled entry, and resets the per-host
     * breakers so a settled network is not stuck in a pre-switch backoff. The factory stays usable
     * (unlike {@link #shutdown()}, the {@code shutdown} flag is NOT set) — the next {@link #open}
     * re-dials lazily. Safe to call concurrently with normal ops (the pool is a
     * {@link ConcurrentHashMap}; per-entry removal uses the same {@code remove(k,v)} idiom as
     * {@link #acquire}). Never blocks the caller past the bounded close.
     *
     * <p><b>Not</b> a session-reclaim: in the sever-first case the sockets are already dead when we
     * react, so the NAS reclaims orphaned sessions on its own idle timeout regardless. The value here
     * is stopping the re-dial thrash and re-establishing cleanly.
     */
    public void invalidateAll() {
        // Cancel in-flight dials WITHOUT flipping the shutdown flag — the factory stays usable.
        for (CompletableFuture<PooledShare> f : inFlightDials.values()) {
            f.cancel(true);
        }
        inFlightDials.clear();
        // Remove + bounded-close every entry present in this pass. Weakly-consistent iteration plus
        // remove(k,v) leaves any entry a concurrent dial adds after we pass it untouched (it re-dialed
        // against a fresh, live connection, so it is not stale).
        int invalidated = 0;
        for (Map.Entry<String, PooledShare> e : pool.entrySet()) {
            if (pool.remove(e.getKey(), e.getValue())) {
                closeBounded(e.getValue());
                invalidated++;
            }
        }
        breaker.resetAll();
        log.info("invalidateAll: torn down {} pooled SMB connection(s) + reset dial breakers; next open re-dials", invalidated);
    }

    /**
     * Tears down a pooled connection on the {@link #closeExecutor}, waiting at most
     * {@code closeTimeoutMillis} for the (share → session → connection) close chain — each step is
     * an SMB round-trip that can hang on a wedged socket. On timeout the teardown is abandoned to
     * the daemon worker and we return; the caller is never blocked past the bound.
     */
    private void closeBounded(PooledShare pooled) {
        if (pooled == null) return;
        Future<?> future;
        try {
            future = closeExecutor.submit(() -> rawClose(pooled));
        } catch (RejectedExecutionException e) {
            // closeExecutor already shut down (e.g. evict racing shutdown) — abandon quietly.
            log.info("SMB close executor unavailable; abandoning connection teardown");
            return;
        }
        try {
            future.get(closeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("SMB connection teardown exceeded {} ms; abandoning close (socket may be wedged)",
                    closeTimeoutMillis);
        } catch (ExecutionException e) {
            // rawClose swallows its own exceptions; this is a defensive backstop only.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.info("SMB connection teardown failed: {}", cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
        }
    }

    /**
     * Performs the raw (unbounded) teardown of a pooled connection in the pool's documented order:
     * share → session → connection. Runs on the {@link #closeExecutor} via {@link #closeBounded}.
     *
     * <p>Visible for testing — override to simulate a slow or hanging close.
     */
    protected void rawClose(PooledShare pooled) {
        pooled.closeQuietly();
    }

    /**
     * Liveness probe for a pooled connection: a <em>bounded</em> SMB share-stat
     * ({@link DiskShare#getShareInformation()}, a real SMB2 round-trip) submitted to the
     * {@link #probeExecutor} with a {@link #probeTimeoutMillis} budget. Returns {@code true} iff it
     * completes without throwing or timing out — a hung probe never blocks the sweep thread. A
     * {@code null} share (test-only ctor) is treated as not-probeable ({@code false}).
     *
     * <p>Visible for testing — override to simulate probe outcomes without real smbj.
     */
    protected boolean probe(PooledShare pooled) {
        if (pooled == null || pooled.share == null) return false;
        Future<?> future;
        try {
            future = probeExecutor.submit(() -> { pooled.share.getShareInformation(); return null; });
        } catch (RejectedExecutionException e) {
            return false;   // probe worker shut down (racing shutdown) — treat as dead.
        }
        try {
            future.get(probeTimeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * One pool-sweep pass (Wave 3): probe every pooled connection, evict the dead ones, and — when
     * <strong>≥2 distinct pooled hosts</strong> had all their probes fail in a single pass (the Stage-0
     * multi-host network-change signature that a per-NAS fault cannot produce) — fire
     * {@link #invalidateAll()} once per event.
     *
     * <p>Probe failures feed <strong>only</strong> the sweep's eviction/sensor logic — never the
     * per-host dial breaker (which keys on dial outcomes; probes are a separate signal).
     *
     * <p>Sensor semantics (event-based debounce):
     * <ul>
     *   <li>Empty pool → do nothing (neither fire nor re-arm).</li>
     *   <li>≥2 distinct hosts all-fail + {@link #networkChangeTeardownEnabled} + armed → WARN +
     *       invalidateAll, then disarm (fire once).</li>
     *   <li>Any pass that saw ≥1 probe OK → re-arm (recovery), so a subsequent event can fire again.</li>
     *   <li>A <em>single</em> pooled host all-failing does NOT auto-teardown — the ≥2-host gate is the
     *       discriminator between a network/VPN event and a single-NAS blip. Its dead entries are still
     *       evicted; the breaker + manual {@code POST /api/smb/reset} / {@code forceResume()} cover it.</li>
     *   <li>A single-host all-fail while another host is OK likewise only evicts the bad host.</li>
     * </ul>
     * Dead-eviction always runs; the flag gates only the auto-teardown fire.
     *
     * <p>Package-visible for testing.
     */
    void sweepOnce() {
        Map<String, Boolean> hostAnyOk = new HashMap<>();
        boolean poolNonEmpty = false;
        for (Map.Entry<String, PooledShare> e : pool.entrySet()) {
            poolNonEmpty = true;
            String volumeId = e.getKey();
            String host;
            try {
                host = hostFor(volumeId);
            } catch (Exception ex) {
                // Unknown/malformed host — can't group it into the sensor, but still probe+evict below.
                host = null;
            }
            boolean ok = probe(e.getValue());
            if (!ok) {
                evict(volumeId);   // bounded close, removes entry; does NOT touch the breaker.
            }
            if (host != null) {
                hostAnyOk.merge(host, ok, (a, b) -> a || b);
            }
        }
        if (!poolNonEmpty) return;   // empty pool → neither fire nor re-arm.

        boolean anyOk = hostAnyOk.containsValue(Boolean.TRUE);
        if (anyOk) {
            sensorArmed = true;      // recovery signal → re-arm the sensor.
        }
        // Require ≥2 distinct pooled hosts all-failing: two independent NAS hosts down at once is the
        // network/VPN signature a per-NAS fault cannot produce. A single pooled host all-failing is a
        // per-NAS blip — already handled by the per-entry evict above + the breaker + the manual reset
        // — and must NOT auto-teardown (that would wipe the Wave-2 backoff via resetAll()).
        boolean multiHostDown = hostAnyOk.size() >= 2 && !anyOk;
        if (multiHostDown && networkChangeTeardownEnabled && sensorArmed) {
            log.warn("all SMB hosts failed liveness at once — suspected network change; tearing down all connections");
            sensorArmed = false;     // debounce: fire once per event (disarm BEFORE firing).
            invalidateAll();
        }
    }

    /**
     * Starts the background pool-sweep on a single daemon scheduler (thread {@code smb-sweep}),
     * running {@link #sweepOnce()} every {@code poolSweepIntervalSeconds}. Each run is guarded so one
     * bad pass can never kill the schedule. Idempotent — a second call is a no-op. Called from
     * {@code Application} right after construction (avoids a {@code this}-escape from the ctor).
     * {@link #shutdown()} stops the scheduler.
     */
    public void startPoolSweep() {
        if (sweepScheduler != null || shutdown) return;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smb-sweep");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(() -> {
            try {
                sweepOnce();
            } catch (Throwable t) {
                log.warn("SMB pool-sweep pass failed (schedule continues): {}", t.toString());
            }
        }, poolSweepIntervalMillis, poolSweepIntervalMillis, TimeUnit.MILLISECONDS);
        this.sweepScheduler = s;
        log.info("SMB pool-sweep started (interval {} ms, probeTimeout {} ms, auto-teardown {})",
                poolSweepIntervalMillis, probeTimeoutMillis, networkChangeTeardownEnabled);
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
                pool.remove(volumeId, existing);
                closeBounded(existing);
            }
            if (shutdown) throw new IOException("SmbConnectionFactory is shut down");
            // Resolve the host FIRST — a config/resolve failure is not a dial failure and must not
            // touch the breaker (it propagates unchanged). Then consult the per-host breaker: when
            // it is open, fast-fail here BEFORE submitting a dial that would just burn the timeout.
            String host = hostFor(volumeId);
            breaker.checkOpenOrThrow(host);
            // A caller that passed checkOpenOrThrow may hold the single half-open probe, so it MUST
            // report the outcome exactly once. Use finally (not a narrow catch) so an Error can't
            // leak the probe flag and wedge the host in HALF_OPEN forever.
            PooledShare fresh;
            boolean dialOk = false;
            try {
                fresh = dialWithTimeout(volumeId);
                dialOk = true;
            } finally {
                if (dialOk) breaker.recordSuccess(host);
                else breaker.recordFailure(host);
            }
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

    /**
     * Resolves the NAS host for a volume WITHOUT dialing, reusing the same {@code smbPath} parse
     * that {@link #dial} uses. Lets the per-host breaker key on host before any network work.
     * Throws if the volume is unknown or its {@code smbPath} is malformed (a config error — the
     * caller lets it propagate without recording a dial failure).
     */
    private String hostFor(String volumeId) throws IOException {
        VolumeConfig volume = config.findById(volumeId)
                .orElseThrow(() -> new IOException("Unknown volume: " + volumeId));
        return parseSmbPath(volume.smbPath()).host;
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

        /**
         * Raw teardown in order share → session → connection (matching
         * {@link SmbVolumeConnection}'s documented order).
         *
         * <p>The connection is closed <strong>gracefully</strong> ({@code close()} == smbj's
         * {@code close(false)}), never force-closed. {@code SMBClient.connect(host)} keys an internal
         * connection table by {@code host:port} and leases a <em>shared</em> {@link Connection} per
         * host (refcounted). With ~15 volumes spread across only a couple of NAS hosts, many
         * {@code PooledShare}s share one underlying connection — so graceful close is required:
         * {@code close(false)} decrements the lease and returns without disconnecting while siblings
         * still hold it, running the session-LOGOFF loop + {@code transport.disconnect()} only for
         * the last lease-holder. Force-close ({@code close(true)}) would skip that refcount check and
         * yank the shared transport out from under every sibling volume on the host. The graceful
         * LOGOFF loop can hang on a wedged socket, which is exactly why callers must run this under
         * {@link #closeBounded} — the executor + {@code closeTimeoutMillis} is the hang safety net.
         */
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
