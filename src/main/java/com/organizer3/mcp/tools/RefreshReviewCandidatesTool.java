package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.DisambiguationSnapshotter;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Re-runs the disambiguation pipeline for one review-queue row and refreshes its candidate
 * snapshot. Handles both the 31 legacy rows with {@code NULL} detail (first-time population)
 * and explicit user "Refresh candidates" clicks.
 *
 * <p>Mutation-gated.
 */
@Slf4j
public class RefreshReviewCandidatesTool implements Tool {

    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final TitleRepository titleRepo;
    private final DisambiguationSnapshotter snapshotter;

    public RefreshReviewCandidatesTool(EnrichmentReviewQueueRepository reviewQueueRepo,
                                       TitleRepository titleRepo,
                                       DisambiguationSnapshotter snapshotter) {
        this.reviewQueueRepo = reviewQueueRepo;
        this.titleRepo       = titleRepo;
        this.snapshotter     = snapshotter;
    }

    @Override public String name() { return "refresh_review_candidates"; }

    @Override public String description() {
        return "Re-fetch disambiguation candidates for an open ambiguous review-queue row and "
             + "update its detail snapshot. Use when detail is NULL (legacy rows) or stale. "
             + "Returns the fresh snapshot JSON on success.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("queue_row_id", "integer", "Id of the open ambiguous review queue row.")
                .require("queue_row_id")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws Exception {
        long queueRowId = Schemas.requireLong(args, "queue_row_id");

        EnrichmentReviewQueueRepository.OpenRow row = reviewQueueRepo.findOpenById(queueRowId)
                .orElse(null);
        if (row == null) {
            return Result.err("row_not_found",
                    "Queue row " + queueRowId + " not found or already resolved");
        }

        var maybeTitle = titleRepo.findById(row.titleId());
        if (maybeTitle.isEmpty()) {
            return Result.err("title_not_found",
                    "Title " + row.titleId() + " (for queue row " + queueRowId + ") not found");
        }
        String productCode = maybeTitle.get().getCode();
        // Strip variant suffixes (e.g. "SONE-038_4K" → "SONE-038") matching EnrichmentRunner.lookupCode.
        String lookupCode  = productCode.replaceFirst("(?i)(^[A-Za-z]+-\\d+)[-_].+$", "$1");

        String detailJson = snapshotter.buildSnapshot(row.titleId(), lookupCode, row.slug(), null);
        if (detailJson == null) {
            return Result.err("snapshot_build_failed",
                    "Failed to build candidate snapshot for " + productCode + " — check logs");
        }

        reviewQueueRepo.updateDetail(queueRowId, detailJson);
        log.info("refresh_review_candidates: updated snapshot for queue row {} (title={})", queueRowId, productCode);
        return Result.ok(detailJson, "Snapshot refreshed for " + productCode);
    }

    public record Result(boolean ok, String error, String detailJson, String message) {
        static Result ok(String detailJson, String message) {
            return new Result(true, null, detailJson, message);
        }
        static Result err(String error, String message) {
            return new Result(false, error, null, message);
        }
    }
}
