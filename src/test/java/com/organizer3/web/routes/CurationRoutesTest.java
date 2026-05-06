package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.curation.NearMissResolveService;
import com.organizer3.curation.NearMissResolveService.Outcome;
import com.organizer3.curation.NearMissResolveService.ResolveResult;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftActressRepository.PendingKanjiRow;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.ActressFuzzyMatcher.MatchResult;
import com.organizer3.translation.ActressFuzzyMatcher.Rule;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CurationRoutesTest {

    private WebServer server;
    private NearMissResolveService resolveService;
    private DraftActressRepository draftActressRepo;
    private ActressRepository actressRepo;
    private ActressFuzzyMatcher actressFuzzyMatcher;
    private StageNameSuggestionRepository suggestionRepo;
    private TranslationQueueRepository queueRepo;
    private TranslationService translationService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        resolveService      = mock(NearMissResolveService.class);
        draftActressRepo    = mock(DraftActressRepository.class);
        actressRepo         = mock(ActressRepository.class);
        actressFuzzyMatcher = mock(ActressFuzzyMatcher.class);
        suggestionRepo      = mock(StageNameSuggestionRepository.class);
        queueRepo           = mock(TranslationQueueRepository.class);
        translationService  = mock(TranslationService.class);

        server = new WebServer(0);
        server.registerCuration(new CurationRoutes(
                resolveService, draftActressRepo, actressRepo,
                actressFuzzyMatcher, suggestionRepo, queueRepo,
                translationService));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String base() { return "http://localhost:" + server.port(); }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── GET /api/translation/stage-name-status ───────────────────────────────

    @Test
    void stageNameStatus_ready() throws Exception {
        when(suggestionRepo.findLatestUsableSuggestion(anyString()))
                .thenReturn(Optional.of("Natsume Iroha"));
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(5);

        HttpResponse<String> res = get("/api/translation/stage-name-status?kanji=夏目彩春");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("ready",         body.get("status").asText());
        assertEquals("Natsume Iroha", body.get("romaji").asText());
        assertEquals(5,               body.get("unresolvedDraftCount").asInt());
    }

    @Test
    void stageNameStatus_queued_whenNoSuggestionButPendingQueueRow() throws Exception {
        when(suggestionRepo.findLatestUsableSuggestion(anyString())).thenReturn(Optional.empty());
        when(queueRepo.hasPending(anyString(), anyString())).thenReturn(true);
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(2);

        HttpResponse<String> res = get("/api/translation/stage-name-status?kanji=夏目彩春");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("queued", body.get("status").asText());
        assertEquals(2,        body.get("unresolvedDraftCount").asInt());
    }

    @Test
    void stageNameStatus_missing_whenNeitherSuggestionNorQueue() throws Exception {
        when(suggestionRepo.findLatestUsableSuggestion(anyString())).thenReturn(Optional.empty());
        when(queueRepo.hasPending(anyString(), anyString())).thenReturn(false);
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(0);

        HttpResponse<String> res = get("/api/translation/stage-name-status?kanji=夏目彩春");

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("missing", body.get("status").asText());
        assertEquals(0,         body.get("unresolvedDraftCount").asInt());
    }

    @Test
    void stageNameStatus_400_whenKanjiMissing() throws Exception {
        HttpResponse<String> res = get("/api/translation/stage-name-status");
        assertEquals(400, res.statusCode());
    }

    // ── GET /api/curation/pending-kanji ──────────────────────────────────────

    @Test
    void pendingKanji_returnsListWithSuggestionField() throws Exception {
        when(draftActressRepo.findPendingKanjiGroups())
                .thenReturn(List.of(new PendingKanjiRow("夏目彩春", 3, "2024-01-01T00:00:00Z")));
        when(suggestionRepo.findLatestUsableSuggestion(anyString()))
                .thenReturn(Optional.of("Natsume Iroha"));

        HttpResponse<String> res = get("/api/curation/pending-kanji");

        assertEquals(200, res.statusCode());
        JsonNode arr = mapper.readTree(res.body());
        assertTrue(arr.isArray());
        assertEquals(1, arr.size());
        JsonNode row = arr.get(0);
        assertEquals("夏目彩春",              row.get("kanji").asText());
        assertEquals(3,                      row.get("count").asInt());
        assertEquals("ready",                row.get("suggestion").get("status").asText());
        assertEquals("Natsume Iroha",        row.get("suggestion").get("romaji").asText());
    }

    // ── GET /api/curation/fuzzy-candidates ───────────────────────────────────

    @Test
    void fuzzyCandidates_returnsCandidatesWithRuleLabel() throws Exception {
        when(actressFuzzyMatcher.findCandidates("Natsume Iroha"))
                .thenReturn(List.of(new MatchResult(77L, Rule.EXACT)));
        Actress actress = Actress.builder().id(77L).canonicalName("Iroha Natsume").tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.findById(77L)).thenReturn(Optional.of(actress));

        HttpResponse<String> res = get("/api/curation/fuzzy-candidates?romaji=Natsume+Iroha");

        assertEquals(200, res.statusCode());
        JsonNode arr = mapper.readTree(res.body());
        assertEquals(1, arr.size());
        assertEquals(77L,           arr.get(0).get("actressId").asLong());
        assertEquals("Iroha Natsume", arr.get(0).get("canonicalName").asText());
        assertEquals("strong: exact", arr.get(0).get("rule").asText());
    }

    @Test
    void fuzzyCandidates_emptyListForBlankRomaji() throws Exception {
        HttpResponse<String> res = get("/api/curation/fuzzy-candidates");
        assertEquals(200, res.statusCode());
        JsonNode arr = mapper.readTree(res.body());
        assertTrue(arr.isArray());
        assertEquals(0, arr.size());
    }

    // ── POST /api/curation/near-miss/resolve ─────────────────────────────────

    @Test
    void resolve_200_onSuccess() throws Exception {
        when(resolveService.resolve(any())).thenReturn(new ResolveResult(3, 2));

        HttpResponse<String> res = post("/api/curation/near-miss/resolve", Map.of(
                "kanji",            "夏目彩春",
                "outcome",          "ALIAS",
                "aliasOfActressId", 10L,
                "englishFirst",     "Ayaharu",
                "englishLast",      "Natsume"
        ));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals(3, body.get("updatedDrafts").asInt());
        assertEquals(2, body.get("insertedAliases").asInt());
    }

    @Test
    void resolve_400_onIllegalArgument() throws Exception {
        when(resolveService.resolve(any()))
                .thenThrow(new IllegalArgumentException("englishLast is required"));

        HttpResponse<String> res = post("/api/curation/near-miss/resolve", Map.of(
                "kanji",   "夏目彩春",
                "outcome", "ALIAS"
        ));

        assertEquals(400, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("englishLast is required", body.get("error").asText());
    }

    @Test
    void resolve_400_onUnknownOutcome() throws Exception {
        HttpResponse<String> res = post("/api/curation/near-miss/resolve", Map.of(
                "kanji",      "夏目彩春",
                "outcome",    "UNKNOWN_OUTCOME",
                "englishLast", "Natsume"
        ));

        assertEquals(400, res.statusCode());
    }

    // ── POST /api/translation/stage-name-translate ───────────────────────────

    @Test
    void stageNameTranslate_ready_whenResolveOrSuggestReturnsSynchronously() throws Exception {
        when(translationService.resolveOrSuggestStageName(anyString()))
                .thenReturn(Optional.of("Natsume Iroha"));
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(4);

        HttpResponse<String> res = post("/api/translation/stage-name-translate",
                Map.of("kanji", "夏目彩春"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("ready",         body.get("status").asText());
        assertEquals("Natsume Iroha", body.get("romaji").asText());
        assertEquals(4,               body.get("unresolvedDraftCount").asInt());
    }

    @Test
    void stageNameTranslate_queued_whenEnqueuedButNotYetReady() throws Exception {
        when(translationService.resolveOrSuggestStageName(anyString()))
                .thenReturn(Optional.empty());
        when(queueRepo.hasPending(anyString(), anyString())).thenReturn(true);
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(1);

        HttpResponse<String> res = post("/api/translation/stage-name-translate",
                Map.of("kanji", "夏目彩春"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("queued", body.get("status").asText());
        assertEquals(1,        body.get("unresolvedDraftCount").asInt());
    }

    // ── POST /api/translation/stage-name-translate-now ───────────────────────

    @Test
    void stageNameTranslateNow_ready_whenTranslationSucceeds() throws Exception {
        when(translationService.translateStageNameNow(anyString()))
                .thenReturn(Optional.of("Natsume Iroha"));
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(3);

        HttpResponse<String> res = post("/api/translation/stage-name-translate-now",
                Map.of("kanji", "夏目彩春"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("ready",         body.get("status").asText());
        assertEquals("Natsume Iroha", body.get("romaji").asText());
        assertEquals(3,               body.get("unresolvedDraftCount").asInt());
    }

    @Test
    void stageNameTranslateNow_failed_whenTranslationReturnsEmpty() throws Exception {
        when(translationService.translateStageNameNow(anyString()))
                .thenReturn(Optional.empty());
        when(draftActressRepo.countUnresolvedByKanji(anyString())).thenReturn(2);

        HttpResponse<String> res = post("/api/translation/stage-name-translate-now",
                Map.of("kanji", "夏目彩春"));

        assertEquals(200, res.statusCode());
        JsonNode body = mapper.readTree(res.body());
        assertEquals("failed", body.get("status").asText());
        assertEquals(2,        body.get("unresolvedDraftCount").asInt());
        assertFalse(body.has("romaji"), "romaji field must be absent on failure");
    }

    @Test
    void stageNameTranslateNow_400_whenKanjiMissing() throws Exception {
        HttpResponse<String> res = post("/api/translation/stage-name-translate-now", Map.of());
        assertEquals(400, res.statusCode());
    }
}
