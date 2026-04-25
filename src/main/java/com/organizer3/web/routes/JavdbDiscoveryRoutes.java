package com.organizer3.web.routes;

import com.organizer3.web.JavdbDiscoveryService;
import io.javalin.Javalin;

/**
 * All /api/javdb/discovery/* routes. Wired by {@link com.organizer3.web.WebServer}.
 */
public class JavdbDiscoveryRoutes {

    private final JavdbDiscoveryService service;

    public JavdbDiscoveryRoutes(JavdbDiscoveryService service) {
        this.service = service;
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
    }
}
