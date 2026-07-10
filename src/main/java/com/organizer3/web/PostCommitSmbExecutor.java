package com.organizer3.web;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs post-commit SMB side effects (NAS cover writes, folder renames) off the request thread
 * so an SMB stall never blocks an HTTP response.
 *
 * <p>Backed by a fixed 3-daemon-thread {@link ThreadPoolExecutor} with a bounded queue. On
 * saturation, tasks are logged at WARN and dropped — never {@code CallerRunsPolicy}, which would
 * reintroduce request-thread blocking. Dropped/failed tasks are healed by the promotion-rename
 * reconciler, the durability backstop for this path.
 *
 * <p>Mirrors the daemon-thread pattern used by
 * {@link com.organizer3.javdb.draft.PromotionRenameReconcileScheduler}.
 */
@Slf4j
public class PostCommitSmbExecutor {

    private static final int POOL_SIZE = 3;
    private static final int QUEUE_CAPACITY = 256;

    private final Executor executor;

    /** Production constructor: fixed 3-daemon-thread pool, bounded queue, drop-on-saturation. */
    public PostCommitSmbExecutor() {
        this.executor = buildExecutor();
    }

    /** Test constructor: injects an executor (e.g. a synchronous {@code Runnable::run} executor). */
    PostCommitSmbExecutor(Executor executor) {
        this.executor = executor;
    }

    private static ThreadPoolExecutor buildExecutor() {
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new DaemonThreadFactory(),
                new DropOnSaturationHandler());
        return tpe;
    }

    /**
     * Submit a post-commit SMB task. Any exception the task throws is caught and logged at WARN
     * — it never propagates to the caller. If the executor's queue is saturated, the task is
     * logged at WARN and dropped (belt-and-suspenders with the {@link RejectedExecutionHandler}).
     */
    public void submit(String label, Runnable task) {
        try {
            executor.execute(() -> runSafely(label, task));
        } catch (RejectedExecutionException e) {
            log.warn("post-commit SMB task dropped (queue full); reconciler will heal");
        }
    }

    private static void runSafely(String label, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("post-commit SMB task '{}' failed: {}", label, e.getMessage());
        }
    }

    /** Best-effort graceful shutdown; never throws. Called from the Application stop sequence. */
    public void shutdown() {
        try {
            if (executor instanceof ExecutorService service) {
                service.shutdown();
            }
        } catch (Exception e) {
            log.warn("PostCommitSmbExecutor shutdown failed: {}", e.getMessage());
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "post-commit-smb-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    private static final class DropOnSaturationHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("post-commit SMB task dropped (queue full); reconciler will heal");
        }
    }
}
