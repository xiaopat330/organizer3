package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.javdb.enrichment.NoMatchTriageService;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.FolderInfo;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * HTTP routes for the no-match enrichment triage UI.
 *
 * <pre>
 * GET  /api/triage/no-match              → list (filtered by ?actressId=N or ?orphan=1)
 * POST /api/triage/no-match/:id/reassign → body: { actressId } → tryOtherActress
 * POST /api/triage/no-match/:id/manual   → body: { javdbSlug } → manualSlugEntry
 * POST /api/triage/no-match/:id/resolve  → markResolved
 * GET  /api/triage/no-match/:id/folder   → openFolder
 * </pre>
 *
 * <p>{@code :id} is the {@code title_id}; 400 is returned on non-numeric input.
 */
@Slf4j
@RequiredArgsConstructor
public final class NoMatchTriageRoutes {

    private final NoMatchTriageService service;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public void register(Javalin app) {

        // GET /api/triage/no-match
        app.get("/api/triage/no-match", ctx -> {
            Long actressId = null;
            String actressIdParam = ctx.queryParam("actressId");
            if (actressIdParam != null && !actressIdParam.isBlank()) {
                try {
                    actressId = Long.parseLong(actressIdParam);
                } catch (NumberFormatException e) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "actressId must be a long integer"));
                    return;
                }
            }
            boolean orphanOnly = "1".equals(ctx.queryParam("orphan"))
                    || "true".equals(ctx.queryParam("orphan"));
            ctx.json(service.list(actressId, orphanOnly));
        });

        // POST /api/triage/no-match/:id/reassign
        app.post("/api/triage/no-match/{id}/reassign", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "id must be a long integer"));
                return;
            }
            Map<?, ?> body = json.readValue(ctx.body(), Map.class);
            Object actressIdRaw = body.get("actressId");
            if (actressIdRaw == null) {
                ctx.status(400);
                ctx.json(Map.of("error", "actressId is required"));
                return;
            }
            long actressId;
            try {
                actressId = ((Number) actressIdRaw).longValue();
            } catch (ClassCastException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "actressId must be a number"));
                return;
            }
            try {
                service.tryOtherActress(titleId, actressId);
                ctx.status(204);
            } catch (IllegalArgumentException e) {
                log.warn("no-match triage reassign failed for title {}: {}", titleId, e.getMessage());
                ctx.status(400);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/triage/no-match/:id/manual
        app.post("/api/triage/no-match/{id}/manual", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "id must be a long integer"));
                return;
            }
            Map<?, ?> body = json.readValue(ctx.body(), Map.class);
            Object slugRaw = body.get("javdbSlug");
            if (slugRaw == null || slugRaw.toString().isBlank()) {
                ctx.status(400);
                ctx.json(Map.of("error", "javdbSlug is required"));
                return;
            }
            String javdbSlug = slugRaw.toString().trim();
            try {
                service.manualSlugEntry(titleId, javdbSlug);
                ctx.status(204);
            } catch (IllegalArgumentException e) {
                log.warn("no-match triage manual entry failed for title {}: {}", titleId, e.getMessage());
                ctx.status(400);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/triage/no-match/:id/resolve
        app.post("/api/triage/no-match/{id}/resolve", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "id must be a long integer"));
                return;
            }
            try {
                service.markResolved(titleId);
                ctx.status(204);
            } catch (IllegalArgumentException e) {
                log.warn("no-match triage resolve failed for title {}: {}", titleId, e.getMessage());
                ctx.status(404);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/triage/no-match/:id/folder
        app.get("/api/triage/no-match/{id}/folder", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "id must be a long integer"));
                return;
            }
            Optional<FolderInfo> info = service.openFolder(titleId);
            info.ifPresentOrElse(
                    fi -> ctx.json(Map.of("path", fi.path(), "volumeId", fi.volumeId())),
                    () -> {
                        ctx.status(404);
                        ctx.json(Map.of("error", "no location found for title " + titleId));
                    });
        });
    }
}
