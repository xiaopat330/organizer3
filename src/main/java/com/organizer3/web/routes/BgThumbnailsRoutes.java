package com.organizer3.web.routes;

import com.organizer3.media.BackgroundThumbnailWorker;
import com.organizer3.media.BgThumbnailsState;
import io.javalin.Javalin;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP surface for the background-thumbnail worker toggle + status chip.
 *
 * <ul>
 *   <li>{@code GET  /api/bg-thumbnails/status} — current enabled flag + session metrics.</li>
 *   <li>{@code POST /api/bg-thumbnails/toggle} — flip the flag; persists to disk; returns
 *       the new status payload. Body-less.</li>
 * </ul>
 *
 * <p>The toggle is outside the utility-task atomic lock — the worker is always-on ambient state,
 * not a one-shot operation, and enabling/disabling it should be instant even while a task runs.
 */
public final class BgThumbnailsRoutes {

    private final BackgroundThumbnailWorker worker;
    private final BgThumbnailsState stateFile;

    public BgThumbnailsRoutes(BackgroundThumbnailWorker worker, BgThumbnailsState stateFile) {
        this.worker = worker;
        this.stateFile = stateFile;
    }

    public void register(Javalin app) {
        app.get("/api/bg-thumbnails/status",  ctx -> ctx.json(statusPayload()));
        app.post("/api/bg-thumbnails/toggle", ctx -> {
            boolean now = !worker.isEnabled();
            worker.setEnabled(now);
            stateFile.writeEnabled(now);
            ctx.json(statusPayload());
        });
    }

    private Map<String, Object> statusPayload() {
        var out = new LinkedHashMap<String, Object>();
        out.put("enabled",          worker.isEnabled());
        out.put("queueSize",        worker.getLastQueueSize());
        out.put("totalGenerated",   worker.getTotalGenerated());
        out.put("totalEvicted",     worker.getTotalEvicted());
        long lastGen = worker.getLastGeneratedAt();
        if (lastGen > 0) {
            out.put("lastGeneratedCode",  worker.getLastGeneratedCode());
            out.put("lastGeneratedAgoMs", Duration.between(
                    Instant.ofEpochMilli(lastGen), Instant.now()).toMillis());
        } else {
            out.put("lastGeneratedCode",  null);
            out.put("lastGeneratedAgoMs", null);
        }
        return out;
    }
}
