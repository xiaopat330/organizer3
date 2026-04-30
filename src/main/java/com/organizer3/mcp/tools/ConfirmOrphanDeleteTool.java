package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * User-confirmed delete of an enriched orphan title.
 *
 * <p>Verifies the queue row is open with reason='orphan_enriched', then:
 * <ol>
 *   <li>Deletes the title via {@link TitleRepository#deleteOne} — same 4A cascade + history
 *       snapshot as sync-prune, but bypasses the catastrophic guard.</li>
 *   <li>Resolves the queue row with resolution='confirmed_delete'.</li>
 * </ol>
 */
@Slf4j
public class ConfirmOrphanDeleteTool implements Tool {

    private static final String REASON = "orphan_enriched";

    private final TitleRepository titleRepo;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;

    public ConfirmOrphanDeleteTool(TitleRepository titleRepo,
                                   EnrichmentReviewQueueRepository reviewQueueRepo) {
        this.titleRepo      = titleRepo;
        this.reviewQueueRepo = reviewQueueRepo;
    }

    @Override public String name() { return "confirm_orphan_delete"; }
    @Override public String description() {
        return "Confirm the deletion of an enriched-orphan title that was flagged by sync. "
             + "Verifies the queue row is open with reason='orphan_enriched', deletes the title "
             + "(cascade + history snapshot), and resolves the queue row as 'confirmed_delete'. "
             + "Mutation-gated.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("queue_row_id", "integer", "Row id from the enrichment_review_queue.")
                .require("queue_row_id")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long queueRowId = Schemas.requireLong(args, "queue_row_id");

        EnrichmentReviewQueueRepository.OpenRow row = reviewQueueRepo.findOpenById(queueRowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Queue row " + queueRowId + " not found or already resolved"));

        if (!REASON.equals(row.reason())) {
            throw new IllegalArgumentException(
                    "Queue row " + queueRowId + " has reason='" + row.reason()
                    + "'; confirm_orphan_delete only handles reason='" + REASON + "'");
        }

        long titleId = row.titleId();
        String titleCode = row.titleCode();

        titleRepo.findById(titleId).orElseThrow(
                () -> new IllegalArgumentException("Title id " + titleId + " not found"));

        log.info("MCP confirm_orphan_delete: deleting title id={} code='{}' via queue row {}",
                titleId, titleCode, queueRowId);

        // Resolve the queue row first so the resolution is recorded even if deleteOne
        // throws. deleteOne does not touch enrichment_review_queue rows.
        reviewQueueRepo.resolveOne(queueRowId, "confirmed_delete");
        titleRepo.deleteOne(titleId);

        log.info("MCP confirm_orphan_delete: deleted title id={} code='{}', queue row {} resolved",
                titleId, titleCode, queueRowId);

        return new Result(true,
                "Title id=" + titleId + " code='" + titleCode + "' deleted; queue row "
                + queueRowId + " resolved as 'confirmed_delete'");
    }

    public record Result(boolean ok, String message) {}
}
