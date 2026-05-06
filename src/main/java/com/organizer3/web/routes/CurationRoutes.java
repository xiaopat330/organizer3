package com.organizer3.web.routes;

import com.organizer3.curation.NearMissResolveService;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftActressRepository.PendingKanjiRow;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.ActressFuzzyMatcher.MatchResult;
import com.organizer3.translation.TranslationNormalization;
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

    private static final String STRATEGY_KEY_STAGE_NAME = "label_basic";

    private final NearMissResolveService resolveService;
    private final DraftActressRepository draftActressRepo;
    private final ActressRepository actressRepo;
    private final ActressFuzzyMatcher actressFuzzyMatcher;
    private final StageNameSuggestionRepository stageNameSuggestionRepo;
    private final TranslationQueueRepository translationQueueRepo;

    public CurationRoutes(NearMissResolveService resolveService,
                           DraftActressRepository draftActressRepo,
                           ActressRepository actressRepo,
                           ActressFuzzyMatcher actressFuzzyMatcher,
                           StageNameSuggestionRepository stageNameSuggestionRepo,
                           TranslationQueueRepository translationQueueRepo) {
        this.resolveService        = resolveService;
        this.draftActressRepo      = draftActressRepo;
        this.actressRepo           = actressRepo;
        this.actressFuzzyMatcher   = actressFuzzyMatcher;
        this.stageNameSuggestionRepo = stageNameSuggestionRepo;
        this.translationQueueRepo  = translationQueueRepo;
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
        Optional<String> romaji = stageNameSuggestionRepo.findLatestUsableSuggestion(normalized);
        if (romaji.isPresent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ready");
            m.put("romaji", romaji.get());
            return m;
        }
        if (translationQueueRepo.hasPending(STRATEGY_KEY_STAGE_NAME, normalized)) {
            return Map.of("status", "queued");
        }
        return Map.of("status", "missing");
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
