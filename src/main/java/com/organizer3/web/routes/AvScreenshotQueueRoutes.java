package com.organizer3.web.routes;

import com.organizer3.avstars.AvScreenshotWorker;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository.ActressProgress;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.media.StreamActivityTracker;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
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

    public void register(Javalin app) {

        // POST /api/av/actresses/{id}/screenshots/enqueue
        // Adds each video with no existing screenshots to the queue. Idempotent.
        app.post("/api/av/actresses/{id}/screenshots/enqueue", ctx -> {
            long actressId = Long.parseLong(ctx.pathParam("id"));
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
            long actressId = Long.parseLong(ctx.pathParam("id"));
            int paused = queueRepo.pauseActress(actressId);
            ctx.json(Map.of("paused", paused));
        });

        // POST /api/av/actresses/{id}/screenshots/resume
        app.post("/api/av/actresses/{id}/screenshots/resume", ctx -> {
            long actressId = Long.parseLong(ctx.pathParam("id"));
            int resumed = queueRepo.resumeActress(actressId);
            ctx.json(Map.of("resumed", resumed));
        });

        // DELETE /api/av/actresses/{id}/screenshots/queue ("stop")
        app.delete("/api/av/actresses/{id}/screenshots/queue", ctx -> {
            long actressId = Long.parseLong(ctx.pathParam("id"));
            int removed = queueRepo.clearForActress(actressId);
            ctx.json(Map.of("removed", removed));
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
}
