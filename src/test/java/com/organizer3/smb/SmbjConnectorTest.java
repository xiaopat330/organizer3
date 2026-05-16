package com.organizer3.smb;

import com.hierynomus.smbj.connection.Connection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SmbjConnectorTest {

    @Test
    void connectWithTimeoutThrowsWhenOperationBlocksPastBudget() {
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        SmbjConnector.ConnectOp blocking = () -> {
            try {
                released.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        };

        long started = System.nanoTime();
        SmbConnectionException ex = assertThrows(SmbConnectionException.class,
                () -> SmbjConnector.connectWithTimeout(blocking, "ghost-host", 1));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(ex.getMessage().contains("timed out"), "message mentions timeout: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("ghost-host"));
        assertTrue(elapsedMs >= 900 && elapsedMs < 3_000,
                "timeout fired in expected window, elapsed=" + elapsedMs + "ms");
        released.countDown();
    }

    @Test
    void connectWithTimeoutReturnsConnectionWhenFast() throws Exception {
        Connection fake = null;
        SmbjConnector.ConnectOp fast = () -> fake;
        Connection result = SmbjConnector.connectWithTimeout(fast, "host", 5);
        assertNull(result);
    }

    @Test
    void connectWithTimeoutPropagatesIOException() {
        SmbjConnector.ConnectOp failing = () -> { throw new IOException("boom"); };
        IOException ex = assertThrows(IOException.class,
                () -> SmbjConnector.connectWithTimeout(failing, "host", 5));
        assertEquals("boom", ex.getMessage());
    }

    @Test
    void connectWithTimeoutCancelsBlockedOpOnTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch saw = new CountDownLatch(1);

        SmbjConnector.ConnectOp blocking = () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                saw.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        };

        assertThrows(SmbConnectionException.class,
                () -> SmbjConnector.connectWithTimeout(blocking, "h", 1));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(saw.await(2, TimeUnit.SECONDS), "blocked op was interrupted by future.cancel(true)");
    }
}
