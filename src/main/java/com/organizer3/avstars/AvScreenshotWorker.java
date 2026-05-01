package com.organizer3.avstars;

import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import com.organizer3.avstars.model.AvScreenshotQueueRow;
import com.organizer3.media.StreamActivityTracker;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single background daemon thread that drains the {@code av_screenshot_queue}.
 *
 * <p>Lifecycle mirrors {@code EnrichmentRunner}: daemon thread, AtomicBoolean
 * stopRequested + paused, synchronized start/stop, 30s sleep on unexpected Throwable.
 *
 * <p>Per-video timeout: a dedicated single-thread executor runs each
 * {@code generateForVideo} call. On timeout the executor is abandoned and replaced
 * so a wedged FFmpeg native thread cannot stall the queue — same pattern as
 * {@code AvScreenshotsCommand}.
 *
 * <p>Playback gating: {@code StreamActivityTracker.isPlaying(30_000)} is checked
 * between videos so concurrent FFmpeg streams don't stutter active playback. The
 * in-flight video always finishes; gating only defers the next claim.
 */
@Slf4j
public class AvScreenshotWorker {

    private static final long DEFAULT_LOOP_SLEEP_MS   = 1_000;   // between successful videos
    private static final long DEFAULT_IDLE_SLEEP_MS   = 5_000;   // queue empty or stream active
    private static final long DEFAULT_PAUSED_SLEEP_MS = 30_000;  // internal pause flag
    static final int  PER_VIDEO_TIMEOUT_SEC = 120;

    private final AvScreenshotQueueRepository queueRepo;
    private final AvScreenshotService screenshotService;
    private final StreamActivityTracker streamActivity;
    private final long loopSleepMs;
    private final long idleSleepMs;
    private final long pausedSleepMs;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    // Internal flag kept for future global-pause endpoint; not exposed via HTTP in v1.
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile Thread thread;
    private volatile Long currentVideoId;
    private volatile Long currentActressId;

    // Single-thread executor for the FFmpeg call. Replaced on timeout to abandon
    // a wedged native thread without blocking the loop.
    private volatile ExecutorService generator;

    public AvScreenshotWorker(AvScreenshotQueueRepository queueRepo,
                               AvScreenshotService screenshotService,
                               StreamActivityTracker streamActivity) {
        this(queueRepo, screenshotService, streamActivity,
                DEFAULT_LOOP_SLEEP_MS, DEFAULT_IDLE_SLEEP_MS, DEFAULT_PAUSED_SLEEP_MS);
    }

    /** Package-private constructor for tests — allows overriding sleep durations. */
    AvScreenshotWorker(AvScreenshotQueueRepository queueRepo,
                       AvScreenshotService screenshotService,
                       StreamActivityTracker streamActivity,
                       long loopSleepMs, long idleSleepMs, long pausedSleepMs) {
        this.queueRepo      = queueRepo;
        this.screenshotService = screenshotService;
        this.streamActivity = streamActivity;
        this.loopSleepMs    = loopSleepMs;
        this.idleSleepMs    = idleSleepMs;
        this.pausedSleepMs  = pausedSleepMs;
        this.generator      = newGenerator();
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stopRequested.set(false);
        int reset = queueRepo.resetOrphanedInFlightJobs();
        if (reset > 0) {
            log.info("av-screenshot-worker: reset {} orphaned IN_PROGRESS rows to PENDING", reset);
        }
        thread = new Thread(this::runLoop, "av-screenshot-worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        log.info("av-screenshot-worker started");
    }

    public synchronized void stop() {
        stopRequested.set(true);
        if (thread != null) thread.interrupt();
        log.info("av-screenshot-worker stop requested");
    }

    public void setPaused(boolean on) { paused.set(on); }
    public boolean isPaused()         { return paused.get(); }
    public boolean isRunning()        { return thread != null && thread.isAlive(); }
    public Long getCurrentVideoId()   { return currentVideoId; }
    public Long getCurrentActressId() { return currentActressId; }

    private void runLoop() {
        while (!stopRequested.get()) {
            try {
                runOneStep();
            } catch (Throwable t) {
                log.error("av-screenshot-worker loop error: {}", t.getMessage(), t);
                sleepInterruptibly(30_000);
            }
            if (Thread.interrupted()) break;
        }
        log.info("av-screenshot-worker stopped");
    }

    private void runOneStep() {
        if (paused.get()) {
            sleepInterruptibly(pausedSleepMs);
            return;
        }
        if (streamActivity.isPlaying(30_000)) {
            log.debug("av-screenshot-worker: stream active, deferring next video");
            sleepInterruptibly(idleSleepMs);
            return;
        }

        var row = queueRepo.claimNextPending();
        if (row.isEmpty()) {
            sleepInterruptibly(idleSleepMs);
            return;
        }

        AvScreenshotQueueRow job = row.get();
        currentVideoId   = job.getAvVideoId();
        currentActressId = job.getAvActressId();
        try {
            processVideo(job);
        } finally {
            currentVideoId   = null;
            currentActressId = null;
        }
        sleepInterruptibly(loopSleepMs);
    }

    private void processVideo(AvScreenshotQueueRow job) {
        final long videoId = job.getAvVideoId();
        Future<List<String>> future = generator.submit(() -> screenshotService.generateForVideo(videoId));
        List<String> urls;
        try {
            urls = future.get(PER_VIDEO_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("av-screenshot-worker: timeout after {}s for video {} — abandoning executor",
                    PER_VIDEO_TIMEOUT_SEC, videoId);
            // FFmpeg native thread may be wedged; replace executor so next video gets a fresh one.
            generator.shutdownNow();
            generator = newGenerator();
            queueRepo.markFailed(job.getId(), "timeout after " + PER_VIDEO_TIMEOUT_SEC + "s");
            return;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            queueRepo.markFailed(job.getId(), "interrupted");
            return;
        } catch (ExecutionException ee) {
            // Unwrap the actual cause so the error message is meaningful (not "ClassName: msg")
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            log.warn("av-screenshot-worker: error for video {}: {}", videoId, cause.getMessage(), cause);
            queueRepo.markFailed(job.getId(), cause.getMessage());
            return;
        }

        if (urls == null || urls.isEmpty()) {
            queueRepo.markFailed(job.getId(), "no frames generated");
        } else {
            queueRepo.markDone(job.getId());
            log.debug("av-screenshot-worker: done video {} ({} frames)", videoId, urls.size());
        }
    }

    private static void sleepInterruptibly(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static ExecutorService newGenerator() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "av-screenshot-gen");
            t.setDaemon(true);
            return t;
        });
    }
}
