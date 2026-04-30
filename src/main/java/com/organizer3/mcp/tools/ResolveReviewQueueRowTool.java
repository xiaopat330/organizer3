package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.Set;

/**
 * Resolves one open enrichment review queue row.
 *
 * <p>MVP allowlist: {@code accepted_gap} and {@code marked_resolved} only.
 * {@code manual_slug} is intentionally excluded until force-enrich (Wave 3B) lands.
 */
public class ResolveReviewQueueRowTool implements Tool {

    private static final Set<String> ALLOWED_RESOLUTIONS = Set.of("accepted_gap", "marked_resolved");

    private final EnrichmentReviewQueueRepository repo;

    public ResolveReviewQueueRowTool(EnrichmentReviewQueueRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "resolve_review_queue_row"; }
    @Override public String description() {
        return "Resolve one open enrichment review queue row. "
             + "Allowed resolution values: accepted_gap | marked_resolved. "
             + "Returns ok=false if the row does not exist or is already resolved.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("id",         "integer", "Row id from list_review_queue.")
                .prop("resolution", "string",  "accepted_gap | marked_resolved")
                .require("id", "resolution")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long   id         = Schemas.requireLong(args, "id");
        String resolution = Schemas.requireString(args, "resolution").trim();

        if (!ALLOWED_RESOLUTIONS.contains(resolution)) {
            throw new IllegalArgumentException(
                    "resolution must be one of " + ALLOWED_RESOLUTIONS + " — got: " + resolution);
        }

        boolean ok = repo.resolveOne(id, resolution);
        String message = ok
                ? "Row " + id + " resolved as '" + resolution + "'"
                : "Row " + id + " was not found or is already resolved";
        return new Result(ok, message);
    }

    public record Result(boolean ok, String message) {}
}
