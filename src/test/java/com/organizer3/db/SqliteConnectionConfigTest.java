package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the SQLite connection configuration used in production
 * ({@link org.sqlite.SQLiteConfig} + {@link org.sqlite.SQLiteDataSource}) sets up
 * {@code busy_timeout=60000} and {@code journal_mode=WAL}.
 *
 * <p>WAL mode is silently ignored on {@code :memory:} databases (they always report
 * {@code memory}), so a file-based temp DB is required for the journal_mode assertion.
 *
 * <p>Also includes a concurrent-writer regression test: two threads running UPDATEs
 * in a tight loop for 5 seconds must both succeed — proving that {@code busy_timeout}
 * serializes contention instead of failing immediately.
 */
class SqliteConnectionConfigTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private Jdbi jdbi;

    /** Build a datasource mirroring the production config from Application.java. */
    @BeforeEach
    void setUp() {
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(60_000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setSharedCache(true);

        dataSource = new SQLiteDataSource(sqliteConfig);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));

        jdbi = Jdbi.create(dataSource);
    }

    @AfterEach
    void tearDown() {
        // temp dir cleaned up automatically by @TempDir
    }

    // ── 1. busy_timeout is set ────────────────────────────────────────────────

    @Test
    void busyTimeout_isConfiguredToNonZero() {
        int timeout = jdbi.withHandle(h ->
                h.createQuery("PRAGMA busy_timeout")
                        .mapTo(Integer.class)
                        .one());
        assertTrue(timeout > 0,
                "busy_timeout must be > 0; actual=" + timeout);
        assertEquals(60_000, timeout,
                "busy_timeout must be 60,000 ms; actual=" + timeout);
    }

    // ── 2. WAL mode is active ─────────────────────────────────────────────────

    @Test
    void journalMode_isWal() {
        // WAL mode is silently ignored on :memory: — must use a file-based DB.
        String mode = jdbi.withHandle(h ->
                h.createQuery("PRAGMA journal_mode")
                        .mapTo(String.class)
                        .one());
        assertEquals("wal", mode,
                "journal_mode must be WAL; actual=" + mode);
    }

    // ── 3. Concurrent-writer regression ───────────────────────────────────────
    //
    // Two threads each run short UPDATEs in a tight loop for 5 seconds.
    // Without busy_timeout=0 this would fail immediately on first contention.
    // With busy_timeout=60000, SQLite serializes the writes and both succeed.

    @Test
    void concurrentWriters_bothSucceedWithBusyTimeout() throws Exception {
        // Bootstrap schema — just a simple counter table.
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS counter (id INTEGER PRIMARY KEY, value INTEGER)");
            h.execute("INSERT OR IGNORE INTO counter (id, value) VALUES (1, 0)");
        });

        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Each writer thread increments counter row in a tight loop.
        Runnable writer = () -> {
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long deadline = System.currentTimeMillis() + 3_000; // 3 second run
            while (System.currentTimeMillis() < deadline) {
                try {
                    jdbi.useHandle(h ->
                            h.execute("UPDATE counter SET value = value + 1 WHERE id = 1"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    // Stop on failure — we want to detect the first failure.
                    break;
                }
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<?> f1 = exec.submit(writer);
        Future<?> f2 = exec.submit(writer);
        startLatch.countDown(); // release both writers simultaneously

        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        exec.shutdown();

        assertEquals(0, failCount.get(),
                "Expected 0 write failures with busy_timeout=60000; " +
                "failures=" + failCount.get() + " successes=" + successCount.get());
        assertTrue(successCount.get() > 0,
                "Expected at least some successful writes; count=" + successCount.get());
    }
}
