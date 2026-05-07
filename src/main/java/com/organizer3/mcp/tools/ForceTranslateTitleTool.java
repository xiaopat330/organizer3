package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.translation.TitleTranslationSweeper;
import com.organizer3.translation.TitleTranslationSweeper.ForceTranslateResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Force a fresh re-translation for one title's {@code title_original_en}.
 * Discards any cached translation for that source text and any prior queue rows
 * for the title's translation callback, then enqueues a new request.
 *
 * <p>Use cases: substitution-map updates, sanitized result needs a fresh attempt,
 * stuck callback after a worker crash.
 *
 * <p>Gated on {@code mcp.allowMutations}.
 */
public class ForceTranslateTitleTool implements Tool {

    private final TitleTranslationSweeper sweeper;

    public ForceTranslateTitleTool(TitleTranslationSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Override public String name()        { return "force_translate_title"; }
    @Override public String description() {
        return "Force a fresh re-translation of a single title's title_original_en. "
             + "Clears the cache row + any prior queue rows for the title before re-submitting. "
             + "Gated on mcp.allowMutations.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleId", "integer", "ID of the title to re-translate.")
                .require("titleId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long titleId = Schemas.requireLong(args, "titleId");
        ForceTranslateResult r = sweeper.forceTranslateOne(titleId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("titleId",          titleId);
        out.put("submitted",        r.submitted());
        out.put("cacheRowsDeleted", r.cacheRowsDeleted());
        out.put("queueRowsDeleted", r.queueRowsDeleted());
        out.put("queueId",          r.queueId());
        if (r.reason() != null) out.put("reason", r.reason());
        return out;
    }
}
