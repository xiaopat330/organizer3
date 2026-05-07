package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.translation.HealthStatus;
import com.organizer3.translation.OllamaModelState;
import com.organizer3.translation.TranslationConfig;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.TranslationServiceStats;
import com.organizer3.translation.repository.TranslationCacheRepository;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only translation subsystem stats. Mirrors the dashboard's
 * {@code /api/translation/stats} payload plus title-sweeper status.
 */
public class GetTranslationStatsTool implements Tool {

    private final TranslationService service;
    private final TranslationCacheRepository cacheRepo;
    private final JavdbEnrichmentRepository enrichmentRepo;
    private final TranslationConfig config;
    private final OllamaModelState ollamaModelState;

    public GetTranslationStatsTool(TranslationService service,
                                   TranslationCacheRepository cacheRepo,
                                   JavdbEnrichmentRepository enrichmentRepo,
                                   TranslationConfig config,
                                   OllamaModelState ollamaModelState) {
        this.service          = service;
        this.cacheRepo        = cacheRepo;
        this.enrichmentRepo   = enrichmentRepo;
        this.config           = config;
        this.ollamaModelState = ollamaModelState;
    }

    @Override public String name()        { return "get_translation_stats"; }
    @Override public String description() {
        return "Translation subsystem snapshot: cache totals, lookup hit-rate, queue depths, "
             + "throughput, titles awaiting translation, sweeper config, and Ollama health.";
    }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) {
        TranslationServiceStats s = service.stats();
        long throughputLastHour = cacheRepo.recentThroughputCount(Duration.ofHours(1));
        long titlesAwaiting = enrichmentRepo.countTitlesAwaitingTranslation();
        HealthStatus health = service.getHealth();
        long lookups = s.cacheLookupHits() + s.cacheLookupMisses();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cacheTotal",                       s.cacheTotal());
        out.put("cacheSuccessful",                  s.cacheSuccessful());
        out.put("cacheFailed",                      s.cacheFailed());
        out.put("cacheFailedSanitized",             s.cacheFailedSanitized());
        out.put("cacheFailedSanitizedBothTiers",    s.cacheFailedSanitizedBothTiers());
        out.put("cacheFailedUnreachable",           s.cacheFailedUnreachable());
        out.put("cacheFailedRefused",               s.cacheFailedRefused());
        out.put("queuePending",                     s.queuePending());
        out.put("queueInFlight",                    s.queueInFlight());
        out.put("queueDone",                        s.queueDone());
        out.put("queueFailed",                      s.queueFailed());
        out.put("queueTier2Pending",                s.queueTier2Pending());
        out.put("throughputLastHour",               throughputLastHour);
        out.put("cacheLookupHits",                  s.cacheLookupHits());
        out.put("cacheLookupMisses",                s.cacheLookupMisses());
        out.put("cacheHitRate",                     lookups == 0 ? null : ((double) s.cacheLookupHits()) / lookups);
        out.put("stageNameLookupSize",              s.stageNameLookupSize());
        out.put("stageNameSuggestionsUnreviewed",   s.stageNameSuggestionsUnreviewed());

        Map<String, Object> sweeper = new LinkedHashMap<>();
        sweeper.put("titlesAwaitingTranslation",    titlesAwaiting);
        sweeper.put("enabled",                      config.titleSweeperEnabledOrDefault());
        sweeper.put("intervalSeconds",              config.titleSweeperIntervalSecondsOrDefault());
        sweeper.put("batchSize",                    config.titleSweeperBatchSizeOrDefault());
        out.put("titleSweeper", sweeper);

        Map<String, Object> healthOut = new LinkedHashMap<>();
        healthOut.put("ollamaReachable",   health.ollamaReachable());
        healthOut.put("tier1ModelPresent", health.tier1ModelPresent());
        healthOut.put("overall",           health.overall());
        healthOut.put("latencyP95Ms",      health.latencyP95Ms());
        healthOut.put("message",           health.message());
        healthOut.put("currentModelId",    ollamaModelState.getCurrentModelId());
        out.put("health", healthOut);
        return out;
    }
}
