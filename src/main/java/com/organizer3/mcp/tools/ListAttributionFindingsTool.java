package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.AttributionFindingsRepository;

import java.util.List;

/**
 * List persisted actress-level attribution audit findings from the
 * {@code attribution_findings} table.
 */
public class ListAttributionFindingsTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT     = 1000;

    private final AttributionFindingsRepository repo;

    public ListAttributionFindingsTool(AttributionFindingsRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "list_attribution_findings"; }
    @Override public String description() {
        return "List persisted actress-level attribution audit findings. "
             + "Each finding aggregates the mismatch or suspect-credit signal for one actress. "
             + "Status: open | suppressed | resolved.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("status", "string", "Filter by status: open, suppressed, resolved. Omit for all.", (Object) null)
                .prop("limit", "integer", "Maximum rows. Default 100, max 1000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String status = Schemas.optString(args, "status", null);
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        int total = repo.count(status);
        List<AttributionFindingsRepository.Finding> rows = repo.list(status, limit);
        return new Result(total, rows);
    }

    public record Result(int total, List<AttributionFindingsRepository.Finding> rows) {}
}
