package com.organizer3.web.routes;

import com.organizer3.web.TitleDiscoveryService;
import io.javalin.Javalin;

import java.util.List;

/**
 * Read + bulk-enqueue endpoints backing the JavDB Discovery "Titles" tab.
 * Mounted under {@code /api/javdb/discovery/titles}.
 */
public class TitleDiscoveryRoutes {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final TitleDiscoveryService service;

    public TitleDiscoveryRoutes(TitleDiscoveryService service) {
        this.service = service;
    }

    public void register(Javalin app) {
        app.get("/api/javdb/discovery/titles", ctx -> {
            String source   = ctx.queryParam("source");
            String volumeId = ctx.queryParam("volumeId");
            int page     = parseInt(ctx.queryParam("page"),     0);
            int pageSize = clamp(parseInt(ctx.queryParam("pageSize"), DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);

            if ("recent".equals(source)) {
                ctx.json(service.listRecent(page, pageSize));
            } else if ("pool".equals(source)) {
                if (volumeId == null || volumeId.isBlank()) {
                    ctx.status(400);
                    ctx.json(java.util.Map.of("error", "volumeId is required when source=pool"));
                    return;
                }
                ctx.json(service.listPool(volumeId, page, pageSize));
            } else {
                ctx.status(400);
                ctx.json(java.util.Map.of("error", "source must be 'recent' or 'pool'"));
            }
        });

        app.get("/api/javdb/discovery/titles/pools", ctx ->
                ctx.json(service.listPools()));

        app.post("/api/javdb/discovery/titles/enqueue", ctx -> {
            EnqueueRequest body = ctx.bodyAsClass(EnqueueRequest.class);
            if (body == null || body.source() == null || body.titleIds() == null) {
                ctx.status(400);
                ctx.json(java.util.Map.of("error", "source and titleIds are required"));
                return;
            }
            try {
                int n = service.enqueue(body.source(), body.titleIds());
                ctx.json(java.util.Map.of("enqueued", n));
            } catch (IllegalArgumentException e) {
                ctx.status(400);
                ctx.json(java.util.Map.of("error", e.getMessage()));
            }
        });
    }

    public record EnqueueRequest(String source, List<Long> titleIds) {}

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
