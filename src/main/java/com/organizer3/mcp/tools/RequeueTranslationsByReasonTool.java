package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.translation.TranslationService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Re-queues failed translations by failure reason. Deletes matching cache + queue rows
 * so upstream sweepers re-enqueue them on the next tick.
 *
 * <p>Gated on {@code mcp.allowMutations}.
 */
public class RequeueTranslationsByReasonTool implements Tool {

    private static final Set<String> ALLOWED = Set.of(
            "sanitized", "sanitized_both_tiers", "unreachable", "refused");

    private final TranslationService service;

    public RequeueTranslationsByReasonTool(TranslationService service) {
        this.service = service;
    }

    @Override public String name()        { return "requeue_translations_by_reason"; }
    @Override public String description() {
        return "Delete cached failures (and their queue rows) for one of: sanitized, "
             + "sanitized_both_tiers, unreachable, refused. The title sweeper re-enqueues "
             + "them on its next tick. Gated on mcp.allowMutations.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("reason", "string", "One of: sanitized, sanitized_both_tiers, unreachable, refused.")
                .require("reason")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String reason = Schemas.requireString(args, "reason");
        if (!ALLOWED.contains(reason)) {
            throw new IllegalArgumentException(
                    "reason must be one of: " + String.join(", ", ALLOWED));
        }
        int requeued = service.requeueFailedByReason(reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", reason);
        out.put("requeued", requeued);
        return out;
    }
}
