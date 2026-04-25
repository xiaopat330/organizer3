package com.organizer3.web.routes;

import com.organizer3.web.JavdbDiscoveryService;
import com.organizer3.web.JavdbEnrichmentActionService;
import io.javalin.Javalin;

/**
 * All /api/javdb/discovery/* routes. Wired by {@link com.organizer3.web.WebServer}.
 */
public class JavdbDiscoveryRoutes {

    private final JavdbDiscoveryService service;
    private final JavdbEnrichmentActionService actionService;

    public JavdbDiscoveryRoutes(JavdbDiscoveryService service, JavdbEnrichmentActionService actionService) {
        this.service       = service;
        this.actionService = actionService;
    }

    public void register(Javalin app) {
        app.get("/api/javdb/discovery/actresses", ctx ->
                ctx.json(service.listActresses()));

        app.get("/api/javdb/discovery/actresses/{id}/titles", ctx -> {
            long id;
            try {
                id = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                return;
            }
            ctx.json(service.getActressTitles(id));
        });

        app.get("/api/javdb/discovery/actresses/{id}/profile", ctx -> {
            long id;
            try {
                id = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                return;
            }
            var profile = service.getActressProfile(id);
            if (profile == null) {
                ctx.status(404);
            } else {
                ctx.json(profile);
            }
        });

        app.get("/api/javdb/discovery/queue", ctx ->
                ctx.json(service.getQueueStatus()));

        // ── M3 action endpoints ────────────────────────────────────────────

        app.post("/api/javdb/discovery/actresses/{id}/enqueue", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            int count = actionService.enqueueActress(id);
            ctx.json(java.util.Map.of("enqueued", count));
        });

        app.delete("/api/javdb/discovery/actresses/{id}/queue", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            actionService.cancelForActress(id);
            ctx.status(204);
        });

        app.delete("/api/javdb/discovery/queue", ctx -> {
            actionService.cancelAll();
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/queue/pause", ctx -> {
            var body = ctx.bodyAsClass(PauseRequest.class);
            actionService.setPaused(body.paused());
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/actresses/{id}/retry", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            actionService.retryFailedForActress(id);
            ctx.status(204);
        });

        app.get("/api/javdb/discovery/actresses/{id}/errors", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            ctx.json(actionService.getErrorsForActress(id));
        });

        app.get("/api/javdb/discovery/actresses/{id}/conflicts", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            ctx.json(service.getActressConflicts(id));
        });
    }

    private long parseId(io.javalin.http.Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private record PauseRequest(boolean paused) {}
}
