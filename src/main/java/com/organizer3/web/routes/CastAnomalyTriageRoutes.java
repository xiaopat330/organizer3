package com.organizer3.web.routes;

import com.organizer3.javdb.enrichment.CastAnomalyTriageService;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * HTTP routes for the cast-anomaly review-queue triage inline action.
 *
 * <pre>
 * POST /api/triage/cast-anomaly/:queueId/add-alias
 *   body: { "actressId": 88, "aliasName": "黒木麻衣" }
 *   200:  { "alias_inserted": true, "rows_recovered": 12 }
 *   400:  { "error": "..." }  — validation failures
 *   404:  { "error": "..." }  — queue row not found / already resolved
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public final class CastAnomalyTriageRoutes {

    private final CastAnomalyTriageService service;

    public void register(Javalin app) {

        // POST /api/triage/cast-anomaly/:queueId/add-alias
        app.post("/api/triage/cast-anomaly/{queueId}/add-alias", ctx -> {
            long queueRowId;
            try {
                queueRowId = Long.parseLong(ctx.pathParam("queueId"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "queueId must be a long integer"));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            if (body == null) {
                ctx.status(400);
                ctx.json(Map.of("error", "request body is required"));
                return;
            }

            if (!(body.get("actressId") instanceof Number actressIdNum)) {
                ctx.status(400);
                ctx.json(Map.of("error", "actressId is required and must be a number"));
                return;
            }
            long actressId = actressIdNum.longValue();

            if (!(body.get("aliasName") instanceof String aliasName)) {
                ctx.status(400);
                ctx.json(Map.of("error", "aliasName is required"));
                return;
            }

            try {
                CastAnomalyTriageService.AddAliasResult result =
                        service.addAlias(queueRowId, actressId, aliasName);
                ctx.json(Map.of(
                        "alias_inserted", result.aliasInserted(),
                        "rows_recovered", result.rowsRecovered()
                ));
            } catch (CastAnomalyTriageService.NotFoundException e) {
                ctx.status(404);
                ctx.json(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                ctx.status(400);
                ctx.json(Map.of("error", e.getMessage()));
            } catch (Exception e) {
                log.error("cast-anomaly triage: unexpected error for queue row {}", queueRowId, e);
                ctx.status(500);
                ctx.json(Map.of("error", "internal error: " + e.getMessage()));
            }
        });
    }
}
