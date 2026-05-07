package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.translation.TitleTranslationSweeper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forces an immediate sweep of titles awaiting English translation.
 * Same dedup as the scheduled tick — cannot double-enqueue.
 *
 * <p>Gated on {@code mcp.allowMutations}.
 */
public class SweepTitleTranslationBacklogTool implements Tool {

    private static final int DEFAULT_LIMIT = 5000;
    private static final int MAX_LIMIT     = 10000;

    private final TitleTranslationSweeper sweeper;
    private final JavdbEnrichmentRepository enrichmentRepo;

    public SweepTitleTranslationBacklogTool(TitleTranslationSweeper sweeper,
                                            JavdbEnrichmentRepository enrichmentRepo) {
        this.sweeper        = sweeper;
        this.enrichmentRepo = enrichmentRepo;
    }

    @Override public String name()        { return "sweep_title_translation_backlog"; }
    @Override public String description() {
        return "Force one sweep tick of the title-translation sweeper. Submits up to `limit` "
             + "titles whose title_original_en is empty. Same dedup as the scheduled tick. "
             + "Gated on mcp.allowMutations.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Max titles to submit this tick (1–10000, default 5000).", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        int submitted = sweeper.sweepOnce(limit);
        long remaining = enrichmentRepo.countTitlesAwaitingTranslation();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", submitted);
        out.put("remaining", remaining);
        out.put("limit", limit);
        return out;
    }
}
