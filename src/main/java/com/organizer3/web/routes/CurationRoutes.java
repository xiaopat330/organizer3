package com.organizer3.web.routes;

import com.organizer3.curation.NearMissResolveService;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftActressRepository.PendingKanjiRow;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.ActressFuzzyMatcher.MatchResult;
import com.organizer3.translation.TranslationNormalization;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the Near-Miss curation UI.
 *
 * <p>Includes {@code GET /api/translation/stage-name-status} — logically translation queue state,
 * but placed here because it is consumed exclusively by the curation modal.
 */
@Slf4j
public class CurationRoutes {

    // Stage names re-use the label_basic strategy. The constant name is intentionally
    // descriptive of its purpose here; the shared strategy key is a known design choice.
    private static final String STAGE_NAME_STRATEGY_KEY = "label_basic";

    private final NearMissResolveService resolveService;
    private final DraftActressRepository draftActressRepo;
    private final ActressRepository actressRepo;
    private final ActressFuzzyMatcher actressFuzzyMatcher;
    private final StageNameSuggestionRepository stageNameSuggestionRepo;
    private final TranslationQueueRepository translationQueueRepo;
    private final TranslationService translationService;

    public CurationRoutes(NearMissResolveService resolveService,
                           DraftActressRepository draftActressRepo,
                           ActressRepository actressRepo,
                           ActressFuzzyMatcher actressFuzzyMatcher,
                           StageNameSuggestionRepository stageNameSuggestionRepo,
                           TranslationQueueRepository translationQueueRepo,
                           TranslationService translationService) {
        this.resolveService        = resolveService;
        this.draftActressRepo      = draftActressRepo;
        this.actressRepo           = actressRepo;
        this.actressFuzzyMatcher   = actressFuzzyMatcher;
        this.stageNameSuggestionRepo = stageNameSuggestionRepo;
        this.translationQueueRepo  = translationQueueRepo;
        this.translationService    = translationService;
    }

    public void register(Javalin app) {

        // GET /api/translation/stage-name-status?kanji=…
        // Returns { status: "queued"|"ready"|"missing", romaji?: string }.
        app.get("/api/translation/stage-name-status", ctx -> {
            try {
                String raw = ctx.queryParam("kanji");
                if (raw == null || raw.isBlank()) {
                    ctx.status(400).json(Map.of("error", "kanji query param is required"));
                    return;
                }
                ctx.json(stageNameStatus(raw));
            } catch (Exception e) {
                log.error("GET /api/translation/stage-name-status failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/stage-name-translate
        // Kicks off an LLM stage-name translation for a kanji with no existing suggestion or queue row.
        // Logically a write (may enqueue), so POST is appropriate.
        // Returns { status: "queued"|"ready"|"missing", romaji?: string, unresolvedDraftCount: number }.
        app.post("/api/translation/stage-name-translate", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String raw = body != null ? asString(body, "kanji") : null;
                if (raw == null || raw.isBlank()) {
                    ctx.status(400).json(Map.of("error", "kanji field is required"));
                    return;
                }
                String normalized = TranslationNormalization.normalize(raw);
                Optional<String> romaji = translationService.resolveOrSuggestStageName(normalized);
                Map<String, Object> result = new LinkedHashMap<>();
                if (romaji.isPresent()) {
                    result.put("status", "ready");
                    result.put("romaji", romaji.get());
                } else if (translationQueueRepo.hasPending(STAGE_NAME_STRATEGY_KEY, normalized)) {
                    result.put("status", "queued");
                } else {
                    result.put("status", "missing");
                }
                result.put("unresolvedDraftCount", draftActressRepo.countUnresolvedByKanji(normalized));
                ctx.json(result);
            } catch (Exception e) {
                log.error("POST /api/translation/stage-name-translate failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/stage-name-translate-now
        // Synchronously translates a stage-name kanji via Ollama (tier-1 only), bypassing the
        // async queue. Returns immediately on cache/suggestion hit.
        // Body: { kanji }
        // Returns { status: "ready"|"failed"|"sanitized", romaji?, unresolvedDraftCount }.
        app.post("/api/translation/stage-name-translate-now", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String raw = body != null ? asString(body, "kanji") : null;
                if (raw == null || raw.isBlank()) {
                    ctx.status(400).json(Map.of("error", "kanji field is required"));
                    return;
                }
                String normalized = TranslationNormalization.normalize(raw);
                Optional<String> romaji = translationService.translateStageNameNow(normalized);
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                if (romaji.isPresent()) {
                    result.put("status", "ready");
                    result.put("romaji", romaji.get());
                } else {
                    result.put("status", "failed");
                }
                result.put("unresolvedDraftCount", draftActressRepo.countUnresolvedByKanji(normalized));
                ctx.json(result);
            } catch (Exception e) {
                log.error("POST /api/translation/stage-name-translate-now failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/curation/pending-kanji
        // Returns [{ kanji, count, oldestSeen, suggestion: { status, romaji? } }].
        app.get("/api/curation/pending-kanji", ctx -> {
            try {
                List<PendingKanjiRow> rows = draftActressRepo.findPendingKanjiGroups();
                List<Map<String, Object>> result = new ArrayList<>(rows.size());
                for (PendingKanjiRow row : rows) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kanji",      row.kanji());
                    item.put("count",      row.count());
                    item.put("oldestSeen", row.oldestSeen());
                    item.put("suggestion", stageNameStatus(row.kanji()));
                    result.add(item);
                }
                ctx.json(result);
            } catch (Exception e) {
                log.error("GET /api/curation/pending-kanji failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/curation/fuzzy-candidates?romaji=…
        // Returns [{ actressId, canonicalName, rule }].
        app.get("/api/curation/fuzzy-candidates", ctx -> {
            try {
                String romaji = ctx.queryParam("romaji");
                if (romaji == null || romaji.isBlank()) {
                    ctx.json(List.of());
                    return;
                }
                List<MatchResult> candidates = actressFuzzyMatcher.findCandidates(romaji);
                List<Map<String, Object>> result = new ArrayList<>(candidates.size());
                for (MatchResult c : candidates) {
                    Optional<Actress> actress = actressRepo.findById(c.actressId());
                    String canonicalName = actress.map(a -> a.getCanonicalName()).orElse(null);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("actressId",     c.actressId());
                    item.put("canonicalName", canonicalName);
                    item.put("rule",          ruleLabel(c.rule()));
                    result.add(item);
                }
                ctx.json(result);
            } catch (Exception e) {
                log.error("GET /api/curation/fuzzy-candidates failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/curation/near-miss/resolve
        // Body: { kanji, primarySlug?, outcome, aliasOfActressId?, englishFirst, englishLast, llmRomaji? }
        // Returns { updatedDrafts, insertedAliases }.
        app.post("/api/curation/near-miss/resolve", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body == null) {
                    ctx.status(400).json(Map.of("error", "request body is required"));
                    return;
                }

                String outcomeStr = asString(body, "outcome");
                NearMissResolveService.Outcome outcome;
                try {
                    outcome = NearMissResolveService.Outcome.valueOf(
                            outcomeStr != null ? outcomeStr.toUpperCase() : "");
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "outcome must be ALIAS or CANONICAL"));
                    return;
                }

                NearMissResolveService.ResolveRequest req = new NearMissResolveService.ResolveRequest(
                        asString(body, "kanji"),
                        asString(body, "primarySlug"),
                        outcome,
                        asLong(body, "aliasOfActressId"),
                        asString(body, "englishFirst"),
                        asString(body, "englishLast"),
                        asString(body, "llmRomaji")
                );

                NearMissResolveService.ResolveResult result = resolveService.resolve(req);
                ctx.json(Map.of(
                        "updatedDrafts",   result.updatedDrafts(),
                        "insertedAliases", result.insertedAliases()
                ));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (Exception e) {
                log.error("POST /api/curation/near-miss/resolve failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });
    }

    // ── Shared helper: stage-name status ─────────────────────────────────────

    /**
     * Returns the suggestion status map for a kanji. Shared by the status endpoint and
     * the pending-kanji aggregator so both surfaces use identical logic.
     */
    private Map<String, Object> stageNameStatus(String rawKanji) {
        String normalized = TranslationNormalization.normalize(rawKanji);
        int unresolvedDraftCount = draftActressRepo.countUnresolvedByKanji(normalized);
        Optional<String> romaji = stageNameSuggestionRepo.findLatestUsableSuggestion(normalized);
        if (romaji.isPresent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ready");
            m.put("romaji", romaji.get());
            m.put("unresolvedDraftCount", unresolvedDraftCount);
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", translationQueueRepo.hasPending(STAGE_NAME_STRATEGY_KEY, normalized) ? "queued" : "missing");
        m.put("unresolvedDraftCount", unresolvedDraftCount);
        return m;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static String ruleLabel(ActressFuzzyMatcher.Rule rule) {
        return switch (rule) {
            case EXACT       -> "strong: exact";
            case REVERSAL    -> "strong: reversed";
            case PUNCT_NORM  -> "strong: punct-norm";
            case LAST_NAME_ONLY -> "weak: last-name";
        };
    }

    private static String asString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private static Long asLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return null;
    }
}
