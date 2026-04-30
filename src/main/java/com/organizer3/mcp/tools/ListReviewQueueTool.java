package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.List;
import java.util.Map;

/**
 * Lists open enrichment review queue rows, optionally filtered by reason.
 * Always returns per-reason counts alongside the row list.
 */
public class ListReviewQueueTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT     = 500;

    private final EnrichmentReviewQueueRepository repo;

    public ListReviewQueueTool(EnrichmentReviewQueueRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "list_review_queue"; }
    @Override public String description() {
        return "List open enrichment review queue rows. Returns per-reason counts (always) "
             + "and the row list (optionally filtered by reason). Rows are ordered newest first.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("reason", "string",  "Filter to one reason: cast_anomaly | ambiguous | no_match | fetch_failed. Omit for all.")
                .prop("limit",  "integer", "Max rows to return. Default 100, max 500.", DEFAULT_LIMIT)
                .prop("offset", "integer", "Zero-based row offset. Default 0.", 0)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String reason = Schemas.optString(args, "reason", null);
        int limit  = Math.max(1, Math.min(Schemas.optInt(args, "limit",  DEFAULT_LIMIT), MAX_LIMIT));
        int offset = Math.max(0,             Schemas.optInt(args, "offset", 0));

        Map<String, Integer> counts = repo.countOpenByReason();
        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listOpen(reason, limit, offset);
        return new Result(counts, rows);
    }

    public record Result(Map<String, Integer> counts,
                         List<EnrichmentReviewQueueRepository.OpenRow> rows) {}
}
