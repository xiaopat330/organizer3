package com.organizer3.web.routes;

import com.organizer3.ollama.OllamaModelOrchestrator;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Exposes AI-assist Ollama queue depth for the status-bar progress widget.
 *
 * <ul>
 *   <li>{@code GET /api/enrichment/assist/queue} — returns {@code { inFlight, queued }}</li>
 * </ul>
 */
@Slf4j
public class EnrichmentAssistQueueRoutes {

    private final OllamaModelOrchestrator orchestrator;

    public EnrichmentAssistQueueRoutes(OllamaModelOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public void register(Javalin app) {
        // GET /api/enrichment/assist/queue
        app.get("/api/enrichment/assist/queue", ctx -> {
            OllamaModelOrchestrator.QueueDepths depths = orchestrator.getQueueDepths();
            ctx.json(Map.of(
                    "inFlight", depths.inFlight(),
                    "queued",   depths.queued()));
        });
    }
}
