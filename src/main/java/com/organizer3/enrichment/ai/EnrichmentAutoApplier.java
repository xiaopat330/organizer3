package com.organizer3.enrichment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.mcp.tools.PickReviewCandidateTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 auto-apply executor: given an eligible {@link OpenRow} whose AI suggestion is
 * {@code confidence='agreed'}, invokes the same {@link PickReviewCandidateTool} call the
 * human picker uses, and on success marks {@code ai_auto_applied=1}.
 *
 * <p>Caller is expected to have filtered rows via
 * {@link EnrichmentReviewQueueRepository#listAutoApplyReady}; the validation here is
 * defensive only.
 */
public class EnrichmentAutoApplier {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichmentAutoApplier.class);

    private final EnrichmentReviewQueueRepository queueRepo;
    private final PickReviewCandidateTool pickTool;
    private final ObjectMapper objectMapper;

    public EnrichmentAutoApplier(EnrichmentReviewQueueRepository queueRepo,
                                 PickReviewCandidateTool pickTool,
                                 ObjectMapper objectMapper) {
        this.queueRepo    = queueRepo;
        this.pickTool     = pickTool;
        this.objectMapper = objectMapper;
    }

    /**
     * Apply an AI-suggested pick to an eligible queue row.
     *
     * @return true if the pick was applied and the row was marked; false otherwise
     *         (defensive validation failed or the pick threw).
     */
    public boolean apply(OpenRow row) {
        if (!"agreed".equals(row.aiSuggestionConfidence())) {
            LOG.debug("[ai-assist] skip auto-apply (not agreed) id={}", row.id());
            return false;
        }
        String slug = row.aiSuggestionSlug();
        if (slug == null || slug.isBlank()) {
            LOG.debug("[ai-assist] skip auto-apply (no slug) id={}", row.id());
            return false;
        }

        String codeLabel = row.titleCode() != null ? row.titleCode() : ("id=" + row.id());

        ObjectNode args = objectMapper.createObjectNode();
        args.put("queue_row_id", row.id());
        args.put("slug",         slug);

        try {
            pickTool.call(args);
        } catch (Exception e) {
            queueRepo.incrementAutoApplyAttempts(row.id());
            LOG.warn("[ai-assist] auto-apply failed code={}: {}", codeLabel, e.getMessage());
            return false;
        }

        queueRepo.markAiAutoApplied(row.id());
        LOG.info("[ai-assist] auto-applied code={} slug={}", codeLabel, slug);
        return true;
    }
}
