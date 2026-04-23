package com.organizer3.web.routes;

import com.organizer3.repository.MergeCandidateRepository;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * REST surface for Duplicate Triage — Merge Candidates.
 *
 * <ul>
 *   <li>{@code GET  /api/tools/merge-candidates}           — all undecided candidates</li>
 *   <li>{@code PUT  /api/tools/merge-candidates/{id}/decision} — record MERGE or DISMISS</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class MergeCandidatesRoutes {

    private static final Set<String> VALID_DECISIONS = Set.of("MERGE", "DISMISS");

    private final MergeCandidateRepository repo;

    public void register(Javalin app) {

        app.get("/api/tools/merge-candidates", ctx -> {
            try {
                ctx.json(repo.listPending());
            } catch (Exception e) {
                log.error("Failed to list merge candidates", e);
                ctx.status(500);
                ctx.json(Map.of("error", e.getMessage()));
            }
        });

        app.put("/api/tools/merge-candidates/{id}/decision", ctx -> {
            long id;
            try {
                id = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "id must be a number"));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            if (body == null) {
                ctx.status(400);
                ctx.json(Map.of("error", "request body is required"));
                return;
            }

            String decision   = asString(body, "decision");
            String winnerCode = asString(body, "winnerCode");

            if (decision == null || !VALID_DECISIONS.contains(decision)) {
                ctx.status(400);
                ctx.json(Map.of("error", "decision must be MERGE or DISMISS"));
                return;
            }
            if ("MERGE".equals(decision) && (winnerCode == null || winnerCode.isBlank())) {
                ctx.status(400);
                ctx.json(Map.of("error", "winnerCode is required when decision is MERGE"));
                return;
            }

            try {
                repo.decide(id, decision, "MERGE".equals(decision) ? winnerCode : null,
                        Instant.now().toString());
                ctx.status(204);
            } catch (Exception e) {
                log.error("Failed to record merge decision for candidate {}", id, e);
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
