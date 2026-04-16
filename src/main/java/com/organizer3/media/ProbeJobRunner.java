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
     * @return snapshot of the started job, or of the already-running job if one exists
     */
    public synchronized Snapshot start(String volumeId, int maxVideos) {
        evictStaleCompleted();
        JobState active = activeJob();
        if (active != null) {
            return Snapshot.of(active, true);
        }
        String id = UUID.randomUUID().toString();
        long totalInitially = videoRepo.countUnprobed(volumeId);
        JobState state = new JobState(id, volumeId, LocalDateTime.now(), totalInitially, maxVideos);
        jobs.put(id, state);
        Future<?> future = executor.submit(() -> runLoop(state));
        state.future = future;
        return Snapshot.of(state, false);
    }

    public Snapshot status(String jobId) {
        evictStaleCompleted();
        JobState s = jobs.get(jobId);
        if (s == null) throw new IllegalArgumentException("No job with id " + jobId);
        return Snapshot.of(s, false);
    }

    public Snapshot active() {
        JobState s = activeJob();
        return s == null ? null : Snapshot.of(s, false);
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

    /** Jackson-friendly immutable snapshot of a {@link JobState}. Returned by all public API methods. */
    public record Snapshot(
            String id,
            String volumeId,
            String startedAt,
            String completedAt,
            long totalInitiallyUnprobed,
            int maxVideos,
            int probed,
            int failed,
            Status status,
            String failureReason,
            boolean alreadyRunning
    ) {
        static Snapshot of(JobState s, boolean alreadyRunning) {
            return new Snapshot(
                    s.id, s.volumeId,
                    s.startedAt == null ? null : s.startedAt.toString(),
                    s.completedAt == null ? null : s.completedAt.toString(),
                    s.totalInitiallyUnprobed,
                    s.maxVideos,
                    s.probed.get(),
                    s.failed.get(),
                    s.status,
                    s.failureReason,
                    alreadyRunning);
        }
    }

    static final class JobState {
        final String id;
        final String volumeId;
        final LocalDateTime startedAt;
        final long totalInitiallyUnprobed;
        final int maxVideos;
        final AtomicInteger probed   = new AtomicInteger();
        final AtomicInteger failed   = new AtomicInteger();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        volatile Status status = Status.RUNNING;
        volatile LocalDateTime completedAt;
        volatile String failureReason;
        volatile Future<?> future;

        JobState(String id, String volumeId, LocalDateTime startedAt,
                 long totalInitiallyUnprobed, int maxVideos) {
            this.id = id;
            this.volumeId = volumeId;
            this.startedAt = startedAt;
            this.totalInitiallyUnprobed = totalInitiallyUnprobed;
            this.maxVideos = maxVideos;
        }
    }
}
