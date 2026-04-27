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
            ctx.json(service.getActressTitles(id, parseFilter(ctx)));
        });

        app.get("/api/javdb/discovery/actresses/{id}/tag-facets", ctx -> {
            long id;
            try {
                id = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                return;
            }
            ctx.json(service.getActressTagFacets(id, parseFilter(ctx)));
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

        app.get("/api/javdb/discovery/queue/items", ctx ->
                ctx.json(service.getActiveQueueItems()));

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

        app.post("/api/javdb/discovery/queue/resume", ctx -> {
            actionService.forceResume();
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/queue/items/{itemId}/move", ctx -> {
            long id = parseItemId(ctx);
            if (id < 0) { ctx.status(400); return; }
            var body = ctx.bodyAsClass(MoveRequest.class);
            switch (body.action()) {
                case "promote" -> actionService.promoteItem(id);
                case "demote"  -> actionService.demoteItem(id);
                case "top"     -> actionService.moveToTop(id);
                case "bottom"  -> actionService.moveToBottom(id);
                default        -> { ctx.status(400); return; }
            }
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/queue/items/{itemId}/pause", ctx -> {
            long id = parseItemId(ctx);
            if (id < 0) { ctx.status(400); return; }
            actionService.pauseItem(id);
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/queue/items/{itemId}/resume", ctx -> {
            long id = parseItemId(ctx);
            if (id < 0) { ctx.status(400); return; }
            actionService.resumeItem(id);
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/queue/items/{itemId}/requeue", ctx -> {
            long id = parseItemId(ctx);
            if (id < 0) { ctx.status(400); return; }
            actionService.requeueItem(id);
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

        app.get("/api/javdb/discovery/titles/{titleId}/enrichment", ctx -> {
            long titleId;
            try { titleId = Long.parseLong(ctx.pathParam("titleId")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var detail = service.getTitleEnrichmentDetail(titleId);
            if (detail == null) { ctx.status(404); return; }
            ctx.json(detail);
        });

        app.post("/api/javdb/discovery/actresses/{id}/titles/{titleId}/reenrich", ctx -> {
            long actressId = parseId(ctx);
            long titleId;
            try { titleId = Long.parseLong(ctx.pathParam("titleId")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            if (actressId < 0) { ctx.status(400); return; }
            actionService.reEnqueueTitle(titleId, actressId);
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/actresses/{id}/profile/reenrich", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            actionService.reEnqueueActressProfile(id);
            ctx.status(204);
        });

        app.post("/api/javdb/discovery/actresses/{id}/profile/derive-slug", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            var result = actionService.deriveSlugAndEnqueueProfile(id);
            int status = switch (result.status()) {
                case "ok", "already_resolved" -> 200;
                case "ambiguous"              -> 409;
                case "no_data"                -> 404;
                default                       -> 500;
            };
            ctx.status(status).json(result);
        });

        app.post("/api/javdb/discovery/actresses/{id}/avatar/download", ctx -> {
            long id = parseId(ctx);
            if (id < 0) { ctx.status(400); return; }
            var result = actionService.downloadAvatarForActress(id);
            int status = switch (result.status()) {
                case "ok"         -> 200;
                case "no_profile" -> 404;
                case "no_url"     -> 409;
                default           -> 502; // "failed"
            };
            ctx.status(status).json(result);
        });

        // ── Tag-health (Phase 3 maintenance dashboard) ─────────────────────

        app.get("/api/javdb/discovery/tag-health", ctx ->
                ctx.json(service.getTagHealthReport()));

        app.post("/api/javdb/discovery/tag-health/{tagId}/surface", ctx -> {
            long tagId;
            try { tagId = Long.parseLong(ctx.pathParam("tagId")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var body = ctx.bodyAsClass(SurfaceRequest.class);
            service.setEnrichmentTagSurface(tagId, body.surface());
            ctx.status(204);
        });
    }

    private record SurfaceRequest(boolean surface) {}
    private record MoveRequest(String action) {}

    private long parseId(io.javalin.http.Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses the surfacing filter from query params. Recognised:
     * - {@code tags=Big Tits,Solowork} (comma-separated, AND semantics)
     * - {@code minRatingAvg=4.2}
     * - {@code minRatingCount=50}
     * Missing/blank params are treated as "no filter on that axis".
     */
    private JavdbDiscoveryService.TitleFilter parseFilter(io.javalin.http.Context ctx) {
        String tagsParam = ctx.queryParam("tags");
        java.util.List<String> tags = (tagsParam == null || tagsParam.isBlank())
                ? java.util.List.of()
                : java.util.Arrays.stream(tagsParam.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        Double minAvg = parseDoubleOrNull(ctx.queryParam("minRatingAvg"));
        Integer minCnt = parseIntOrNull(ctx.queryParam("minRatingCount"));
        return new JavdbDiscoveryService.TitleFilter(tags, minAvg, minCnt);
    }

    private long parseItemId(io.javalin.http.Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("itemId"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private record PauseRequest(boolean paused) {}
}
