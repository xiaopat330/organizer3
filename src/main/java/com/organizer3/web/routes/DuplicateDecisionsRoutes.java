package com.organizer3.web.routes;

import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.DuplicateDecisionRepository;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * REST surface for Duplicate Triage decisions.
 *
 * <ul>
 *   <li>{@code GET  /api/tools/duplicates/decisions}                      — all pending decisions</li>
 *   <li>{@code PUT  /api/tools/duplicates/decisions}                      — upsert one decision</li>
 *   <li>{@code DELETE /api/tools/duplicates/decisions/{titleCode}/{volumeId}?nasPath=} — remove one</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DuplicateDecisionsRoutes {

    private static final Set<String> VALID_DECISIONS = Set.of("KEEP", "TRASH", "VARIANT");

    private final DuplicateDecisionRepository repo;

    public void register(Javalin app) {

        app.get("/api/tools/duplicates/decisions", ctx -> {
            try {
                ctx.json(repo.listPending());
            } catch (Exception e) {
                log.error("Failed to list duplicate decisions", e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        app.put("/api/tools/duplicates/decisions", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            if (body == null) {
                ctx.status(400);
                ctx.json(Map.of("error", "request body is required"));
                return;
            }

            String titleCode = asString(body, "titleCode");
            String volumeId  = asString(body, "volumeId");
            String nasPath   = asString(body, "nasPath");
            String decision  = asString(body, "decision");

            if (titleCode == null || volumeId == null || nasPath == null || decision == null) {
                ctx.status(400);
                ctx.json(Map.of("error", "titleCode, volumeId, nasPath, and decision are required"));
                return;
            }
            if (!VALID_DECISIONS.contains(decision)) {
                ctx.status(400);
                ctx.json(Map.of("error", "decision must be KEEP, TRASH, or VARIANT"));
                return;
            }

            try {
                repo.upsert(DuplicateDecision.builder()
                        .titleCode(titleCode)
                        .volumeId(volumeId)
                        .nasPath(nasPath)
                        .decision(decision)
                        .createdAt(Instant.now().toString())
                        .build());
                ctx.status(204);
            } catch (Exception e) {
                log.error("Failed to upsert duplicate decision for {} @ {}", titleCode, nasPath, e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        app.delete("/api/tools/duplicates/decisions/{titleCode}/{volumeId}", ctx -> {
            String titleCode = ctx.pathParam("titleCode");
            String volumeId  = ctx.pathParam("volumeId");
            String nasPath   = ctx.queryParam("nasPath");

            if (nasPath == null || nasPath.isBlank()) {
                ctx.status(400);
                ctx.json(Map.of("error", "nasPath query param is required"));
                return;
            }

            try {
                repo.delete(titleCode, volumeId, nasPath);
                ctx.status(204);
            } catch (Exception e) {
                log.error("Failed to delete duplicate decision for {} @ {}", titleCode, nasPath, e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });
    }

    private static String asString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return (v instanceof String s) ? s : null;
    }
}
