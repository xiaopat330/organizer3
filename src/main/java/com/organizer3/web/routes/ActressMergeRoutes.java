package com.organizer3.web.routes;

import com.organizer3.mcp.tools.MergeActressesTool;
import com.organizer3.repository.ActressRepository;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.Map;

/**
 * POST /api/actresses/{id}/merge — fold actress {@code fromId} into the actress at
 * {@code id}. Body: {@code { "fromId": long, "dryRun": boolean? }} — {@code dryRun}
 * defaults to true so clients must opt into mutation.
 *
 * <p>Delegates to {@link MergeActressesTool#merge} so the merge policy (flag merging,
 * alias migration, title reassignment) is identical to the MCP tool.
 */
@Slf4j
public class ActressMergeRoutes {

    private final Jdbi jdbi;
    private final ActressRepository actressRepo;

    public ActressMergeRoutes(Jdbi jdbi, ActressRepository actressRepo) {
        this.jdbi = jdbi;
        this.actressRepo = actressRepo;
    }

    public void register(Javalin app) {
        app.post("/api/actresses/{id}/merge", ctx -> {
            long intoId;
            try { intoId = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            long fromId;
            boolean dryRun;
            try {
                var body = ctx.bodyAsClass(Map.class);
                Object rawFrom = body.get("fromId");
                if (rawFrom == null) throw new IllegalArgumentException("fromId required");
                fromId = rawFrom instanceof Number n ? n.longValue() : Long.parseLong(rawFrom.toString());
                Object rawDry = body.get("dryRun");
                // Default to dryRun=true so a malformed body never commits.
                dryRun = rawDry == null || Boolean.parseBoolean(rawDry.toString());
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid request body; expected { fromId, dryRun? }"));
                return;
            }

            log.info("ActressMerge request received — into={} from={} dryRun={}", intoId, fromId, dryRun);
            try {
                ctx.json(MergeActressesTool.merge(jdbi, actressRepo, intoId, fromId, dryRun));
            } catch (IllegalArgumentException e) {
                log.warn("ActressMerge rejected — into={} from={} reason={}", intoId, fromId, e.getMessage());
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        });
    }
}
