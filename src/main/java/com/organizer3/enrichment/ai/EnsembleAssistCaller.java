package com.organizer3.enrichment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Two-model ensemble caller for the AI picker assist (Wave 3 Track F).
 *
 * <p>Materializes a prompt from a queue row's snapshot, dispatches it to the configured
 * primary (e.g. {@code phi4}) and secondary (e.g. {@code gemma3:12b}) models through
 * {@link OllamaModelOrchestrator}, parses each model's JSON reply, and applies the
 * 5-outcome voting policy:
 *
 * <pre>
 *   phi4    gemma   outcome
 *   ------  ------  --------------
 *   i       i       agreed         (suggestedSlug = candidate[i-1])
 *   i       null    phi4_only
 *   null    j       gemma_only
 *   i       j (≠)   conflict       (suggestedSlug = null)
 *   null    null    both_abstain   (suggestedSlug = null)
 * </pre>
 *
 * <p><b>Robustness</b>: any failure of one model (HTTP error, timeout, malformed JSON,
 * out-of-range index) is treated as an abstain for that model. The other model still
 * gets consulted and the voting policy is applied normally. The only condition that
 * throws is a structurally invalid row (zero candidates after parsing) — those are
 * surfaced to the sweeper as an {@link IllegalStateException}.
 *
 * <p><b>Reachability gaps</b> from {@link EnrichmentReviewQueueRepository.OpenRow}: the
 * row carries the {@code detail} snapshot (code, linked_slugs, candidates) and the
 * {@code title_code}, but not the on-disk folder path or the linked actresses'
 * canonical romaji names. {@link AssistPromptBuilder.Input} accepts {@code null} for
 * those fields, so this caller passes nulls. A follow-up track can wire in a richer
 * context resolver if measured pick quality demands it.
 */
public class EnsembleAssistCaller {

    private static final Logger log = LoggerFactory.getLogger(EnsembleAssistCaller.class);

    /** Per-model wait budget. Matches the POC's generous timeout for cold-load + generate. */
    public static final Duration MODEL_FUTURE_TIMEOUT = Duration.ofMinutes(5);

    /** Default keep-alive hint passed to Ollama on every assist request. */
    public static final String KEEP_ALIVE_DEFAULT = "15m";

    private final OllamaModelOrchestrator orchestrator;
    private final EnrichmentAssistConfig config;
    private final ObjectMapper objectMapper;
    private final PostProcessingRules postProcessing;

    /** Test-friendly constructor — wires a disabled {@link PostProcessingRules} layer. */
    public EnsembleAssistCaller(OllamaModelOrchestrator orchestrator,
                                EnrichmentAssistConfig config,
                                ObjectMapper objectMapper) {
        this(orchestrator, config, objectMapper, new PostProcessingRules(false));
    }

    public EnsembleAssistCaller(OllamaModelOrchestrator orchestrator,
                                EnrichmentAssistConfig config,
                                ObjectMapper objectMapper,
                                PostProcessingRules postProcessing) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.config = Objects.requireNonNull(config, "config");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.postProcessing = Objects.requireNonNull(postProcessing, "postProcessing");
    }

    public AssistResult evaluate(EnrichmentReviewQueueRepository.OpenRow row) {
        return evaluate(row, null, List.of());
    }

    /**
     * Track G overload: threads {@code folderPath} and the linked actresses' canonical
     * (romaji) names into the prompt input. Both hints are passed through to
     * {@link AssistPromptBuilder.Input}, which handles {@code null} / empty gracefully.
     * The folder hint shows only the trailing path segment; the actress hint feeds the
     * kanji-bridge rule in the system prompt.
     */
    public AssistResult evaluate(EnrichmentReviewQueueRepository.OpenRow row,
                                 String folderPath,
                                 List<String> actressNames) {
        Objects.requireNonNull(row, "row");
        AssistPromptBuilder.Input rawInput = materializeInput(row, folderPath, actressNames);
        if (rawInput.candidates() == null || rawInput.candidates().isEmpty()) {
            throw new IllegalStateException(
                    "EnsembleAssistCaller: row " + row.id() + " (code=" + row.titleCode()
                            + ") has zero candidates after parsing detail snapshot");
        }

        // Phase 4 Track B — Java-side prefilter (rules 3 + 2). Empty-list safe: returns the
        // original candidate list unchanged if a rule would remove everything.
        List<AssistPromptBuilder.Input.Candidate> filtered =
                postProcessing.prefilterCandidates(rawInput, rawInput.candidates());
        AssistPromptBuilder.Input input = (filtered == rawInput.candidates())
                ? rawInput
                : new AssistPromptBuilder.Input(
                        rawInput.code(), rawInput.label(), rawInput.folderPath(),
                        rawInput.actressNames(), rawInput.linkedSlugs(), filtered);

        String userPrompt = AssistPromptBuilder.buildUserPrompt(input);
        String systemPrompt = AssistPromptBuilder.SYSTEM;

        ModelReply phi4Reply;
        ModelReply gemmaReply;
        if (shouldRunParallel()) {
            ModelReply[] replies = callBothParallel(input, row.titleCode());
            phi4Reply  = replies[0];
            gemmaReply = replies[1];
        } else {
            phi4Reply  = callModel(config.primaryModel(),   systemPrompt, userPrompt, row.titleCode(), input.candidates().size());
            gemmaReply = callModel(config.secondaryModel(), systemPrompt, userPrompt, row.titleCode(), input.candidates().size());
        }

        AssistResult preOverride = vote(
                phi4Reply.pick(),  phi4Reply.confidence(),  phi4Reply.reason(),
                gemmaReply.pick(), gemmaReply.confidence(), gemmaReply.reason(),
                input.candidates());

        // Phase 4 Track B — post-ensemble override (rule 1). Operates on the same candidate
        // list that the ensemble voted on, so slug references stay consistent.
        AssistResult result = postProcessing.applyOverrides(input, preOverride, input.candidates());

        log.info("[ai-assist] code={} outcome={} confidence={} phi4={} gemma={}",
                row.titleCode(), result.outcome(), result.confidence(),
                result.phi4Pick(), result.gemmaPick());
        return result;
    }

    // ------------------------------------------------------------------ prompt input

    AssistPromptBuilder.Input materializeInput(EnrichmentReviewQueueRepository.OpenRow row) {
        return materializeInput(objectMapper, row, null, List.of());
    }

    AssistPromptBuilder.Input materializeInput(EnrichmentReviewQueueRepository.OpenRow row,
                                               String folderPath,
                                               List<String> actressNames) {
        return materializeInput(objectMapper, row, folderPath, actressNames);
    }

    /**
     * Phase 5 Track A — static accessor reused by {@code AiAssistBackfillTask}'s
     * batched path. Equivalent to the instance helper but with the JSON mapper
     * threaded in explicitly.
     */
    public static AssistPromptBuilder.Input materializeInput(ObjectMapper objectMapper,
                                                             EnrichmentReviewQueueRepository.OpenRow row,
                                                             String folderPath,
                                                             List<String> actressNames) {
        List<String> linkedSlugs = new ArrayList<>();
        List<AssistPromptBuilder.Input.Candidate> candidates = new ArrayList<>();
        String code = row.titleCode();
        String label = parseLabelFromCode(code);

        if (row.detail() != null && !row.detail().isBlank()) {
            try {
                JsonNode snap = objectMapper.readTree(row.detail());
                JsonNode slugsNode = snap.path("linked_slugs");
                if (slugsNode.isArray()) {
                    for (JsonNode s : slugsNode) {
                        if (s.isTextual()) linkedSlugs.add(s.asText());
                    }
                }
                JsonNode candsNode = snap.path("candidates");
                if (candsNode.isArray()) {
                    for (JsonNode c : candsNode) {
                        candidates.add(toCandidate(c));
                    }
                }
                // Prefer code from snapshot if row's titleCode is null (orphan/deleted title).
                if (code == null) {
                    JsonNode codeNode = snap.path("code");
                    if (codeNode.isTextual()) code = codeNode.asText();
                }
            } catch (Exception e) {
                log.warn("[ai-assist] failed to parse detail JSON for row {} (code={}): {}",
                        row.id(), row.titleCode(), e.getMessage());
            }
        }

        // Track G: folderPath and actressNames are supplied by the sweeper via
        // EnrichmentReviewQueueRepository.findContextForAssist(titleId). Both are
        // null-safe in AssistPromptBuilder.buildUserPrompt — the hint lines are
        // simply omitted when missing.
        List<String> safeActressNames = (actressNames == null || actressNames.isEmpty())
                ? null
                : List.copyOf(actressNames);
        return new AssistPromptBuilder.Input(
                code,
                label,
                folderPath,
                safeActressNames,
                linkedSlugs,
                candidates);
    }

    private static AssistPromptBuilder.Input.Candidate toCandidate(JsonNode c) {
        List<String> castNames = new ArrayList<>();
        JsonNode castNode = c.path("cast");
        if (castNode.isArray()) {
            for (JsonNode ce : castNode) {
                JsonNode name = ce.path("name");
                if (name.isTextual()) castNames.add(name.asText());
            }
        }
        return new AssistPromptBuilder.Input.Candidate(
                textOrNull(c.path("slug")),
                textOrNull(c.path("title_original")),
                textOrNull(c.path("release_date")),
                textOrNull(c.path("maker")),
                castNames,
                intOrNull(c.path("duration_minutes")),
                doubleOrNull(c.path("rating_avg")),
                intOrNull(c.path("rating_count")));
    }

    private static String textOrNull(JsonNode n) {
        return (n == null || n.isMissingNode() || n.isNull() || !n.isTextual()) ? null : n.asText();
    }

    private static Integer intOrNull(JsonNode n) {
        return (n == null || n.isMissingNode() || n.isNull() || !n.isNumber()) ? null : n.asInt();
    }

    private static Double doubleOrNull(JsonNode n) {
        return (n == null || n.isMissingNode() || n.isNull() || !n.isNumber()) ? null : n.asDouble();
    }

    private static String parseLabelFromCode(String code) {
        if (code == null) return null;
        int dash = code.indexOf('-');
        return dash > 0 ? code.substring(0, dash) : null;
    }

    // ------------------------------------------------------------------ model dispatch

    /** Result of one model call. Any failure mode is encoded as an abstain (pick=null). */
    private record ModelReply(Integer pick, String confidence, String reason) {
        static ModelReply abstain(String reason) { return new ModelReply(null, null, reason); }
    }

    /**
     * Phase 5 Track A — public static builder reused by the batched backfill path.
     * Mirrors the request shape that {@link #callModel} constructs internally.
     */
    public static OllamaRequest buildRequest(String model,
                                             AssistPromptBuilder.Input input,
                                             boolean formatJson,
                                             String keepAlive) {
        String userPrompt = AssistPromptBuilder.buildUserPrompt(input);
        return new OllamaRequest(
                model, userPrompt, AssistPromptBuilder.SYSTEM,
                /* options */ null,
                /* timeout */ MODEL_FUTURE_TIMEOUT,
                /* formatJson */ formatJson,
                /* keepAlive  */ keepAlive);
    }

    /**
     * Phase 5 Track A — public static reply parser. Translates an Ollama response
     * body into a triple of {@code (pick, confidence, reason)}. Any failure mode
     * (null/blank body, JSON parse error, out-of-range pick) is encoded as an
     * abstain — {@code pick = null}. The {@code candidates} array shape is used
     * solely to validate the returned index.
     *
     * @return a 3-element array: {@code [Integer pick, String confidence, String reason]}
     */
    public static Object[] parseReply(ObjectMapper objectMapper, String model, String code,
                                      String raw, int numCandidates) {
        if (raw == null || raw.isBlank()) {
            log.warn("[ai-assist] model {} returned empty response for code={}", model, code);
            return new Object[]{null, null, "empty_response"};
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            Integer pick = null;
            JsonNode pickNode = node.has("pick") ? node.get("pick") : node.path("pick_slug");
            if (pickNode != null && !pickNode.isNull() && !pickNode.isMissingNode()) {
                if (pickNode.isInt() || pickNode.isLong()) {
                    pick = pickNode.asInt();
                } else if (pickNode.isTextual()) {
                    try { pick = Integer.parseInt(pickNode.asText().trim()); }
                    catch (NumberFormatException nfe) { pick = null; }
                }
            }
            if (pick != null && (pick < 1 || pick > numCandidates)) {
                log.warn("[ai-assist] model {} returned out-of-range pick={} for code={} (N={})",
                        model, pick, code, numCandidates);
                pick = null;
            }
            String confidence = textOrNull(node.path("confidence"));
            String reason     = textOrNull(node.path("reason"));
            return new Object[]{pick, confidence, reason};
        } catch (Exception e) {
            log.warn("[ai-assist] model {} JSON parse failed for code={}: {}",
                    model, code, e.getMessage());
            return new Object[]{null, null, "parse_error"};
        }
    }

    private ModelReply callModel(String model, String systemPrompt, String userPrompt,
                                 String code, int numCandidates) {
        OllamaRequest req = new OllamaRequest(
                model, userPrompt, systemPrompt,
                /* options */ null,
                /* timeout */ MODEL_FUTURE_TIMEOUT,
                /* formatJson */ true,
                /* keepAlive  */ KEEP_ALIVE_DEFAULT);

        CompletableFuture<OllamaResponse> future;
        try {
            future = orchestrator.submit(model, req);
        } catch (RuntimeException submitError) {
            log.warn("[ai-assist] model {} submission failed for code={}: {}",
                    model, code, submitError.getMessage());
            return ModelReply.abstain("submit_failed");
        }

        OllamaResponse resp;
        try {
            resp = future.get(MODEL_FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("[ai-assist] model {} timed out after {}s for code={}",
                    model, MODEL_FUTURE_TIMEOUT.toSeconds(), code);
            future.cancel(true);
            return ModelReply.abstain("timeout");
        } catch (ExecutionException ee) {
            log.warn("[ai-assist] model {} call failed for code={}: {}",
                    model, code, ee.getCause() != null ? ee.getCause().toString() : ee.getMessage());
            return ModelReply.abstain("call_failed");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[ai-assist] model {} call interrupted for code={}", model, code);
            return ModelReply.abstain("interrupted");
        }

        return parseReply(model, code, resp != null ? resp.responseText() : null, numCandidates);
    }

    // ------------------------------------------------------------------ parallel-ensemble (Phase 5 Track B)

    /**
     * Phase 5 Track B — gate check for the parallel-dispatch path. The flag must be on
     * AND Ollama's current loaded-model footprint must fit within the configured budget.
     * On memory pressure the call falls back to the serial path with a WARN log.
     */
    boolean shouldRunParallel() {
        if (!config.parallelEnsemble()) return false;
        int loadedMb = orchestrator.currentLoadedModelMb();
        if (loadedMb > config.parallelEnsembleMemoryBudgetMb()) {
            log.warn("[ai-assist] parallel disabled (loaded={}MB > budget {}MB); falling back to serial",
                    loadedMb, config.parallelEnsembleMemoryBudgetMb());
            return false;
        }
        return true;
    }

    /**
     * Phase 5 Track B — fire both model calls concurrently via
     * {@link OllamaModelOrchestrator#submitParallel}, then await both with the same
     * timeout the serial path uses. Either side failing (timeout, submit failure,
     * execution exception, parse failure) is encoded as an abstain for that model;
     * the other model's reply is still applied normally by {@link #vote}.
     *
     * @return a 2-element array {@code [phi4Reply, gemmaReply]}, never null
     */
    private ModelReply[] callBothParallel(AssistPromptBuilder.Input input, String code) {
        String primary   = config.primaryModel();
        String secondary = config.secondaryModel();
        int numCandidates = input.candidates().size();

        OllamaRequest phi4Req  = buildRequest(primary,   input, true, KEEP_ALIVE_DEFAULT);
        OllamaRequest gemmaReq = buildRequest(secondary, input, true, KEEP_ALIVE_DEFAULT);

        CompletableFuture<OllamaResponse> phi4F;
        CompletableFuture<OllamaResponse> gemmaF;
        try {
            phi4F  = orchestrator.submitParallel(primary,   phi4Req);
            gemmaF = orchestrator.submitParallel(secondary, gemmaReq);
        } catch (RuntimeException submitError) {
            log.warn("[ai-assist] parallel submission failed for code={}: {}", code, submitError.getMessage());
            return new ModelReply[]{ ModelReply.abstain("submit_failed"), ModelReply.abstain("submit_failed") };
        }

        ModelReply phi4Reply  = awaitReply(phi4F,  primary,   code, numCandidates);
        ModelReply gemmaReply = awaitReply(gemmaF, secondary, code, numCandidates);
        return new ModelReply[]{ phi4Reply, gemmaReply };
    }

    private ModelReply awaitReply(CompletableFuture<OllamaResponse> future,
                                  String model, String code, int numCandidates) {
        OllamaResponse resp;
        try {
            resp = future.get(MODEL_FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("[ai-assist] model {} timed out after {}s for code={} (parallel)",
                    model, MODEL_FUTURE_TIMEOUT.toSeconds(), code);
            future.cancel(true);
            return ModelReply.abstain("timeout");
        } catch (ExecutionException ee) {
            log.warn("[ai-assist] model {} call failed for code={} (parallel): {}",
                    model, code, ee.getCause() != null ? ee.getCause().toString() : ee.getMessage());
            return ModelReply.abstain("call_failed");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[ai-assist] model {} call interrupted for code={} (parallel)", model, code);
            return ModelReply.abstain("interrupted");
        }
        return parseReply(model, code, resp != null ? resp.responseText() : null, numCandidates);
    }

    private ModelReply parseReply(String model, String code, String raw, int numCandidates) {
        if (raw == null || raw.isBlank()) {
            log.warn("[ai-assist] model {} returned empty response for code={}", model, code);
            return ModelReply.abstain("empty_response");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            Integer pick = null;
            JsonNode pickNode = node.has("pick") ? node.get("pick") : node.path("pick_slug");
            if (pickNode != null && !pickNode.isNull() && !pickNode.isMissingNode()) {
                if (pickNode.isInt() || pickNode.isLong()) {
                    pick = pickNode.asInt();
                } else if (pickNode.isTextual()) {
                    try { pick = Integer.parseInt(pickNode.asText().trim()); }
                    catch (NumberFormatException nfe) { pick = null; }
                }
            }
            // Validate range — out of [1, N] becomes abstain.
            if (pick != null && (pick < 1 || pick > numCandidates)) {
                log.warn("[ai-assist] model {} returned out-of-range pick={} for code={} (N={})",
                        model, pick, code, numCandidates);
                pick = null;
            }
            String confidence = textOrNull(node.path("confidence"));
            String reason = textOrNull(node.path("reason"));
            return new ModelReply(pick, confidence, reason);
        } catch (Exception e) {
            log.warn("[ai-assist] model {} JSON parse failed for code={}: {}",
                    model, code, e.getMessage());
            return ModelReply.abstain("parse_error");
        }
    }

    // ------------------------------------------------------------------ voting

    /**
     * Phase 5 Track A — public static voting helper extracted from {@code evaluate()}.
     * Backfill's batched path calls this directly after collecting both models' replies
     * for a chunk; {@code evaluate()} delegates here per-row. Any failure mode of a
     * model is encoded as a {@code null} pick (abstain).
     *
     * @param phi4Pick        primary model's 1-based candidate index, or {@code null} for abstain
     * @param phi4Confidence  primary model's reported confidence (high|medium|low or null)
     * @param phi4Reason      primary model's reason text
     * @param gemmaPick       secondary model's 1-based candidate index, or {@code null} for abstain
     * @param gemmaConfidence secondary model's reported confidence
     * @param gemmaReason     secondary model's reason text
     * @param candidates      the candidate list the picks index into (the prefiltered list,
     *                        so slug references stay consistent)
     * @return an {@link AssistResult} with one of the 5 outcomes (or
     *         {@code agreed_with_override} only after post-processing runs)
     */
    public static AssistResult vote(
            Integer phi4Pick, String phi4Confidence, String phi4Reason,
            Integer gemmaPick, String gemmaConfidence, String gemmaReason,
            List<AssistPromptBuilder.Input.Candidate> candidates) {

        // both abstained
        if (phi4Pick == null && gemmaPick == null) {
            return new AssistResult("both_abstain", null, null, "both abstained", null, null);
        }
        // phi4 only
        if (phi4Pick != null && gemmaPick == null) {
            return new AssistResult("phi4_only", phi4Confidence,
                    candidates.get(phi4Pick - 1).slug(),
                    firstNonBlank(phi4Reason, gemmaReason, "phi4 only"),
                    phi4Pick, null);
        }
        // gemma only
        if (phi4Pick == null) {
            return new AssistResult("gemma_only", gemmaConfidence,
                    candidates.get(gemmaPick - 1).slug(),
                    firstNonBlank(gemmaReason, phi4Reason, "gemma only"),
                    null, gemmaPick);
        }
        // both picked
        if (phi4Pick.equals(gemmaPick)) {
            String agreedConfidence = minConfidence(phi4Confidence, gemmaConfidence);
            return new AssistResult("agreed", agreedConfidence,
                    candidates.get(phi4Pick - 1).slug(),
                    firstNonBlank(phi4Reason, gemmaReason, "agreed"),
                    phi4Pick, gemmaPick);
        }
        // conflict
        return new AssistResult("conflict", null, null,
                firstNonBlank(phi4Reason, gemmaReason, "conflict"),
                phi4Pick, gemmaPick);
    }

    /** Returns min(a, b) on the high>medium>low ordering. Nulls treated as unknown (lowest). */
    private static String minConfidence(String a, String b) {
        int ra = confidenceRank(a);
        int rb = confidenceRank(b);
        int min = Math.min(ra, rb);
        return rankToConfidence(min);
    }

    private static int confidenceRank(String c) {
        if (c == null) return 0;
        return switch (c.toLowerCase()) {
            case "high"   -> 3;
            case "medium" -> 2;
            case "low"    -> 1;
            default       -> 0;
        };
    }

    private static String rankToConfidence(int r) {
        return switch (r) {
            case 3  -> "high";
            case 2  -> "medium";
            case 1  -> "low";
            default -> null;
        };
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }
}
