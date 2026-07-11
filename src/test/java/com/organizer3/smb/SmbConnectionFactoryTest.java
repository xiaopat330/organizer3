package com.organizer3.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.organizer3.config.SmbSettings;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.smb.NasAvailabilityMonitor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmbConnectionFactoryTest {

    // ---------- Dial timeout derivation ----------

    /**
     * Asserts that the factory derives {@code dialTimeoutMillis} from
     * {@code dialTimeoutSeconds} (not from transactTimeoutMinutes).
     * Default dialTimeoutSeconds=10 → 10_000 ms.
     */
    @Test
    void dialTimeout_derivedFromDialTimeoutSeconds_default() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        SmbConnectionFactory factory = new SmbConnectionFactory(config, mock(SMBClient.class));
        assertEquals(10_000L, factory.dialTimeoutMillisForTesting(),
                "default dialTimeoutSeconds=10 should produce dialTimeoutMillis=10000");
        factory.shutdown();
    }

    @Test
    void dialTimeout_derivedFromDialTimeoutSeconds_customValue() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        // Custom: dialTimeoutSeconds=5; transactTimeoutMinutes=5 (300_000 ms) must NOT be used.
        when(config.smbOrDefaults()).thenReturn(new SmbSettings(null, null, 5, null, null, 5, null, null, null, null, null, null, null, null, null, null, null));
        SmbConnectionFactory factory = new SmbConnectionFactory(config, mock(SMBClient.class));
        assertEquals(5_000L, factory.dialTimeoutMillisForTesting(),
                "dialTimeoutSeconds=5 should produce dialTimeoutMillis=5000, not transact-minutes value");
        factory.shutdown();
    }

    // ---------- isVolumeAvailable passthrough ----------

    @Test
    void isVolumeAvailable_nullMonitor_returnsTrue() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        // Constructor that accepts null monitor (package-private 3-arg ctor)
        SmbConnectionFactory factory = new SmbConnectionFactory(config, mock(SMBClient.class), null);
        assertTrue(factory.isVolumeAvailable("any-volume"),
                "null monitor should fail-open (return true)");
        factory.shutdown();
    }

    @Test
    void isVolumeAvailable_monitorReportsDown_returnsFalse() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        NasAvailabilityMonitor monitor = mock(NasAvailabilityMonitor.class);
        when(monitor.isVolumeAvailable("down-vol")).thenReturn(false);
        when(monitor.isVolumeAvailable("up-vol")).thenReturn(true);
        SmbConnectionFactory factory = new SmbConnectionFactory(config, mock(SMBClient.class), monitor);
        assertFalse(factory.isVolumeAvailable("down-vol"), "monitor-down volume should return false");
        assertTrue(factory.isVolumeAvailable("up-vol"), "monitor-up volume should return true");
        factory.shutdown();
    }

    @Test
    void throwsForUnknownVolume() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        when(config.findById("nonexistent")).thenReturn(Optional.empty());

        SmbConnectionFactory factory = new SmbConnectionFactory(config);

        IOException ex = assertThrows(IOException.class, () -> factory.open("nonexistent"));
        assertTrue(ex.getMessage().contains("Unknown volume"));
    }

    @Test
    void throwsForUnknownServer() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        VolumeConfig volume = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        when(config.findById("a")).thenReturn(Optional.of(volume));
        when(config.findServerById("pandora")).thenReturn(Optional.empty());

        SmbConnectionFactory factory = new SmbConnectionFactory(config);

        IOException ex = assertThrows(IOException.class, () -> factory.open("a"));
        assertTrue(ex.getMessage().contains("Unknown server"));
    }

    @Test
    void throwsForInvalidSmbPath() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        VolumeConfig volume = new VolumeConfig("a", "not-a-smb-path", "conventional", "pandora", null);
        ServerConfig server = new ServerConfig("pandora", "user", "pass", null);
        when(config.findById("a")).thenReturn(Optional.of(volume));
        when(config.findServerById("pandora")).thenReturn(Optional.of(server));

        SmbConnectionFactory factory = new SmbConnectionFactory(config);

        // Should throw due to invalid smbPath format
        assertThrows(Exception.class, () -> factory.open("a"));
    }

    // ---------- Concurrency / hang-resistance tests ----------

    /**
     * Test harness that bypasses real smbj and lets each test wire up dial behavior per-volume.
     */
    private static class TestableFactory extends SmbConnectionFactory {
        private final java.util.Map<String, DialBehavior> behaviors = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger dialCount = new AtomicInteger();

        TestableFactory(OrganizerConfig config) {
            super(config, mock(SMBClient.class), null);
        }

        void onDial(String volumeId, DialBehavior b) {
            behaviors.put(volumeId, b);
        }

        int dialCount() { return dialCount.get(); }

        @Override
        protected PooledShare dial(String volumeId) throws IOException {
            dialCount.incrementAndGet();
            DialBehavior b = behaviors.get(volumeId);
            if (b == null) throw new IOException("no behavior for " + volumeId);
            return b.run(volumeId);
        }
    }

    @FunctionalInterface
    private interface DialBehavior {
        SmbConnectionFactory.PooledShare run(String volumeId) throws IOException;
    }

    private static OrganizerConfig configWithShortTimeouts() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        // transactTimeoutMinutes drives dial timeout; we want a small budget for the timeout test
        // but still big enough for non-hanging tests to finish. SmbSettings is minutes-only, so
        // the timeout-test uses its own override path (we'll bound via thread-interrupt instead).
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        // The Wave-2 breaker resolves the host via findById(volumeId) before dialing; the dial()
        // override bypasses real smbj, so resolve every id to a synthetic single host.
        when(config.findById(anyString())).thenAnswer(inv -> Optional.of(
                new VolumeConfig(inv.getArgument(0), "//testhost/share", "conventional", "testhost", null)));
        return config;
    }

    @Test
    void concurrentAcquiresForSameVolumeCoalesceIntoOneDial() throws Exception {
        TestableFactory factory = new TestableFactory(configWithShortTimeouts());
        CountDownLatch dialStarted = new CountDownLatch(1);
        CountDownLatch releaseDial = new CountDownLatch(1);
        factory.onDial("vol-a", v -> {
            dialStarted.countDown();
            try { releaseDial.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new SmbConnectionFactory.PooledShare("");
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int n = 8;
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            Future<?>[] futures = new Future<?>[n];
            for (int i = 0; i < n; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    factory.open("vol-a");
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            // Wait for one dial to be in-flight
            assertTrue(dialStarted.await(5, TimeUnit.SECONDS));
            releaseDial.countDown();
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
            assertEquals(1, factory.dialCount(), "all concurrent acquires for same volume should coalesce into one dial");
        } finally {
            pool.shutdownNow();
            factory.shutdown();
        }
    }

    @Test
    void slowDialOnVolumeADoesNotBlockVolumeB() throws Exception {
        TestableFactory factory = new TestableFactory(configWithShortTimeouts());
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        factory.onDial("a", v -> {
            aStarted.countDown();
            try { releaseA.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new SmbConnectionFactory.PooledShare("");
        });
        factory.onDial("b", v -> new SmbConnectionFactory.PooledShare(""));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> slowA = pool.submit(() -> factory.open("a"));
            assertTrue(aStarted.await(5, TimeUnit.SECONDS), "dial for A should have started");
            // While A is parked, B must complete promptly
            long t0 = System.nanoTime();
            Future<?> fastB = pool.submit(() -> factory.open("b"));
            fastB.get(3, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 2000,
                    "open(B) should not be blocked by parked dial(A); took " + elapsedMs + "ms");
            releaseA.countDown();
            slowA.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            factory.shutdown();
        }
    }

    @Test
    void evictDoesNotTakeFactoryMonitor() throws Exception {
        TestableFactory factory = new TestableFactory(configWithShortTimeouts());
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        factory.onDial("a", v -> {
            aStarted.countDown();
            try { releaseA.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new SmbConnectionFactory.PooledShare("");
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> slowA = pool.submit(() -> factory.open("a"));
            assertTrue(aStarted.await(5, TimeUnit.SECONDS));
            // evict on a different volume must not block on the in-flight dial of A
            long t0 = System.nanoTime();
            factory.evict("b");
            // Post-fix invariant: evict completes promptly even while a dial is parked,
            // because evict() no longer acquires any factory-wide synchronization.
            factory.evict("a");
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 500, "evict should not block on in-flight dial; took " + elapsedMs + "ms");
            releaseA.countDown();
            slowA.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            factory.shutdown();
        }
    }

    @Test
    void dialTimesOutWhenAuthenticateHangs() throws Exception {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        when(config.findById(anyString())).thenAnswer(inv -> Optional.of(
                new VolumeConfig(inv.getArgument(0), "//testhost/share", "conventional", "testhost", null)));
        CountDownLatch neverReleased = new CountDownLatch(1);
        TestableFactory factory = new TestableFactory(config) {
            @Override
            protected PooledShare dial(String volumeId) throws IOException {
                try {
                    // Park forever (simulates Promise.retrieve() with no timeout)
                    neverReleased.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                return new SmbConnectionFactory.PooledShare("");
            }
        };
        factory.setDialTimeoutMillisForTesting(500L);

        try {
            long t0 = System.nanoTime();
            IOException ex = assertThrows(IOException.class, () -> factory.open("a"));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ex.getMessage().toLowerCase().contains("timed out") || ex.getCause() instanceof java.util.concurrent.TimeoutException,
                    "expected timeout IOException, got: " + ex.getMessage());
            assertTrue(elapsedMs < 5000, "dial should have timed out near the configured budget; took " + elapsedMs + "ms");
        } finally {
            neverReleased.countDown();
            factory.shutdown();
        }
    }

    @Test
    void withForceRetryEvictsAndRetriesOnce() throws Exception {
        OrganizerConfig config = configWithShortTimeouts();
        // Configure a real volume so dial() can be exercised (we override anyway)
        VolumeConfig volume = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        ServerConfig server = new ServerConfig("pandora", "user", "pass", null);
        when(config.findById("a")).thenReturn(Optional.of(volume));
        when(config.findServerById("pandora")).thenReturn(Optional.of(server));

        AtomicInteger dialCalls = new AtomicInteger();
        TestableFactory factory = new TestableFactory(config) {
            @Override
            protected PooledShare dial(String volumeId) {
                dialCalls.incrementAndGet();
                return new SmbConnectionFactory.PooledShare("");
            }
        };

        AtomicInteger opCalls = new AtomicInteger();
        try {
            String result = factory.withForceRetry("a", handle -> {
                if (opCalls.incrementAndGet() == 1) {
                    throw new IOException("simulated transient failure");
                }
                return "ok";
            });
            assertEquals("ok", result);
            assertEquals(2, opCalls.get(), "op must be retried exactly once");
            assertEquals(2, dialCalls.get(), "evict + re-acquire should cause a second dial");
        } finally {
            factory.shutdown();
        }
    }

    // ---------- Bounded-close (Wave 1) ----------

    /**
     * A hanging connection teardown must not block the caller: {@code evict()} removes the pool
     * entry synchronously and returns within {@code closeTimeoutMillis}, abandoning the close to
     * the daemon worker. The evicted entry is gone, so the next {@code open} re-dials.
     */
    @Test
    void evictWithHangingCloseReturnsWithinBoundAndRemovesEntry() throws Exception {
        OrganizerConfig config = configWithShortTimeouts();
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        TestableFactory factory = new TestableFactory(config) {
            @Override
            protected void rawClose(SmbConnectionFactory.PooledShare pooled) {
                closeEntered.countDown();
                try { releaseClose.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        factory.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        factory.setCloseTimeoutMillisForTesting(300L);
        try {
            factory.open("a");                       // pool one entry
            int dialsBefore = factory.dialCount();
            long t0 = System.nanoTime();
            factory.evict("a");                      // hanging close — must not block past the bound
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS), "close should have been attempted");
            assertTrue(elapsedMs < 3000, "evict must return within the close bound; took " + elapsedMs + "ms");
            factory.open("a");                       // entry gone → must re-dial
            assertEquals(dialsBefore + 1, factory.dialCount(), "evicted entry should be gone → re-dial");
        } finally {
            releaseClose.countDown();
            factory.shutdown();
        }
    }

    /** A normal (fast) close runs to completion and the entry is removed (next open re-dials). */
    @Test
    void evictWithFastCloseCompletesAndRemovesEntry() throws Exception {
        OrganizerConfig config = configWithShortTimeouts();
        CountDownLatch closed = new CountDownLatch(1);
        TestableFactory factory = new TestableFactory(config) {
            @Override
            protected void rawClose(SmbConnectionFactory.PooledShare pooled) {
                closed.countDown();
            }
        };
        factory.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        try {
            factory.open("a");
            int dialsBefore = factory.dialCount();
            factory.evict("a");
            assertTrue(closed.await(2, TimeUnit.SECONDS), "fast close should run to completion");
            factory.open("a");
            assertEquals(dialsBefore + 1, factory.dialCount(), "entry should be removed → re-dial");
        } finally {
            factory.shutdown();
        }
    }

    /** {@code shutdown()} drains best-effort and stays bounded even when every close hangs. */
    @Test
    void shutdownStaysBoundedWhenCloseHangs() throws Exception {
        OrganizerConfig config = configWithShortTimeouts();
        CountDownLatch releaseClose = new CountDownLatch(1);
        TestableFactory factory = new TestableFactory(config) {
            @Override
            protected void rawClose(SmbConnectionFactory.PooledShare pooled) {
                try { releaseClose.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        factory.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        factory.onDial("b", v -> new SmbConnectionFactory.PooledShare(""));
        factory.setCloseTimeoutMillisForTesting(300L);
        try {
            factory.open("a");
            factory.open("b");
            long t0 = System.nanoTime();
            factory.shutdown();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 3000, "shutdown must stay bounded even when closes hang; took " + elapsedMs + "ms");
        } finally {
            releaseClose.countDown();
        }
    }

    // ---------- Per-host dial breaker (Wave 2) ----------

    /** Config with volume "a" -> host "pandora" so hostFor() resolves without dialing. */
    private static OrganizerConfig configWithVolumeA() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        VolumeConfig volume = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        when(config.findById("a")).thenReturn(Optional.of(volume));
        return config;
    }

    /**
     * PROOF OF VALUE: after {@code threshold} dial failures the breaker opens and the next
     * {@code open()} FAST-FAILS WITHOUT calling {@code dial()} — the dial counter does not advance.
     * This is the entire point of Wave 2 (kills the N-items x dial-timeout reconciler thrash).
     */
    @Test
    void afterThresholdFailures_nextAcquireFastFailsWithoutDialing() throws Exception {
        TestableFactory factory = new TestableFactory(configWithVolumeA());
        AtomicLong clock = new AtomicLong(10_000L);
        factory.setNowMillisForTesting(clock::get);
        factory.setDialBackoffForTesting(3, 60_000L, 30_000L);
        factory.onDial("a", v -> { throw new IOException("simulated dead host"); });
        try {
            // 3 real dials, each failing → 3rd opens the breaker.
            for (int i = 0; i < 3; i++) {
                assertThrows(IOException.class, () -> factory.open("a"));
            }
            assertEquals(3, factory.dialCount(), "threshold dials should have actually run");
            assertTrue(factory.dialBreakerForTesting().isOpen("pandora"), "breaker must be open");

            // 4th acquire: breaker is open → fast-fail with the backoff message, NO new dial.
            IOException ex = assertThrows(IOException.class, () -> factory.open("a"));
            assertTrue(ex.getMessage().contains("dial-backoff"),
                    "fast-fail must surface the backoff message, got: " + ex.getMessage());
            assertEquals(3, factory.dialCount(),
                    "open() while breaker is OPEN must NOT invoke dial() — dial count stays at threshold");
        } finally {
            factory.shutdown();
        }
    }

    /**
     * After the cooldown elapses, exactly one half-open probe is allowed: the next {@code open()}
     * DOES invoke {@code dial()} once. A succeeding probe closes the breaker (recovery).
     */
    @Test
    void afterCooldown_halfOpenProbeInvokesDialOnce_successCloses() throws Exception {
        TestableFactory factory = new TestableFactory(configWithVolumeA());
        AtomicLong clock = new AtomicLong(10_000L);
        factory.setNowMillisForTesting(clock::get);
        factory.setDialBackoffForTesting(3, 60_000L, 30_000L);
        AtomicInteger failuresLeft = new AtomicInteger(3);
        factory.onDial("a", v -> {
            if (failuresLeft.getAndDecrement() > 0) throw new IOException("simulated dead host");
            return new SmbConnectionFactory.PooledShare("");   // probe succeeds
        });
        try {
            for (int i = 0; i < 3; i++) assertThrows(IOException.class, () -> factory.open("a"));
            assertEquals(3, factory.dialCount());
            assertTrue(factory.dialBreakerForTesting().isOpen("pandora"));

            // Still within cooldown → fast-fail, no dial.
            assertThrows(IOException.class, () -> factory.open("a"));
            assertEquals(3, factory.dialCount(), "within cooldown must not dial");

            // Advance past the 30s cooldown → one half-open probe dial is allowed and succeeds.
            clock.addAndGet(30_000L);
            factory.open("a");
            assertEquals(4, factory.dialCount(), "half-open probe must invoke dial() exactly once");
            assertFalse(factory.dialBreakerForTesting().isOpen("pandora"),
                    "successful probe must close the breaker");
        } finally {
            factory.shutdown();
        }
    }

    /** A config/resolve failure (unknown volume) must propagate WITHOUT touching the breaker. */
    @Test
    void unknownVolume_doesNotRecordDialFailure() throws Exception {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        when(config.findById("ghost")).thenReturn(Optional.empty());
        TestableFactory factory = new TestableFactory(config);
        try {
            IOException ex = assertThrows(IOException.class, () -> factory.open("ghost"));
            assertTrue(ex.getMessage().contains("Unknown volume"));
            assertEquals(0, factory.dialCount(), "config error must not invoke dial()");
        } finally {
            factory.shutdown();
        }
    }

    /** The injectable wall-clock seam (foundational for later waves) is read through the supplier. */
    @Test
    void nowMillisSourceIsInjectable() {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        SmbConnectionFactory factory = new SmbConnectionFactory(config, mock(SMBClient.class));
        try {
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1000L);
            factory.setNowMillisForTesting(clock::get);
            assertEquals(1000L, factory.nowMillisForTesting());
            clock.set(5000L);
            assertEquals(5000L, factory.nowMillisForTesting(),
                    "factory should read wall-clock through the injected source");
        } finally {
            factory.shutdown();
        }
    }

    // ---------- Wave 3: invalidateAll / pool-sweep / all-hosts-down sensor ----------

    /**
     * Harness for the sweep/sensor tests. Bypasses real smbj: {@code dial()} pools a stub
     * {@code PooledShare} whose {@code subPath} carries the volumeId (so {@code probe()} can key its
     * simulated outcome per volume), and {@code invalidateAll()} is counted (delegating to the real
     * teardown only when {@code realInvalidate} is set).
     */
    private static class SweepTestableFactory extends SmbConnectionFactory {
        final Map<String, Boolean> probeResults = new ConcurrentHashMap<>();
        final AtomicInteger invalidateAllCount = new AtomicInteger();
        final AtomicInteger dialCount = new AtomicInteger();
        volatile boolean realInvalidate = false;

        SweepTestableFactory(OrganizerConfig config) {
            super(config, mock(SMBClient.class), null);
        }

        @Override
        protected PooledShare dial(String volumeId) {
            dialCount.incrementAndGet();
            return new SmbConnectionFactory.PooledShare(volumeId);   // volumeId encoded in subPath
        }

        @Override
        protected boolean probe(PooledShare pooled) {
            return probeResults.getOrDefault(pooled.subPath, Boolean.TRUE);
        }

        @Override
        public void invalidateAll() {
            invalidateAllCount.incrementAndGet();
            if (realInvalidate) super.invalidateAll();
        }
    }

    /** Config mapping volumeId → host via a synthetic {@code //host/share} smbPath. */
    private static OrganizerConfig configWithHosts(Map<String, String> volToHost) {
        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.smbOrDefaults()).thenReturn(SmbSettings.DEFAULTS);
        when(config.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            String host = volToHost.get(id);
            return host == null ? Optional.empty()
                    : Optional.of(new VolumeConfig(id, "//" + host + "/share", "conventional", host, null));
        });
        return config;
    }

    @Test
    void invalidateAll_closesAllEntries_resetsBreaker_nextOpenRedials() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithShortTimeouts());
        f.realInvalidate = true;
        AtomicLong clock = new AtomicLong(10_000L);
        f.setNowMillisForTesting(clock::get);
        try {
            f.open("a");
            f.open("b");
            assertEquals(2, f.poolSizeForTesting());
            // Open the shared host's breaker (threshold=3 default) so we can assert the reset.
            HostDialBreaker br = f.dialBreakerForTesting();
            br.recordFailure("testhost");
            br.recordFailure("testhost");
            br.recordFailure("testhost");
            assertTrue(br.isOpen("testhost"), "breaker must be open before invalidateAll");
            int dialsBefore = f.dialCount.get();

            f.invalidateAll();

            assertEquals(0, f.poolSizeForTesting(), "every pooled entry removed");
            assertFalse(f.dialBreakerForTesting().isOpen("testhost"),
                    "invalidateAll must reset the breakers");
            f.open("a");
            assertEquals(dialsBefore + 1, f.dialCount.get(), "next open re-dials lazily");
        } finally {
            f.shutdown();
        }
    }

    @Test
    void invalidateAll_cancelsInFlightDial_awaiterUnblocks_returnsPromptly() throws Exception {
        TestableFactory f = new TestableFactory(configWithShortTimeouts());
        CountDownLatch dialStarted = new CountDownLatch(1);
        CountDownLatch releaseDial = new CountDownLatch(1);
        f.onDial("a", v -> {
            dialStarted.countDown();
            try { releaseDial.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new SmbConnectionFactory.PooledShare("");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> owner = pool.submit(() -> f.open("a"));
            assertTrue(dialStarted.await(5, TimeUnit.SECONDS), "owner dial should be parked");
            // Second caller coalesces onto the owner's in-flight future.
            Future<?> awaiter = pool.submit(() -> f.open("a"));
            Thread.sleep(200);   // let the awaiter register on the in-flight dial

            long t0 = System.nanoTime();
            f.invalidateAll();   // cancels the in-flight future — must not block on the parked dial
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 1000, "invalidateAll must not block on a parked dial; took " + elapsedMs + "ms");

            ExecutionException ee = assertThrows(ExecutionException.class, () -> awaiter.get(5, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof IOException, "awaiter should surface a cancellation IOException");

            releaseDial.countDown();
            owner.get(5, TimeUnit.SECONDS);   // owner completes (re-populates) — mirrors shutdown() semantics
        } finally {
            pool.shutdownNow();
            f.shutdown();
        }
    }

    @Test
    void invalidateAll_boundedWhenCloseHangs() throws Exception {
        OrganizerConfig config = configWithShortTimeouts();
        CountDownLatch releaseClose = new CountDownLatch(1);
        TestableFactory f = new TestableFactory(config) {
            @Override
            protected void rawClose(SmbConnectionFactory.PooledShare pooled) {
                try { releaseClose.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        f.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        f.onDial("b", v -> new SmbConnectionFactory.PooledShare(""));
        f.setCloseTimeoutMillisForTesting(300L);
        try {
            f.open("a");
            f.open("b");
            long t0 = System.nanoTime();
            f.invalidateAll();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 3000, "invalidateAll must stay bounded when closes hang; took " + elapsedMs + "ms");
            assertEquals(0, f.poolSizeForTesting(), "entries removed even though close hung");
        } finally {
            releaseClose.countDown();
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_evictsDeadEntry_retainsLiveEntry() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h1")));
        try {
            f.open("a");
            f.open("b");
            assertEquals(2, f.poolSizeForTesting());
            f.probeResults.put("a", false);   // dead
            f.probeResults.put("b", true);     // live

            f.sweepOnce();

            assertEquals(1, f.poolSizeForTesting(), "dead entry evicted, live one retained");
            assertEquals(0, f.invalidateAllCount.get(), "a live host remained → no teardown");
        } finally {
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_singleHostAllFail_otherHostOk_evictsOnly_noTeardown() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h2")));
        try {
            f.open("a");
            f.open("b");
            f.probeResults.put("a", false);   // host h1 down
            f.probeResults.put("b", true);    // host h2 up

            f.sweepOnce();

            assertEquals(1, f.poolSizeForTesting(), "only the dead host's entry evicted");
            assertEquals(0, f.invalidateAllCount.get(), "another host OK → invalidateAll NOT fired");
        } finally {
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_allHostsAllFail_firesOnce_thenDebouncesWhileDisarmed() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h2")));
        try {
            f.open("a");
            f.open("b");
            f.probeResults.put("a", false);
            f.probeResults.put("b", false);

            f.sweepOnce();
            assertEquals(1, f.invalidateAllCount.get(), "all hosts down at once → fire teardown");
            assertFalse(f.sensorArmedForTesting(), "sensor disarmed after firing");

            // Re-populate with still-failing entries; disarmed sensor must NOT re-fire (debounce).
            f.open("a");
            f.open("b");
            f.sweepOnce();
            assertEquals(1, f.invalidateAllCount.get(), "debounced: no re-fire while disarmed");
            assertFalse(f.sensorArmedForTesting());
        } finally {
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_singleHostOnlyAllFail_evictsButDoesNotTeardown() throws Exception {
        // Pool holds only ONE host. A single host all-failing is a per-NAS blip, NOT the multi-host
        // network signature — the sensor must require ≥2 distinct pooled hosts. Only per-entry evict +
        // the breaker handle single-host severance; the auto-teardown must NOT fire (it would wipe the
        // Wave-2 backoff via resetAll()).
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h1")));
        try {
            f.open("a");
            f.open("b");
            f.probeResults.put("a", false);
            f.probeResults.put("b", false);

            f.sweepOnce();

            assertEquals(0, f.invalidateAllCount.get(),
                    "single pooled host all-failing must NOT auto-teardown (needs ≥2 distinct hosts)");
            assertEquals(0, f.poolSizeForTesting(), "dead entries still evicted");
            assertTrue(f.sensorArmedForTesting(), "sensor stays armed — it never fired");
        } finally {
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_recoveryReArmsSensor_canFireAgain() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h2")));
        try {
            // Event 1: all down → fire + disarm.
            f.open("a");
            f.open("b");
            f.probeResults.put("a", false);
            f.probeResults.put("b", false);
            f.sweepOnce();
            assertEquals(1, f.invalidateAllCount.get());
            assertFalse(f.sensorArmedForTesting());

            // Recovery pass: healthy probes → re-arm, entries retained.
            f.probeResults.put("a", true);
            f.probeResults.put("b", true);
            f.open("a");
            f.open("b");
            f.sweepOnce();
            assertTrue(f.sensorArmedForTesting(), "a healthy probe re-arms the sensor");
            assertEquals(2, f.poolSizeForTesting(), "healthy entries retained");

            // Event 2: all down again → fires again.
            f.probeResults.put("a", false);
            f.probeResults.put("b", false);
            f.sweepOnce();
            assertEquals(2, f.invalidateAllCount.get(), "re-armed sensor fires on the next event");
        } finally {
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_teardownDisabled_suppressesFire_stillEvictsDead() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h2")));
        f.setNetworkChangeTeardownEnabledForTesting(false);
        try {
            f.open("a");
            f.open("b");
            f.probeResults.put("a", false);
            f.probeResults.put("b", false);

            f.sweepOnce();

            assertEquals(0, f.invalidateAllCount.get(), "flag off → auto-teardown suppressed");
            assertEquals(0, f.poolSizeForTesting(), "dead entries still evicted");
        } finally {
            f.shutdown();
        }
    }

    @Test
    void sweepOnce_emptyPool_doesNothing_doesNotReArm() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        f.setSensorArmedForTesting(false);
        try {
            f.sweepOnce();   // empty pool
            assertEquals(0, f.invalidateAllCount.get(), "empty pool never fires");
            assertFalse(f.sensorArmedForTesting(), "empty pool must not re-arm the sensor");
        } finally {
            f.shutdown();
        }
    }

    // ---------- Wave 4: idle/age recycling + inUse bracket ----------

    /** Idle beyond maxIdle with inUse==0 → recycled (evicted) on the next sweep. */
    @Test
    void sweepOnce_idleBeyondMaxIdle_recyclesConnection() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(100_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(10_000L);
        f.setMaxAgeMillisForTesting(Long.MAX_VALUE);   // isolate the idle branch
        try {
            f.open("a").close();                       // pool the entry, release the lease (idle, inUse==0)
            assertEquals(1, f.poolSizeForTesting());
            clock.addAndGet(20_000L);                  // idle 20s >= maxIdle 10s
            f.sweepOnce();
            assertEquals(0, f.poolSizeForTesting(), "idle connection must be recycled");
            assertEquals(0, f.invalidateAllCount.get(), "a recycle is not a probe failure → no teardown");
        } finally {
            f.shutdown();
        }
    }

    /** Idle below maxIdle → retained (still healthy on probe). */
    @Test
    void sweepOnce_idleBelowMaxIdle_retainsConnection() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(100_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(60_000L);
        f.setMaxAgeMillisForTesting(Long.MAX_VALUE);
        try {
            f.open("a").close();                       // pooled, lease released (idle, inUse==0)
            clock.addAndGet(20_000L);                  // idle 20s < maxIdle 60s
            f.sweepOnce();
            assertEquals(1, f.poolSizeForTesting(), "recently-used connection must be retained");
        } finally {
            f.shutdown();
        }
    }

    /** inUse>0 (actively borrowed) + idle>maxIdle → NEVER recycled — the load-bearing invariant. */
    @Test
    void sweepOnce_inUseConnection_notRecycledEvenWhenIdle() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(100_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(10_000L);
        f.setMaxAgeMillisForTesting(Long.MAX_VALUE);
        try {
            f.open("a").close();                               // pooled, lease released
            f.pooledForTesting("a").inUse.incrementAndGet();   // now simulate an in-flight borrow
            clock.addAndGet(20_000L);
            f.sweepOnce();
            assertEquals(1, f.poolSizeForTesting(), "an in-use connection must never be recycled");
        } finally {
            f.shutdown();
        }
    }

    /** Aged beyond maxAge + idle >= ageIdleGrace + inUse==0 → recycled at the natural idle gap. */
    @Test
    void sweepOnce_agedBeyondMaxAge_pastGrace_recycles() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(1_000_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(Long.MAX_VALUE);   // isolate the age branch
        f.setMaxAgeMillisForTesting(30_000L);
        f.setAgeIdleGraceMillisForTesting(5_000L);
        try {
            f.open("a").close();                        // pooled, lease released (inUse==0 baseline)
            long now = clock.get();
            var ps = f.pooledForTesting("a");
            ps.createdAtMillis = now - 40_000L;         // age 40s >= maxAge 30s
            ps.lastUsedAtMillis = now - 6_000L;         // idle 6s >= grace 5s
            f.sweepOnce();
            assertEquals(0, f.poolSizeForTesting(), "aged connection past its idle grace must be recycled");
        } finally {
            f.shutdown();
        }
    }

    /** Aged beyond maxAge but idle < ageIdleGrace → NOT recycled (protects a just-used aged connection). */
    @Test
    void sweepOnce_agedBeyondMaxAge_withinGrace_notRecycled() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(1_000_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(Long.MAX_VALUE);
        f.setMaxAgeMillisForTesting(30_000L);
        f.setAgeIdleGraceMillisForTesting(5_000L);
        try {
            f.open("a").close();                        // pooled, lease released (inUse==0 baseline)
            long now = clock.get();
            var ps = f.pooledForTesting("a");
            ps.createdAtMillis = now - 40_000L;         // aged
            ps.lastUsedAtMillis = now - 1_000L;         // idle 1s < grace 5s → mid-transfer protection
            f.sweepOnce();
            assertEquals(1, f.poolSizeForTesting(),
                    "an aged but just-used connection must not be recycled mid-transfer");
        } finally {
            f.shutdown();
        }
    }

    /** Aged beyond maxAge but inUse>0 → NOT recycled. */
    @Test
    void sweepOnce_agedBeyondMaxAge_inUse_notRecycled() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(1_000_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(Long.MAX_VALUE);
        f.setMaxAgeMillisForTesting(30_000L);
        f.setAgeIdleGraceMillisForTesting(5_000L);
        try {
            f.open("a").close();                        // pooled, lease released (inUse==0 baseline)
            long now = clock.get();
            var ps = f.pooledForTesting("a");
            ps.createdAtMillis = now - 40_000L;
            ps.lastUsedAtMillis = now - 6_000L;         // past grace, but…
            ps.inUse.incrementAndGet();                 // …actively borrowed
            f.sweepOnce();
            assertEquals(1, f.poolSizeForTesting(), "an in-use aged connection must never be recycled");
        } finally {
            f.shutdown();
        }
    }

    /**
     * REGRESSION GUARD: a recycled (healthy) connection must NOT look like a probe failure to the
     * ≥2-host sensor. Two hosts, both idle-recycled in one pass → invalidateAll NOT fired.
     */
    @Test
    void sweepOnce_recycledEntries_doNotFeedSensor_noTeardown() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1", "b", "h2")));
        AtomicLong clock = new AtomicLong(100_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(10_000L);
        f.setMaxAgeMillisForTesting(Long.MAX_VALUE);
        try {
            f.open("a").close();                       // pooled, leases released
            f.open("b").close();
            clock.addAndGet(20_000L);                  // both idle beyond maxIdle
            f.sweepOnce();
            assertEquals(0, f.poolSizeForTesting(), "both entries recycled");
            assertEquals(0, f.invalidateAllCount.get(),
                    "recycled (not probed-dead) entries must never trip the all-hosts-down sensor");
            assertTrue(f.sensorArmedForTesting(), "sensor never fired → stays armed");
        } finally {
            f.shutdown();
        }
    }

    /** A pooled-hit open() re-stamps lastUsedAtMillis (keeps an actively-used connection fresh). */
    @Test
    void open_pooledHit_refreshesLastUsedAtMillis() throws Exception {
        OrganizerConfig config = configWithHosts(Map.of("a", "h1"));
        Connection healthy = mock(Connection.class);
        when(healthy.isConnected()).thenReturn(true);
        SmbConnectionFactory f = new SmbConnectionFactory(config, mock(SMBClient.class), null) {
            @Override
            protected PooledShare dial(String volumeId) {
                return new PooledShare(healthy, null, null, volumeId);
            }
        };
        AtomicLong clock = new AtomicLong(100_000L);
        f.setNowMillisForTesting(clock::get);
        try {
            f.open("a");
            var ps1 = f.pooledForTesting("a");
            assertEquals(100_000L, ps1.lastUsedAtMillis);
            clock.addAndGet(5_000L);
            f.open("a");                                // healthy → hit path re-stamps, no re-dial
            var ps2 = f.pooledForTesting("a");
            assertSame(ps1, ps2, "pooled-hit must reuse the same share (no re-dial)");
            assertEquals(105_000L, ps2.lastUsedAtMillis, "pooled-hit open must refresh lastUsedAtMillis");
        } finally {
            f.shutdown();
        }
    }

    /** withRetry brackets inUse: 1 mid-op, back to 0 after a successful op. */
    @Test
    void withRetry_bracketsInUse_balancedAfterSuccess() throws Exception {
        TestableFactory f = new TestableFactory(configWithVolumeA());
        f.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        try {
            String r = f.withRetry("a", handle -> {
                assertEquals(1, f.pooledForTesting("a").inUse.get(), "inUse must be 1 while the op runs");
                return "ok";
            });
            assertEquals("ok", r);
            assertEquals(0, f.pooledForTesting("a").inUse.get(), "inUse must return to 0 after success");
        } finally {
            f.shutdown();
        }
    }

    /**
     * withRetry brackets inUse across the broken-pipe retry: the first share is balanced to 0 before
     * eviction, the fresh share is bracketed independently, and inUse ends at 0 on the surviving share.
     */
    @Test
    void withRetry_bracketsInUse_balancedAfterBrokenPipeRetry() throws Exception {
        TestableFactory f = new TestableFactory(configWithVolumeA());
        f.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        AtomicInteger opCalls = new AtomicInteger();
        try {
            String r = f.withRetry("a", handle -> {
                int n = opCalls.incrementAndGet();
                assertEquals(1, f.pooledForTesting("a").inUse.get(), "borrowed share is in-use during the op");
                if (n == 1) throw new IOException("drop", new java.net.SocketException("Broken pipe"));
                return "ok";
            });
            assertEquals("ok", r);
            assertEquals(2, opCalls.get(), "op must be retried exactly once on broken pipe");
            assertEquals(0, f.pooledForTesting("a").inUse.get(),
                    "inUse must be balanced to 0 on the fresh share after the retry");
        } finally {
            f.shutdown();
        }
    }

    /** withForceRetry must not leak inUse even when the op throws on BOTH attempts. */
    @Test
    void withForceRetry_bracketsInUse_balancedAfterThrow() throws Exception {
        TestableFactory f = new TestableFactory(configWithVolumeA());
        f.onDial("a", v -> new SmbConnectionFactory.PooledShare(""));
        try {
            assertThrows(IOException.class, () -> f.withForceRetry("a", handle -> {
                assertEquals(1, f.pooledForTesting("a").inUse.get());
                throw new IOException("always fails");
            }));
            assertEquals(0, f.pooledForTesting("a").inUse.get(),
                    "inUse must be balanced to 0 even when the op always throws (finally on every path)");
        } finally {
            f.shutdown();
        }
    }

    // ---------- Wave 4 gate correction: handle-lease protects a mid-transfer stream ----------

    /**
     * THE STREAMING-REGRESSION GUARD. An AGED connection (the steady state on a long-running server)
     * whose recycle threshold has collapsed to the 30s ageIdleGrace must still NOT be recycled while a
     * stream handle holds it (inUse>0) — even though lastUsedAtMillis is frozen far in the past (the
     * transfer loop never re-borrows). Once the handle is released (inUse==0), the next sweep recycles
     * it. Before the fix, the aged+idle connection was recycled mid-read → the stream broke with a 502.
     */
    @Test
    void sweepOnce_agedButInUse_notRecycled_untilReleased() throws Exception {
        SweepTestableFactory f = new SweepTestableFactory(configWithHosts(Map.of("a", "h1")));
        AtomicLong clock = new AtomicLong(1_000_000L);
        f.setNowMillisForTesting(clock::get);
        f.setMaxIdleMillisForTesting(Long.MAX_VALUE);   // isolate the age branch
        f.setMaxAgeMillisForTesting(30_000L);
        f.setAgeIdleGraceMillisForTesting(5_000L);
        try {
            f.open("a").close();                        // pool the entry
            var ps = f.pooledForTesting("a");
            long now = clock.get();
            ps.createdAtMillis = now - 40_000L;         // aged past maxAge
            ps.lastUsedAtMillis = now - 60_000L;        // idle far beyond grace (frozen mid-transfer)
            ps.inUse.incrementAndGet();                 // a stream handle is holding it

            f.sweepOnce();
            assertEquals(1, f.poolSizeForTesting(),
                    "an aged+idle connection held by an open stream handle must NOT be recycled");

            ps.inUse.decrementAndGet();                 // stream finished → handle closed
            f.sweepOnce();
            assertEquals(0, f.poolSizeForTesting(),
                    "once released, the aged+idle connection is recycled on the next sweep");
        } finally {
            f.shutdown();
        }
    }

    /** open() takes an in-use lease; handle.close() releases it exactly once (idempotent double-close). */
    @Test
    void open_takesLease_close_releasesOnce_idempotent() throws Exception {
        OrganizerConfig config = configWithHosts(Map.of("a", "h1"));
        Connection healthy = mock(Connection.class);
        when(healthy.isConnected()).thenReturn(true);
        SmbConnectionFactory f = new SmbConnectionFactory(config, mock(SMBClient.class), null) {
            @Override
            protected PooledShare dial(String volumeId) {
                return new PooledShare(healthy, null, null, volumeId);
            }
        };
        try {
            SmbConnectionFactory.SmbShareHandle handle = f.open("a");
            assertEquals(1, f.pooledForTesting("a").inUse.get(), "open() must take an in-use lease");
            handle.close();
            assertEquals(0, f.pooledForTesting("a").inUse.get(), "close() releases the lease");
            handle.close();   // idempotent — must not double-decrement
            assertEquals(0, f.pooledForTesting("a").inUse.get(), "double-close must be a no-op");
        } finally {
            f.shutdown();
        }
    }
}
