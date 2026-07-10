package com.organizer3.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostCommitSmbExecutorTest {

    @Test
    void submit_runsTask() {
        PostCommitSmbExecutor postCommitSmbExecutor = new PostCommitSmbExecutor(Runnable::run);
        AtomicBoolean ran = new AtomicBoolean(false);

        postCommitSmbExecutor.submit("label", () -> ran.set(true));

        assertTrue(ran.get(), "task should have run synchronously on the direct executor");
    }

    @Test
    void submit_swallowsTaskException() {
        PostCommitSmbExecutor postCommitSmbExecutor = new PostCommitSmbExecutor(Runnable::run);

        assertDoesNotThrow(() ->
                postCommitSmbExecutor.submit("boom", () -> {
                    throw new RuntimeException("simulated SMB failure");
                }));
    }

    /**
     * Uses a real bounded, single-thread executor (default AbortPolicy) so a third submission is
     * rejected while the pool is saturated. submit() must swallow the RejectedExecutionException
     * rather than letting it escape to the caller.
     */
    @Test
    void submit_droppedOnSaturation() throws InterruptedException {
        CountDownLatch blockFirstTask = new CountDownLatch(1);
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        ThreadPoolExecutor tiny = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        try {
            PostCommitSmbExecutor postCommitSmbExecutor = new PostCommitSmbExecutor(tiny);

            // Occupies the sole worker thread until we release the latch.
            postCommitSmbExecutor.submit("blocking", () -> {
                firstTaskStarted.countDown();
                await(blockFirstTask);
            });
            assertTrue(firstTaskStarted.await(5, TimeUnit.SECONDS), "first task should have started");

            // Fills the single-capacity queue.
            postCommitSmbExecutor.submit("queued", () -> { });

            // Pool full (1 running + 1 queued, capacity 1) — this submission must be rejected
            // and dropped without throwing.
            assertDoesNotThrow(() -> postCommitSmbExecutor.submit("dropped", () -> { }));
        } finally {
            blockFirstTask.countDown();
            tiny.shutdown();
            tiny.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
