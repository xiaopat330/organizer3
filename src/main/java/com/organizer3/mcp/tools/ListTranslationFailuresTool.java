package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.translation.TranslationCacheRow;
import com.organizer3.translation.repository.TranslationCacheRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists recent failed translation cache rows, newest first. Read-only.
 */
public class ListTranslationFailuresTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT     = 200;

    private final TranslationCacheRepository cacheRepo;

    public ListTranslationFailuresTool(TranslationCacheRepository cacheRepo) {
        this.cacheRepo = cacheRepo;
    }

    @Override public String name()        { return "list_translation_failures"; }
    @Override public String description() {
        return "List recent failed translation cache rows (refused / sanitized / unreachable / "
             + "sanitized_both_tiers). Newest first.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Max rows to return (1–200, default 20).", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        List<TranslationCacheRow> rows = cacheRepo.findRecentFailures(limit);
        return rows.stream().map(this::toMap).toList();
    }

    private Map<String, Object> toMap(TranslationCacheRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            r.id());
        m.put("sourceText",    r.sourceText());
        m.put("englishText",   r.englishText());
        m.put("failureReason", r.failureReason());
        m.put("retryAfter",    r.retryAfter());
        m.put("latencyMs",     r.latencyMs());
        m.put("promptTokens",  r.promptTokens());
        m.put("evalTokens",    r.evalTokens());
        m.put("cachedAt",      r.cachedAt());
        return m;
    }
}
