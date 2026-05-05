package com.organizer3.web.routes;

import com.organizer3.avstars.AvScreenshotWorker;
import com.organizer3.avstars.cleanup.AvArtifactCleaner;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository.ActressProgress;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.media.StreamActivityTracker;
import io.javalin.Javalin;
import io.javalin.http.Context;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface for the AV screenshot generation queue.
 *
 * <p>Enqueue, pause, resume, stop per-actress; progress polling; worker state.
 * See spec/PROPOSAL_AV_SCREENSHOT_QUEUE.md §4.
 */
@Slf4j
@RequiredArgsConstructor
public class AvScreenshotQueueRoutes {

    private final AvScreenshotQueueRepository queueRepo;
    private final AvVideoRepository videoRepo;
    private final AvScreenshotRepository screenshotRepo;
    private final AvScreenshotWorker worker;
    private final StreamActivityTracker streamTracker;
    private final AvArtifactCleaner artifactCleaner;

    public void register(Javalin app) {

        // POST /api/av/actresses/{id}/screenshots/enqueue
        // Adds each video with no existing screenshots to the queue. Idempotent.
        app.post("/api/av/actresses/{id}/screenshots/enqueue", ctx -> {
            long actressId = parseId(ctx);
            if (actressId < 0) { ctx.status(400); return; }
            var videos = videoRepo.findByActress(actressId);

            int alreadyDone   = 0;
            int alreadyQueued = 0;
            int enqueued      = 0;

            for (var video : videos) {
                if (screenshotRepo.countByVideoId(video.getId()) > 0) {
                    alreadyDone++;
                } else {
                    boolean inserted = queueRepo.enqueueIfAbsent(actressId, video.getId());
                    if (inserted) enqueued++;
                    else alreadyQueued++;
                }
            }

            log.info("enqueue actress={}: enqueued={} alreadyDone={} alreadyQueued={}",
                    actressId, enqueued, alreadyDone, alreadyQueued);
            ctx.json(Map.of("enqueued", enqueued, "alreadyDone", alreadyDone, "alreadyQueued", alreadyQueued));
        });

        // POST /api/av/actresses/{id}/screenshots/pause
        app.post("/api/av/actresses/{id}/screenshots/pause", ctx -> {
            long actressId = parseId(ctx);
            if (actressId < 0) { ctx.status(400); return; }
            int paused = queueRepo.pauseActress(actressId);
            ctx.json(Map.of("paused", paused));
        });

        // POST /api/av/actresses/{id}/screenshots/resume
        app.post("/api/av/actresses/{id}/screenshots/resume", ctx -> {
            long actressId = parseId(ctx);
            if (actressId < 0) { ctx.status(400); return; }
            int resumed = queueRepo.resumeActress(actressId);
            ctx.json(Map.of("resumed", resumed));
        });

        // DELETE /api/av/actresses/{id}/screenshots/queue ("stop")
        app.delete("/api/av/actresses/{id}/screenshots/queue", ctx -> {
            long actressId = parseId(ctx);
            if (actressId < 0) { ctx.status(400); return; }
            int removed = queueRepo.clearForActress(actressId);
            ctx.json(Map.of("removed", removed));
        });

        // DELETE /api/av/actresses/{id}/screenshots ("reset")
        // Brings the actress back to a fresh state: removes every queue row regardless of
        // status (DONE/FAILED rows would otherwise block re-enqueue via the av_video_id
        // UNIQUE constraint), deletes screenshot DB rows for her videos, and removes the
        // on-disk screenshot directories. Refuses with 409 if the worker is currently
        // generating for this actress — caller should pause+stop first or wait.
        app.delete("/api/av/actresses/{id}/screenshots", ctx -> {
            long actressId = parseId(ctx);
            if (actressId < 0) { ctx.status(400); return; }
            Long currentActress = worker.getCurrentActressId();
            if (currentActress != null && currentActress == actressId) {
                ctx.status(409).json(Map.of(
                        "error", "in_progress",
                        "message", "Worker is currently generating for this actress; pause/stop and retry."));
                return;
            }
            int queueRows  = queueRepo.deleteAllForActress(actressId);
            int dbRows     = screenshotRepo.deleteByActressId(actressId);
            List<Long> videoIds = videoRepo.findByActress(actressId).stream()
                    .map(AvVideo::getId).toList();
            int dirsRemoved = artifactCleaner.deleteScreenshotsFor(videoIds);
            log.info("reset actress={}: queueRows={} dbRows={} dirsRemoved={}",
                    actressId, queueRows, dbRows, dirsRemoved);
            ctx.json(Map.of(
                    "queueRowsCleared",   queueRows,
                    "screenshotsDeleted", dbRows,
                    "directoriesRemoved", dirsRemoved));
        });

        // GET /api/av/actresses/{id}/screenshots/progress
        app.get("/api/av/actresses/{id}/screenshots/progress", ctx -> {
            long actressId = Long.parseLong(ctx.pathParam("id"));
            ActressProgress p = queueRepo.progressForActress(actressId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pending",       p.pending());
            out.put("inProgress",    p.inProgress());
            out.put("paused",        p.paused());
            out.put("done",          p.done());
            out.put("failed",        p.failed());
            out.put("total",         p.total());
            out.put("currentVideoId", p.currentVideoId());
            ctx.json(out);
        });

        // GET /api/av/screenshot-queue/state
        // Path is deliberately outside /api/av/screenshots/ to avoid colliding with the
        // pre-existing image-serving route /api/av/screenshots/{videoId}/{seq}, which would
        // otherwise match "worker/state" as videoId="worker" and reject with 400.
        app.get("/api/av/screenshot-queue/state", ctx -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("running",          worker.isRunning());
            out.put("streamActive",     streamTracker.isPlaying(30_000));
            out.put("queueDepth",       queueRepo.globalDepth());
            out.put("currentVideoId",   worker.getCurrentVideoId());
            out.put("currentActressId", worker.getCurrentActressId());
            ctx.json(out);
        });
    }

    private long parseId(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
