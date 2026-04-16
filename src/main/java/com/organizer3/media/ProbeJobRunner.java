package com.organizer3.media;

import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Runs {@code probe videos} work in the background so agents and long-chain
 * orchestrators don't need to hold a synchronous MCP call open for hours.
 *
 * <p>Only one probe job runs at a time — the app supports a single active SMB mount,
 * so parallel probes across volumes aren't possible anyway. Attempting to start a
 * second job while one is running returns the existing job's state with a
 * {@code alreadyRunning} flag.
 *
 * <p>Jobs are identified by a UUID. State is in-memory only — completed jobs are
 * retained for {@link #COMPLETED_JOB_RETENTION} so the client has time to poll the
 * final state, then evicted on the next start/status call.
 */
@Slf4j
public class ProbeJobRunner {

    /** Batch size per findUnprobed call inside a running job. */
    private static final int BATCH_SIZE = 50;

    /** How long finished jobs remain queryable before eviction. */
    private static final long COMPLETED_JOB_RETENTION = TimeUnit.HOURS.toMillis(24);

    private final VideoRepository videoRepo;
    private final BiFunction<Long, String, Map<String, Object>> prober;
    private final ExecutorService executor;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public ProbeJobRunner(VideoRepository videoRepo,
                           BiFunction<Long, String, Map<String, Object>> prober) {
        this(videoRepo, prober, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe-job-runner");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Test-friendly constructor — pass a same-thread executor for deterministic assertions. */
    public ProbeJobRunner(VideoRepository videoRepo,
                           BiFunction<Long, String, Map<String, Object>> prober,
                           ExecutorService executor) {
        this.videoRepo = videoRepo;
        this.prober = prober;
        this.executor = executor;
    }

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Start a new background probe job on {@code volumeId}.
     *
     * @param volumeId  the mounted volume to probe
     * @param maxVideos optional cap on how many unprobed videos to attempt this run;
     *                  {@code <= 0} means "probe all remaining"
     * @return state of the started job, or of the already-running job if one exists
     */
    public synchronized JobState start(String volumeId, int maxVideos) {
        evictStaleCompleted();
        JobState active = activeJob();
        if (active != null) {
            return active.withAlreadyRunning(true);
        }
        String id = UUID.randomUUID().toString();
        long totalInitially = videoRepo.countUnprobed(volumeId);
        JobState state = new JobState(id, volumeId, LocalDateTime.now(), totalInitially, maxVideos);
        jobs.put(id, state);
        Future<?> future = executor.submit(() -> runLoop(state));
        state.future = future;
        return state;
    }

    public JobState status(String jobId) {
        evictStaleCompleted();
        JobState s = jobs.get(jobId);
        if (s == null) throw new IllegalArgumentException("No job with id " + jobId);
        return s;
    }

    public JobState active() {
        return activeJob();
    }

    public boolean cancel(String jobId) {
        JobState s = jobs.get(jobId);
        if (s == null) return false;
        s.cancelRequested.set(true);
        if (s.future != null) s.future.cancel(true);
        return true;
    }

    public void shutdown() {
        for (JobState s : jobs.values()) s.cancelRequested.set(true);
        executor.shutdownNow();
    }

    // ── internals ───────────────────────────────────────────────────────────

    private synchronized JobState activeJob() {
        for (JobState s : jobs.values()) {
            if (s.status == Status.RUNNING) return s;
        }
        return null;
    }

    private void evictStaleCompleted() {
        long cutoff = System.currentTimeMillis() - COMPLETED_JOB_RETENTION;
        jobs.values().removeIf(s -> s.status != Status.RUNNING
                && s.completedAt != null
                && java.time.Duration.between(s.completedAt, LocalDateTime.now())
                        .toMillis() > (System.currentTimeMillis() - cutoff));
    }

    private void runLoop(JobState state) {
        try {
            long cursor = 0;
            int attempted = 0;
            while (!state.cancelRequested.get()) {
                int batchLimit = state.maxVideos > 0
                        ? Math.min(BATCH_SIZE, state.maxVideos - attempted)
                        : BATCH_SIZE;
                if (batchLimit <= 0) break;

                List<Video> batch = videoRepo.findUnprobed(state.volumeId, cursor, batchLimit);
                if (batch.isEmpty()) break;

                for (Video v : batch) {
                    if (state.cancelRequested.get()) break;
                    cursor = Math.max(cursor, v.getId());
                    Map<String, Object> meta = prober.apply(v.getId(), v.getFilename());
                    if (meta.isEmpty() || !meta.containsKey("durationSeconds")) {
                        state.failed.incrementAndGet();
                    } else {
                        applyMeta(v, meta);
                        state.probed.incrementAndGet();
                    }
                    attempted++;
                }
            }
            state.status = state.cancelRequested.get() ? Status.CANCELLED : Status.COMPLETED;
        } catch (RuntimeException e) {
            log.warn("probe job {} failed", state.id, e);
            state.status = Status.FAILED;
            state.failureReason = e.getMessage();
        } finally {
            state.completedAt = LocalDateTime.now();
        }
    }

    private void applyMeta(Video v, Map<String, Object> meta) {
        Long    durationSec = meta.get("durationSeconds") instanceof Number n ? n.longValue() : null;
        Integer width       = meta.get("width")  instanceof Number n ? n.intValue()  : null;
        Integer height      = meta.get("height") instanceof Number n ? n.intValue()  : null;
        String  videoCodec  = nullIfUnknown(stringOf(meta.get("videoCodec")));
        String  audioCodec  = nullIfUnknown(stringOf(meta.get("audioCodec")));
        String  container   = containerFrom(v.getFilename());
        videoRepo.updateMetadata(v.getId(), durationSec, width, height, videoCodec, audioCodec, container);
    }

    private static String containerFrom(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        return (dot < 0 || dot == filename.length() - 1) ? null
                : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String nullIfUnknown(String s) {
        return (s == null || "unknown".equalsIgnoreCase(s)) ? null : s;
    }

    private static String stringOf(Object o) { return o == null ? null : o.toString(); }

    // ── shared types ────────────────────────────────────────────────────────

    public enum Status { RUNNING, COMPLETED, CANCELLED, FAILED }

    public static final class JobState {
        private final String id;
        private final String volumeId;
        private final LocalDateTime startedAt;
        private final long totalInitiallyUnprobed;
        private final int maxVideos;
        private final AtomicInteger probed   = new AtomicInteger();
        private final AtomicInteger failed   = new AtomicInteger();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile Status status = Status.RUNNING;
        private volatile LocalDateTime completedAt;
        private volatile String failureReason;
        private volatile Future<?> future;
        private final boolean alreadyRunning;

        JobState(String id, String volumeId, LocalDateTime startedAt,
                 long totalInitiallyUnprobed, int maxVideos) {
            this(id, volumeId, startedAt, totalInitiallyUnprobed, maxVideos, false);
        }

        private JobState(String id, String volumeId, LocalDateTime startedAt,
                         long totalInitiallyUnprobed, int maxVideos, boolean alreadyRunning) {
            this.id = id;
            this.volumeId = volumeId;
            this.startedAt = startedAt;
            this.totalInitiallyUnprobed = totalInitiallyUnprobed;
            this.maxVideos = maxVideos;
            this.alreadyRunning = alreadyRunning;
        }

        JobState withAlreadyRunning(boolean flag) {
            JobState s = new JobState(id, volumeId, startedAt, totalInitiallyUnprobed, maxVideos, flag);
            s.probed.set(this.probed.get());
            s.failed.set(this.failed.get());
            s.status = this.status;
            s.completedAt = this.completedAt;
            s.failureReason = this.failureReason;
            return s;
        }

        public String  id()                    { return id; }
        public String  volumeId()              { return volumeId; }
        public String  startedAt()             { return startedAt.toString(); }
        public String  completedAt()           { return completedAt == null ? null : completedAt.toString(); }
        public long    totalInitiallyUnprobed(){ return totalInitiallyUnprobed; }
        public int     maxVideos()             { return maxVideos; }
        public int     probed()                { return probed.get(); }
        public int     failed()                { return failed.get(); }
        public Status  status()                { return status; }
        public String  failureReason()         { return failureReason; }
        public boolean alreadyRunning()        { return alreadyRunning; }
    }
}
