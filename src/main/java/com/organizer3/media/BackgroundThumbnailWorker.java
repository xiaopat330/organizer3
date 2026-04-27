package com.organizer3.media;

import com.organizer3.config.volume.BackgroundThumbnailConfig;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import com.organizer3.smb.NasAvailabilityMonitor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-priority background worker that pre-generates thumbnails for titles the user
 * has shown interest in (favorites, bookmarks, recent visits).
 *
 * <p>Runs on a dedicated thread. See {@code spec/PROPOSAL_BACKGROUND_THUMBNAILS.md}.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>On {@link #start()}, sleeps {@code startupDelaySec} before the first cycle.</li>
 *   <li>Each cycle: wait for quiet period → fetch ranked candidates → filter
 *       already-complete and known-bad videos → generate one at a time with a hard
 *       timeout → sweep evictions → sleep.</li>
 *   <li>{@link #stop()} interrupts the thread and best-effort-shuts down the executor.</li>
 * </ol>
 *
 * <p>Hang protection: each {@code generateBlocking} call runs on a dedicated
 * single-thread executor with {@code Future.get(timeout)}. On timeout, the future is
 * cancelled and the executor is replaced — if JavaCV leaks native resources, the old
 * thread is abandoned rather than wedging the worker.
 */
@Slf4j
public class BackgroundThumbnailWorker {

    private final BackgroundThumbnailConfig config;
    private final BackgroundThumbnailQueue queue;
    private final ThumbnailService thumbnailService;
    private final ThumbnailEvictor evictor;
    private final VideoRepository videoRepo;
    private final UserActivityTracker activityTracker;
    private final NasAvailabilityMonitor monitor;

    private final AtomicBoolean enabled = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private Thread thread;
    private ExecutorService generationExecutor;

    // In-memory per-process skip set — cleared on app restart.
    // Video IDs that have failed twice are added here.
    private final Set<Long> failSet = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Map<Long, Integer> attemptCounts = new HashMap<>();

    // Status for the shell status command
    @Getter private volatile long lastGeneratedAt = 0L;
    @Getter private volatile String lastGeneratedCode = null;
    @Getter private volatile int lastQueueSize = 0;
    @Getter private volatile long totalGenerated = 0L;
    @Getter private volatile long totalEvicted = 0L;

    public BackgroundThumbnailWorker(BackgroundThumbnailConfig config,
                                     BackgroundThumbnailQueue queue,
                                     ThumbnailService thumbnailService,
                                     ThumbnailEvictor evictor,
                                     VideoRepository videoRepo,
                                     UserActivityTracker activityTracker) {
        this(config, queue, thumbnailService, evictor, videoRepo, activityTracker,
                NasAvailabilityMonitor.alwaysAvailable());
    }

    public BackgroundThumbnailWorker(BackgroundThumbnailConfig config,
                                     BackgroundThumbnailQueue queue,
                                     ThumbnailService thumbnailService,
                                     ThumbnailEvictor evictor,
                                     VideoRepository videoRepo,
                                     UserActivityTracker activityTracker,
                                     NasAvailabilityMonitor monitor) {
        this.config = config;
        this.queue = queue;
        this.thumbnailService = thumbnailService;
        this.evictor = evictor;
        this.videoRepo = videoRepo;
        this.activityTracker = activityTracker;
        this.monitor = monitor;
        this.enabled.set(config.enabledOrDefault());
        this.generationExecutor = newGenerationExecutor();
    }

    private static ExecutorService newGenerationExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bg-thumb-ffmpeg");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** Starts the worker thread. Safe to call multiple times (no-op if already running). */
    public synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stopRequested.set(false);
        if (generationExecutor.isShutdown()) generationExecutor = newGenerationExecutor();
        thread = new Thread(this::runLoop, "bg-thumb-worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        log.info("Background thumbnail worker started (enabled={})", enabled.get());
    }

    /** Signals the worker to stop and best-effort-shuts down executors. */
    public synchronized void stop() {
        stopRequested.set(true);
        if (thread != null) thread.interrupt();
        if (generationExecutor != null) generationExecutor.shutdownNow();
    }

    /** Turn the worker on or off at runtime. */
    public void setEnabled(boolean on) {
        enabled.set(on);
        log.info("Background thumbnail worker enabled={}", on);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    private void runLoop() {
        // Startup delay — give the user a quiet boot.
        if (!sleepInterruptibly(config.startupDelaySecOrDefault() * 1000L)) return;

        boolean nasPaused = false;
        while (!stopRequested.get()) {
            try {
                if (!enabled.get()) {
                    if (!sleepInterruptibly(60_000)) return;
                    continue;
                }
                if (!monitor.areAllHostsAvailable()) {
                    if (!nasPaused) {
                        log.info("Background thumbnail worker paused — NAS host(s) unreachable");
                        nasPaused = true;
                    }
                    if (!sleepInterruptibly(60_000)) return;
                    continue;
                }
                if (nasPaused) {
                    log.info("Background thumbnail worker resuming — NAS host(s) reachable");
                    nasPaused = false;
                }
                runOneCycle();
            } catch (Throwable t) {
                log.error("Background thumbnail worker cycle failed: {}", t.getMessage(), t);
                if (!sleepInterruptibly(60_000)) return;
            }
        }
    }

    /** Exposed for tests — runs a single cycle synchronously. */
    void runOneCycle() throws InterruptedException {
        if (!waitForQuiet()) return;

        List<BackgroundThumbnailQueue.Candidate> candidates =
                queue.topCandidates(config.maxCandidatesPerCycleOrDefault());
        List<BackgroundThumbnailQueue.Candidate> actionable = candidates.stream()
                .filter(c -> !failSet.contains(c.getVideoId()))
                .filter(c -> !thumbnailService.isComplete(c.getTitleCode(), c.getVideoFilename(), c.getVideoId()))
                .toList();
        lastQueueSize = actionable.size();

        if (actionable.isEmpty()) {
            runEviction();
            sleepInterruptibly(config.idleSleepSecOrDefault() * 1000L);
            return;
        }

        for (BackgroundThumbnailQueue.Candidate cand : actionable) {
            if (stopRequested.get() || !enabled.get()) return;
            if (!waitForQuiet()) return;

            Optional<Video> maybeVideo = videoRepo.findById(cand.getVideoId());
            if (maybeVideo.isEmpty()) continue;
            generateWithTimeout(cand, maybeVideo.get());
        }

        runEviction();
    }

    private void generateWithTimeout(BackgroundThumbnailQueue.Candidate cand, Video video) {
        long timeoutSec = config.generationTimeoutSecOrDefault();
        Future<?> future = generationExecutor.submit(() -> {
            try {
                thumbnailService.generateBlocking(cand.getTitleCode(), video, cand.getPrimaryActressName());
            } catch (Throwable e) {
                log.warn("Thumbnail generation failed for video {} ({}): {}",
                        cand.getVideoId(), cand.getTitleCode(), e.getMessage());
                throw new RuntimeException(e);
            }
        });
        try {
            future.get(timeoutSec, TimeUnit.SECONDS);
            lastGeneratedAt = System.currentTimeMillis();
            lastGeneratedCode = cand.getTitleCode();
            totalGenerated++;
            attemptCounts.remove(cand.getVideoId());
        } catch (TimeoutException te) {
            log.warn("Thumbnail generation timed out for video {} ({}) after {}s — abandoning",
                    cand.getVideoId(), cand.getTitleCode(), timeoutSec);
            future.cancel(true);
            // Replace the executor — the old thread may be stuck inside FFmpeg native code.
            abandonExecutor();
            recordFailure(cand.getVideoId());
        } catch (Exception e) {
            recordFailure(cand.getVideoId());
        }
    }

    private synchronized void abandonExecutor() {
        if (generationExecutor != null) generationExecutor.shutdownNow();
        generationExecutor = newGenerationExecutor();
    }

    private void recordFailure(long videoId) {
        int attempts = attemptCounts.merge(videoId, 1, Integer::sum);
        if (attempts >= 2) {
            failSet.add(videoId);
            attemptCounts.remove(videoId);
        }
    }

    private void runEviction() {
        int removed = evictor.sweep(config.evictionDaysOrDefault());
        if (removed > 0) {
            totalEvicted += removed;
            log.info("Evicted {} cold thumbnail directories", removed);
        }
    }

    /**
     * Waits until the quiet-period threshold is met. Returns false if the worker was
     * stopped or disabled while waiting.
     */
    private boolean waitForQuiet() {
        long quietMillis = config.quietThresholdSecOrDefault() * 1000L;
        while (!activityTracker.isQuiet(quietMillis)) {
            if (stopRequested.get() || !enabled.get()) return false;
            long remaining = quietMillis - activityTracker.millisSinceLast();
            if (remaining < 500) remaining = 500;
            if (!sleepInterruptibly(Math.min(remaining, 30_000))) return false;
        }
        return true;
    }

    /** @return false if interrupted / stop-requested. */
    private boolean sleepInterruptibly(long millis) {
        if (millis <= 0) return !stopRequested.get();
        try {
            Thread.sleep(millis);
            return !stopRequested.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
