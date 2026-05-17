package com.organizer3.smb;

import com.hierynomus.smbj.SMBClient;
import com.organizer3.config.SmbSettings;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmbConnectionFactoryTest {

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
}
