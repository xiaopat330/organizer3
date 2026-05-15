package com.organizer3.web.routes;

import com.organizer3.curation.NearMissResolveService;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftActressRepository.PendingKanjiRow;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.ActressFuzzyMatcher.MatchResult;
import com.organizer3.translation.TranslationNormalization;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.repository.StageNameLookupRepository;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
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
    private final StageNameLookupRepository stageNameLookupRepo;
    private final StageNameSuggestionRepository stageNameSuggestionRepo;
    private final TranslationQueueRepository translationQueueRepo;
    private final TranslationService translationService;
    private final Jdbi jdbi;

    public CurationRoutes(NearMissResolveService resolveService,
                           DraftActressRepository draftActressRepo,
                           ActressRepository actressRepo,
                           ActressFuzzyMatcher actressFuzzyMatcher,
                           StageNameLookupRepository stageNameLookupRepo,
                           StageNameSuggestionRepository stageNameSuggestionRepo,
                           TranslationQueueRepository translationQueueRepo,
                           TranslationService translationService,
                           Jdbi jdbi) {
        this.resolveService        = resolveService;
        this.draftActressRepo      = draftActressRepo;
        this.actressRepo           = actressRepo;
        this.actressFuzzyMatcher   = actressFuzzyMatcher;
        this.stageNameLookupRepo   = stageNameLookupRepo;
        this.stageNameSuggestionRepo = stageNameSuggestionRepo;
        this.translationQueueRepo  = translationQueueRepo;
        this.translationService    = translationService;
        this.jdbi                  = jdbi;
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
                    String matchedAlias = findMatchedAlias(c.actressId(), romaji, canonicalName);
                    if (matchedAlias != null) {
                        item.put("matchedAlias", matchedAlias);
                    }
                    result.add(item);
                }
                ctx.json(result);
            } catch (Exception e) {
                log.error("GET /api/curation/fuzzy-candidates failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/curation/alias-capture-event
        // Persists an alias-capture modal fire to the server log for §5.4 measurement.
        // Body: { type: "trigger"|"dismissed", actressId: number, needs?: string[], via?: string }
        // Returns 204 No Content.
        app.post("/api/curation/alias-capture-event", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body == null) {
                    ctx.status(400).json(Map.of("error", "request body is required"));
                    return;
                }
                String type = asString(body, "type");
                if (type == null || type.isBlank()) {
                    ctx.status(400).json(Map.of("error", "type is required"));
                    return;
                }
                Object actressIdRaw = body.get("actressId");
                if (!(actressIdRaw instanceof Number)) {
                    ctx.status(400).json(Map.of("error", "actressId is required"));
                    return;
                }
                long actressId = ((Number) actressIdRaw).longValue();

                if ("trigger".equals(type)) {
                    @SuppressWarnings("unchecked")
                    List<String> needs = body.get("needs") instanceof List<?> l
                            ? (List<String>) l : List.of();
                    String needsJoined = String.join(",", needs);
                    String needsPart = needs.isEmpty() ? "" : " needs=" + needsJoined;
                    log.info("alias-capture: trigger actressId={}{}", actressId, needsPart);
                    insertAliasCaptureEvent("trigger", actressId, null, null,
                            needs.isEmpty() ? null : needsJoined);
                } else if ("dismissed".equals(type)) {
                    String via = asString(body, "via");
                    log.info("alias-capture: dismissed actressId={} via={}", actressId, via);
                    insertAliasCaptureEvent("dismissed", actressId, null, via, null);
                } else {
                    ctx.status(400).json(Map.of("error", "type must be trigger or dismissed"));
                    return;
                }
                ctx.status(204);
            } catch (Exception e) {
                log.error("POST /api/curation/alias-capture-event failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/curation/editor-session-open
        // Persists a draft-editor open event to the server log for §5.4 measurement.
        // Body: { titleId: number }
        // Returns 204 No Content.
        app.post("/api/curation/editor-session-open", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body == null) {
                    ctx.status(400).json(Map.of("error", "request body is required"));
                    return;
                }
                Object titleIdRaw = body.get("titleId");
                if (!(titleIdRaw instanceof Number)) {
                    ctx.status(400).json(Map.of("error", "titleId is required"));
                    return;
                }
                long titleId = ((Number) titleIdRaw).longValue();
                log.info("draft-editor: open titleId={}", titleId);
                insertAliasCaptureEvent("editor_open", null, titleId, null, null);
                ctx.status(204);
            } catch (Exception e) {
                log.error("POST /api/curation/editor-session-open failed", e);
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
        // Priority 1: curated lookup table (stage_name_lookup).
        Optional<String> romaji = stageNameLookupRepo.findRomanizedFor(normalized);
        // Priority 2: accepted/usable suggestion row.
        if (romaji.isEmpty()) {
            romaji = stageNameSuggestionRepo.findLatestUsableSuggestion(normalized);
        }
        if (romaji.isPresent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ready");
            m.put("romaji", romaji.get());
            m.put("unresolvedDraftCount", unresolvedDraftCount);
            return m;
        }
        // Priority 3: pending or in-flight queue row → queued. Otherwise → missing.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", translationQueueRepo.hasPending(STAGE_NAME_STRATEGY_KEY, normalized) ? "queued" : "missing");
        m.put("unresolvedDraftCount", unresolvedDraftCount);
        return m;
    }

    /**
     * If the searched romaji matched the actress via an alias rather than her canonical name,
     * return that alias string so the UI can show "via alias …". Returns null when the canonical
     * name itself matched (under any of the fuzzy rules) or when no alias matches.
     */
    private String findMatchedAlias(long actressId, String searchedRomaji, String canonicalName) {
        if (canonicalName != null && namesMatchUnderRules(canonicalName, searchedRomaji)) return null;
        for (ActressAlias alias : actressRepo.findAliases(actressId)) {
            if (alias.aliasName() != null && namesMatchUnderRules(alias.aliasName(), searchedRomaji)) {
                return alias.aliasName();
            }
        }
        return null;
    }

    /** Mirrors the fuzzy matcher's exact / reversal / punct-norm rules. Case-insensitive. */
    private static boolean namesMatchUnderRules(String stored, String searched) {
        if (stored.equalsIgnoreCase(searched)) return true;
        String[] tokens = searched.trim().split("\\s+");
        if (tokens.length > 1) {
            StringBuilder rev = new StringBuilder();
            for (int i = tokens.length - 1; i >= 0; i--) {
                if (rev.length() > 0) rev.append(' ');
                rev.append(tokens[i]);
            }
            if (stored.equalsIgnoreCase(rev.toString())) return true;
        }
        String stripped = searched.replaceAll("[-,]", "").replaceAll("\\s+", " ").trim();
        return stored.equalsIgnoreCase(stripped);
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

    /**
     * Persist a Phase 6d alias-capture / draft-editor measurement event. See
     * {@code SchemaUpgrader#applyV57} and spec/PROPOSAL_TRANSLATION_PHASE_6.md §5.4.
     *
     * <p>The redundant {@code log.info(...)} call at each call site remains; this row
     * gives us a durable signal that survives logback rotation (10MB×4) so the 7-day
     * measurement window can be restarted at any time.
     */
    private void insertAliasCaptureEvent(String kind, Long actressId, Long titleId,
                                          String via, String needs) {
        if (jdbi == null) return;
        try {
            jdbi.useHandle(h -> h.createUpdate("""
                    INSERT INTO alias_capture_events (ts, kind, actress_id, title_id, via, needs)
                    VALUES (:ts, :kind, :actressId, :titleId, :via, :needs)
                    """)
                    .bind("ts",         Instant.now().toString())
                    .bind("kind",       kind)
                    .bind("actressId",  actressId)
                    .bind("titleId",    titleId)
                    .bind("via",        via)
                    .bind("needs",      needs)
                    .execute());
        } catch (Exception e) {
            // Measurement is a best-effort signal — never fail the request because of it.
            log.warn("alias_capture_events insert failed kind={} actressId={} titleId={}",
                    kind, actressId, titleId, e);
        }
    }
}
