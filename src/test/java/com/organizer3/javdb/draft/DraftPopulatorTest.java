package com.organizer3.javdb.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.enrichment.JavdbExtractor;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.javdb.enrichment.TitleExtract;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.web.ImageFetcher;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DraftPopulatorTest {

    @TempDir
    Path dataDir;

    // ── Mocked external dependencies ──────────────────────────────────────────
    private TitleRepository              titleRepo;
    private ActressRepository            actressRepo;
    private JavdbSlugResolver            slugResolver;
    private JavdbClient                  javdbClient;
    private JavdbExtractor               extractor;
    private JavdbStagingRepository       stagingRepo;
    private ImageFetcher                 imageFetcher;
    private TranslationService           translationService;
    private ActressFuzzyMatcher          fuzzyMatcher;
    private StageNameSuggestionRepository stageNameSuggestionRepo; // FIX 3a

    // ── In-memory SQLite repos ─────────────────────────────────────────────────
    private Connection connection;
    private Jdbi jdbi;
    private DraftTitleRepository        draftTitleRepo;
    private DraftActressRepository      draftActressRepo;
    private DraftTitleActressesRepository draftCastRepo;
    private DraftTitleEnrichmentRepository draftEnrichRepo;
    private DraftCoverScratchStore      coverStore;

    private DraftPopulator populator;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Mocks
        titleRepo               = mock(TitleRepository.class);
        actressRepo             = mock(ActressRepository.class);
        slugResolver            = mock(JavdbSlugResolver.class);
        javdbClient             = mock(JavdbClient.class);
        extractor               = mock(JavdbExtractor.class);
        stagingRepo             = mock(JavdbStagingRepository.class);
        imageFetcher            = mock(ImageFetcher.class);
        translationService      = mock(TranslationService.class);
        fuzzyMatcher            = mock(ActressFuzzyMatcher.class);
        stageNameSuggestionRepo = mock(StageNameSuggestionRepository.class); // FIX 3a

        // In-memory SQLite
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        draftTitleRepo   = new DraftTitleRepository(jdbi);
        draftActressRepo = new DraftActressRepository(jdbi);
        draftCastRepo    = new DraftTitleActressesRepository(jdbi);
        draftEnrichRepo  = new DraftTitleEnrichmentRepository(jdbi);
        coverStore       = new DraftCoverScratchStore(dataDir);

        // Seed a canonical titles row required by draft_titles FK.
        jdbi.useHandle(h ->
                h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)"));

        populator = new DraftPopulator(
                titleRepo, actressRepo, slugResolver, javdbClient, extractor, stagingRepo,
                draftTitleRepo, draftActressRepo, draftCastRepo, draftEnrichRepo,
                coverStore, imageFetcher, JSON, translationService, fuzzyMatcher,
                stageNameSuggestionRepo); // FIX 3a: wire suggestion repo for REVERSAL correction
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Title stubTitle(long id, String code) {
        return Title.builder().id(id).code(code).baseCode("TST").label("TST").seqNum(1).build();
    }

    private static TitleExtract stubExtract(String code, String slug, List<TitleExtract.CastEntry> cast) {
        return new TitleExtract(
                code, slug, "テスト", "2024-06-01", null,
                "S1", null, null,
                4.5, 100,
                List.of("Solowork"),
                cast,
                "https://example.com/cover.jpg",
                null,
                "2024-06-01T00:00:00Z",
                false, false);
    }

    private void stubSlugSuccess(String code, String slug) {
        when(slugResolver.resolve(eq(code), isNull()))
                .thenReturn(new JavdbSlugResolver.Success(slug, JavdbSlugResolver.Source.CODE_SEARCH_FALLBACK));
    }

    // ── titleNotFound ─────────────────────────────────────────────────────────

    @Test
    void populate_titleNotFound_returnsCorrectStatus() {
        when(titleRepo.findById(99L)).thenReturn(Optional.empty());
        var result = populator.populate(99L);
        assertEquals(DraftPopulator.Status.TITLE_NOT_FOUND, result.status());
        assertNull(result.draftTitleId());
    }

    // ── alreadyExists ─────────────────────────────────────────────────────────

    @Test
    void populate_draftAlreadyExists_returns409Status() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        // Insert an existing draft directly.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO draft_titles(title_id, code, created_at, updated_at) " +
                "VALUES (1, 'TST-1', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.ALREADY_EXISTS, result.status());
    }

    // ── javdbNotFound ─────────────────────────────────────────────────────────

    @Test
    void populate_javdbCodeNotFound_returnsJavdbNotFound() {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        when(slugResolver.resolve(eq("TST-1"), isNull()))
                .thenReturn(new JavdbSlugResolver.CodeNotFound());

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.JAVDB_NOT_FOUND, result.status());
    }

    // ── javdbError ────────────────────────────────────────────────────────────

    @Test
    void populate_javdbFetchThrows_returnsJavdbError() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenThrow(new RuntimeException("network error"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.JAVDB_ERROR, result.status());
        // No draft should be left in the DB after a rollback.
        assertTrue(draftTitleRepo.findByTitleId(1L).isEmpty(), "no partial draft should remain");
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void populate_happy_createsDraftRowsAndReturnsCreated() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        var cast = List.of(new TitleExtract.CastEntry("a1", "テスト女優", "F"));
        when(extractor.extractTitle("<html/>", "TST-1", "tst-1")).thenReturn(stubExtract("TST-1", "tst-1", cast));
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("a1")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{1, 2, 3}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());
        assertNotNull(result.draftTitleId());

        long draftId = result.draftTitleId();

        // draft_titles row
        var dt = draftTitleRepo.findById(draftId).orElseThrow();
        assertEquals(1L, dt.getTitleId());
        assertEquals("TST-1", dt.getCode());
        assertEquals("テスト", dt.getTitleOriginal());

        // draft_title_javdb_enrichment row
        var enr = draftEnrichRepo.findByDraftId(draftId).orElseThrow();
        assertEquals("tst-1", enr.getJavdbSlug());
        assertEquals("S1", enr.getMaker());

        // draft_actresses + draft_title_actresses
        var slots = draftCastRepo.findByDraftTitleId(draftId);
        assertEquals(1, slots.size());
        assertEquals("a1", slots.get(0).getJavdbSlug());
        assertEquals(DraftPopulator.RESOLUTION_UNRESOLVED, slots.get(0).getResolution());

        // cover file
        assertTrue(coverStore.exists(draftId), "cover scratch file must be written");
    }

    // ── Defect A: duration/publisher must survive populate → draft enrichment ──

    @Test
    void populate_happy_carriesDurationAndPublisherIntoDraftEnrichment() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        TitleExtract extract = new TitleExtract(
                "TST-1", "tst-1", "テスト", "2024-06-01", 120,
                "S1", "S1 Publisher", null,
                4.5, 100,
                List.of("Solowork"),
                List.of(),
                "https://example.com/cover.jpg",
                null,
                "2024-06-01T00:00:00Z",
                false, false);
        when(extractor.extractTitle("<html/>", "TST-1", "tst-1")).thenReturn(extract);
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        var enr = draftEnrichRepo.findByDraftId(result.draftTitleId()).orElseThrow();
        assertEquals(120, enr.getDurationMinutes(), "duration_minutes must be carried from the extract");
        assertEquals("S1 Publisher", enr.getPublisher(), "publisher must be carried from the extract");
    }

    // ── cover fetch failure is non-fatal ──────────────────────────────────────

    @Test
    void populate_coverFetchFails_draftStillCreated() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        when(extractor.extractTitle(any(), any(), any()))
                .thenReturn(stubExtract("TST-1", "tst-1", List.of()));
        when(imageFetcher.fetch(anyString())).thenThrow(new RuntimeException("cover fail"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());
        assertNotNull(result.draftTitleId());
    }

    // ── auto-link passes 1/2 ──────────────────────────────────────────────────

    /**
     * Seeds an actress row in the in-memory DB so FK constraints on
     * draft_actresses.link_to_existing_id are satisfied.
     */
    private void seedActress(long id, String canonicalName) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (?, ?, 'LIBRARY', '2024-01-01')",
                id, canonicalName));
    }

    @Test
    void populate_pass1Match_setsResolutionToPick() throws Exception {
        seedActress(42L, "Aika");

        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        var cast = List.of(new TitleExtract.CastEntry("a1", "Aika", "F"));
        when(extractor.extractTitle(any(), any(), any())).thenReturn(stubExtract("TST-1", "tst-1", cast));

        // Pass 1: resolveByName returns a non-rejected actress.
        Actress actress = Actress.builder().id(42L).canonicalName("Aika").build();
        when(actressRepo.resolveByName("aika")).thenReturn(Optional.of(actress));
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        var slots = draftCastRepo.findByDraftTitleId(result.draftTitleId());
        assertEquals(DraftPopulator.RESOLUTION_PICK, slots.get(0).getResolution());

        var draftActress = draftActressRepo.findBySlug("a1").orElseThrow();
        assertEquals(42L, draftActress.getLinkToExistingId());
    }

    @Test
    void populate_pass3SlugMatch_setsResolutionToPick() throws Exception {
        seedActress(7L, "SomeName");

        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        var cast = List.of(new TitleExtract.CastEntry("known-slug", "Unknown Name", "F"));
        when(extractor.extractTitle(any(), any(), any())).thenReturn(stubExtract("TST-1", "tst-1", cast));

        // Pass 1/2: no name match.
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        // Pass 3: slug match.
        when(stagingRepo.findActressIdByJavdbSlug("known-slug")).thenReturn(Optional.of(7L));
        Actress actress = Actress.builder().id(7L).canonicalName("SomeName").build();
        when(actressRepo.findById(7L)).thenReturn(Optional.of(actress));
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        var slots = draftCastRepo.findByDraftTitleId(result.draftTitleId());
        assertEquals(DraftPopulator.RESOLUTION_PICK, slots.get(0).getResolution());
    }

    @Test
    void populate_rejectedActress_notAutoLinked() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        var cast = List.of(new TitleExtract.CastEntry("a1", "Rejected One", "F"));
        when(extractor.extractTitle(any(), any(), any())).thenReturn(stubExtract("TST-1", "tst-1", cast));

        // Name match returns a rejected actress (no FK seeding needed — link_to_existing_id stays null).
        Actress rejected = Actress.builder().id(99L).canonicalName("Rejected One").rejected(true).build();
        when(actressRepo.resolveByName(any())).thenReturn(Optional.of(rejected));
        when(stagingRepo.findActressIdByJavdbSlug("a1")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        var slots = draftCastRepo.findByDraftTitleId(result.draftTitleId());
        assertEquals(DraftPopulator.RESOLUTION_UNRESOLVED, slots.get(0).getResolution());
    }

    // ── empty cast list ───────────────────────────────────────────────────────

    @Test
    void populate_emptyCastList_createsDraftWithNoSlots() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        when(extractor.extractTitle(any(), any(), any()))
                .thenReturn(stubExtract("TST-1", "tst-1", List.of()));
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());
        assertTrue(draftCastRepo.findByDraftTitleId(result.draftTitleId()).isEmpty());
    }

    // ── autoLinkActress unit tests ────────────────────────────────────────────

    @Test
    void autoLinkActress_nullName_skipsPass1And2() {
        // An entry with null name should not crash and should fall through to pass 3.
        var entry = new TitleExtract.CastEntry("slug-x", null, "F");
        when(stagingRepo.findActressIdByJavdbSlug("slug-x")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(isNull(), anyLong(), anyLong())).thenReturn(Optional.empty());
        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);
        assertNull(result.actressId());
        assertNull(result.englishFirst());
        assertNull(result.englishLast());
    }

    // ── pass 4 + 5 unit tests ─────────────────────────────────────────────────

    @Test
    void autoLinkActress_pass4Hit_returnsActressId() {
        seedActress(77L, "Yuma Asami");
        var entry = new TitleExtract.CastEntry("slug-p4", "浅見ゆま", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-p4")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("浅見ゆま"), anyLong(), anyLong())).thenReturn(Optional.of("Yuma Asami"));
        Actress matched = Actress.builder().id(77L).canonicalName("Yuma Asami").build();
        when(fuzzyMatcher.match("Yuma Asami"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(77L, ActressFuzzyMatcher.Rule.EXACT)));
        when(actressRepo.findById(77L)).thenReturn(Optional.of(matched));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(77L, result.actressId());
        assertNull(result.englishFirst());
        assertNull(result.englishLast());
    }

    @Test
    void autoLinkActress_pass4RejectedActress_fallsThroughToPass5a() {
        var entry = new TitleExtract.CastEntry("slug-rej", "渡辺まや", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-rej")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("渡辺まや"), anyLong(), anyLong())).thenReturn(Optional.of("Maya Watanabe"));
        Actress rejected = Actress.builder().id(55L).canonicalName("Maya Watanabe").rejected(true).build();
        when(fuzzyMatcher.match("Maya Watanabe"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(55L, ActressFuzzyMatcher.Rule.EXACT)));
        when(actressRepo.findById(55L)).thenReturn(Optional.of(rejected));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertNull(result.actressId());
        assertEquals("Maya", result.englishFirst());
        assertEquals("Watanabe", result.englishLast());
    }

    @Test
    void autoLinkActress_pass5a_prefillsEnglishName() {
        var entry = new TitleExtract.CastEntry("slug-5a", "田中みく", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-5a")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("田中みく"), anyLong(), anyLong())).thenReturn(Optional.of("Miku Tanaka"));
        when(fuzzyMatcher.match("Miku Tanaka")).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertNull(result.actressId());
        assertEquals("Miku", result.englishFirst());
        assertEquals("Tanaka", result.englishLast());
    }

    @Test
    void autoLinkActress_kanjiRomaji_treatedAsMiss_noFuzzyNoPrefill() {
        // The model echoed the kanji input back instead of romanizing, so resolveStageNameBlocking
        // returns a still-CJK "romaji" (e.g. "華 倉木"). The Pass 4/5a guard must treat this as a
        // miss: no fuzzy match, no english-name prefill → falls through to Pass 5b EMPTY.
        var entry = new TitleExtract.CastEntry("slug-kanji", "倉木華", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("倉木華")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-kanji")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("倉木華"), anyLong(), anyLong()))
                .thenReturn(Optional.of("華 倉木"));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result, "kanji romaji must yield EMPTY (unresolved)");
        assertNull(result.actressId());
        assertNull(result.englishFirst(), "kanji must NOT be split into english_first_name");
        assertNull(result.englishLast(), "kanji must NOT be split into english_last_name");
        // The kanji "romaji" must never be fuzzy-matched.
        verify(fuzzyMatcher, never()).match("華 倉木");
    }

    // ── pass 2.5 (kanji stage_name) unit tests ───────────────────────────────

    @Test
    void autoLinkActress_pass2_5StageNameMatch_returnsActressId() {
        // javdb cast name is kanji; the actress's canonical_name is romaji, so Pass 1/2 miss,
        // but her stage_name carries the kanji → Pass 2.5 resolves her.
        var entry = new TitleExtract.CastEntry("slug-stage", "森日向子", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        Actress matched = Actress.builder().id(321L).canonicalName("Hinako Mori")
                .stageName("森日向子").build();
        when(actressRepo.findByStageName("森日向子")).thenReturn(Optional.of(matched));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(321L, result.actressId());
        assertNull(result.englishFirst());
        assertNull(result.englishLast());
        // Pass 2.5 short-circuits before the slug / translation passes.
        verify(stagingRepo, never()).findActressIdByJavdbSlug(anyString());
        verify(translationService, never()).resolveStageNameBlocking(anyString(), anyLong(), anyLong());
    }

    @Test
    void autoLinkActress_pass1Wins_stageNamePassNotConsulted() {
        // When Pass 1 (resolveByName) matches, the kanji stage_name pass must never be queried.
        var entry = new TitleExtract.CastEntry("slug-p1", "Aika", "F");
        Actress actress = Actress.builder().id(42L).canonicalName("Aika").build();
        when(actressRepo.resolveByName("aika")).thenReturn(Optional.of(actress));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(42L, result.actressId());
        verify(actressRepo, never()).findByStageName(any());
    }

    @Test
    void autoLinkActress_stageNameMiss_fallsThroughToLaterPasses() {
        // Kanji name matches no stage_name (and all other passes empty) → returns EMPTY as before.
        var entry = new TitleExtract.CastEntry("slug-miss", "誰でもない", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("誰でもない")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-miss")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("誰でもない"), anyLong(), anyLong())).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result);
    }

    @Test
    void autoLinkActress_pass5b_enqueuedReturnsEmpty() {
        var entry = new TitleExtract.CastEntry("slug-5b", "木村花子", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-5b")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("木村花子"), anyLong(), anyLong())).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result);
    }

    // ── gender filter: only F entries become cast slots ───────────────────────

    /**
     * Cast with 1 female + 2 males: only the female produces a draft_title_actresses slot
     * and a draft_actresses row, but the stored cast_json in draft_title_javdb_enrichment
     * must retain all 3 entries.
     */
    @Test
    void writeCastSlots_maleGenderEntriesSkipped_onlyFemaleBecomesSlot() throws Exception {
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");

        var cast = List.of(
                new TitleExtract.CastEntry("female-slug", "Female Actress", "F"),
                new TitleExtract.CastEntry("male-slug-1", "Male Actor 1",   "M"),
                new TitleExtract.CastEntry("male-slug-2", "Male Actor 2",   "M"));
        when(extractor.extractTitle("<html/>", "TST-1", "tst-1")).thenReturn(stubExtract("TST-1", "tst-1", cast));

        // Only female-slug will be auto-linked; the males should never reach autoLinkActress.
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("female-slug")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{1}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());
        long draftId = result.draftTitleId();

        // Exactly 1 draft_title_actresses slot — the female.
        var slots = draftCastRepo.findByDraftTitleId(draftId);
        assertEquals(1, slots.size(), "only the female entry should produce a slot");
        assertEquals("female-slug", slots.get(0).getJavdbSlug());

        // Exactly 1 draft_actresses row — the female.
        assertNotNull(draftActressRepo.findBySlug("female-slug").orElse(null));
        assertTrue(draftActressRepo.findBySlug("male-slug-1").isEmpty(),
                "male actor must NOT have a draft_actresses row");
        assertTrue(draftActressRepo.findBySlug("male-slug-2").isEmpty(),
                "male actor must NOT have a draft_actresses row");

        // The stored cast_json in draft_title_javdb_enrichment retains all 3 entries.
        var enr = draftEnrichRepo.findByDraftId(draftId).orElseThrow();
        String storedCast = enr.getCastJson();
        assertNotNull(storedCast);
        assertTrue(storedCast.contains("female-slug"), "stored cast_json must contain female slug");
        assertTrue(storedCast.contains("male-slug-1"), "stored cast_json must still contain male slug 1");
        assertTrue(storedCast.contains("male-slug-2"), "stored cast_json must still contain male slug 2");

        // Males must never have reached autoLinkActress (i.e., slug lookup never called for them).
        verify(stagingRepo, org.mockito.Mockito.never()).findActressIdByJavdbSlug("male-slug-1");
        verify(stagingRepo, org.mockito.Mockito.never()).findActressIdByJavdbSlug("male-slug-2");
    }

    @Test
    void writeCastSlots_nfkcNormalizesStageName() throws Exception {
        // Full-width digit in stage name should be NFKC-normalized to half-width.
        // U+FF11 '１' normalizes to '1'.
        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        String fullWidthName = "テスト１号";  // '１' is full-width digit
        var cast = List.of(new TitleExtract.CastEntry("slug-nfkc", fullWidthName, "F"));
        when(extractor.extractTitle(any(), any(), any())).thenReturn(stubExtract("TST-1", "tst-1", cast));
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-nfkc")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq(fullWidthName), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        DraftActress da = draftActressRepo.findBySlug("slug-nfkc").orElseThrow();
        assertEquals("テスト1号", da.getStageName(), "full-width digit should be NFKC-normalized to half-width");
    }

    // ── FIX 2: resolveStageNameBlocking is used in Pass 4 ────────────────────

    /**
     * FIX 2 race-reproducing test: the blocking call is mocked to return the romaji
     * (simulating the async LLM worker succeeding within the wait window). The existing
     * actress "Airi Nagisa" has NO kanji stage_name and NO slug — the old single-call
     * path would have missed because the suggestion wasn't present yet.
     *
     * <p>The reversal rule fires: LLM returned "Nagisa Airi" (surname-first); the
     * fuzzy matcher reverses to "Airi Nagisa" and hits. This test asserts the slot
     * resolution flips to {@code pick} via the normal match path.
     */
    @Test
    void autoLinkActress_fix2_blockingCallReturnsRomaji_reversalMatchSetsPickResolution() {
        seedActress(4023L, "Airi Nagisa"); // no stage_name, no slug

        var entry = new TitleExtract.CastEntry("Mm5v4", "渚あいり", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("渚あいり")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("Mm5v4")).thenReturn(Optional.empty());

        // FIX 2: blocking call mocked to return surname-first romaji (as LLM would produce).
        // This simulates the race: the LLM finished within the blocking window.
        when(translationService.resolveStageNameBlocking(eq("渚あいり"), anyLong(), anyLong()))
                .thenReturn(Optional.of("Nagisa Airi"));

        // Fuzzy matcher: REVERSAL rule fires ("Nagisa Airi" → reversed → "Airi Nagisa")
        Actress matched = Actress.builder().id(4023L).canonicalName("Airi Nagisa").build();
        when(fuzzyMatcher.match("Nagisa Airi"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(4023L, ActressFuzzyMatcher.Rule.REVERSAL)));
        when(actressRepo.findById(4023L)).thenReturn(Optional.of(matched));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        // (a) Slot resolution becomes 'pick' linked to actress 4023 — via the normal match path.
        assertEquals(4023L, result.actressId(), "REVERSAL match must return the actress id (pick resolution)");
        assertNull(result.englishFirst());
        assertNull(result.englishLast());
    }

    /**
     * FIX 2 full-path test through {@code populate()}: confirms the slot resolution
     * written to {@code draft_title_actresses.resolution} equals {@code "pick"} when
     * the blocking call returns a romaji that matches via the REVERSAL rule.
     *
     * <p>This verifies point (a): the per-draft slot resolution flips to {@code pick}
     * through {@link DraftPopulator#writeCastSlots}, not just {@code link_to_existing_id}.
     */
    @Test
    void populate_fix2_blockingCallMatchViaReversal_slotResolutionIsPick() throws Exception {
        seedActress(4023L, "Airi Nagisa");

        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        var cast = List.of(new TitleExtract.CastEntry("Mm5v4", "渚あいり", "F"));
        when(extractor.extractTitle(any(), any(), any())).thenReturn(stubExtract("TST-1", "tst-1", cast));

        // Passes 1/2/3: all miss.
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("渚あいり")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("Mm5v4")).thenReturn(Optional.empty());

        // FIX 2: blocking call resolves the romaji within the wait window.
        when(translationService.resolveStageNameBlocking(eq("渚あいり"), anyLong(), anyLong()))
                .thenReturn(Optional.of("Nagisa Airi"));

        // Fuzzy REVERSAL match → actress 4023.
        Actress matched = Actress.builder().id(4023L).canonicalName("Airi Nagisa").build();
        when(fuzzyMatcher.match("Nagisa Airi"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(4023L, ActressFuzzyMatcher.Rule.REVERSAL)));
        when(actressRepo.findById(4023L)).thenReturn(Optional.of(matched));
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        // (a) draft_title_actresses.resolution must be "pick" — set by writeCastSlots line 294.
        var slots = draftCastRepo.findByDraftTitleId(result.draftTitleId());
        assertEquals(1, slots.size());
        assertEquals(DraftPopulator.RESOLUTION_PICK, slots.get(0).getResolution(),
                "FIX 2: slot resolution must be 'pick' when blocking call resolves via REVERSAL");

        // link_to_existing_id must point to actress 4023.
        var da = draftActressRepo.findBySlug("Mm5v4").orElseThrow();
        assertEquals(4023L, da.getLinkToExistingId());
    }

    /**
     * FIX 2 timeout test: the blocking call returns empty (LLM not done / down).
     * The slot must stay {@code unresolved} — no regression from the old behaviour.
     */
    @Test
    void autoLinkActress_fix2_blockingCallTimesOut_slotStaysUnresolved() {
        var entry = new TitleExtract.CastEntry("slug-timeout", "渚あいり", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("渚あいり")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-timeout")).thenReturn(Optional.empty());

        // Blocking call times out → returns empty.
        when(translationService.resolveStageNameBlocking(eq("渚あいり"), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result, "timeout must return EMPTY (unresolved)");
    }

    // ── FIX 3a: REVERSAL match triggers recordFinalRomaji ────────────────────

    /**
     * FIX 3a: When the REVERSAL rule fires, {@code recordFinalRomaji} is called with
     * the canonical (given-first) order from the matched actress.
     */
    @Test
    void autoLinkActress_fix3a_reversalRule_recordsFinalRomajiWithCanonicalOrder() {
        seedActress(4023L, "Airi Nagisa");

        var entry = new TitleExtract.CastEntry("Mm5v4", "渚あいり", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("渚あいり")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("Mm5v4")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("渚あいり"), anyLong(), anyLong()))
                .thenReturn(Optional.of("Nagisa Airi")); // surname-first from LLM

        Actress matched = Actress.builder().id(4023L).canonicalName("Airi Nagisa").build();
        when(fuzzyMatcher.match("Nagisa Airi"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(4023L, ActressFuzzyMatcher.Rule.REVERSAL)));
        when(actressRepo.findById(4023L)).thenReturn(Optional.of(matched));

        populator.autoLinkActress(entry);

        // FIX 3a: the corrected canonical order must be persisted into final_romaji.
        verify(stageNameSuggestionRepo).recordFinalRomaji(
                com.organizer3.translation.TranslationNormalization.normalize("渚あいり"),
                "Airi Nagisa");
    }

    /**
     * FIX 3a: An EXACT-rule match must NOT trigger recordFinalRomaji (only REVERSAL is wrong-order).
     */
    @Test
    void autoLinkActress_fix3a_exactRule_doesNotRecordFinalRomaji() {
        seedActress(77L, "Yuma Asami");

        var entry = new TitleExtract.CastEntry("slug-exact", "浅見ゆま", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName("浅見ゆま")).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-exact")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("浅見ゆま"), anyLong(), anyLong()))
                .thenReturn(Optional.of("Yuma Asami"));

        Actress matched = Actress.builder().id(77L).canonicalName("Yuma Asami").build();
        when(fuzzyMatcher.match("Yuma Asami"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(77L, ActressFuzzyMatcher.Rule.EXACT)));
        when(actressRepo.findById(77L)).thenReturn(Optional.of(matched));

        populator.autoLinkActress(entry);

        verify(stageNameSuggestionRepo, never()).recordFinalRomaji(any(), any());
    }

    // ── resolved_via provenance tests ─────────────────────────────────────────

    @Test
    void autoLinkActress_pass1CanonicalMatch_viaIsCanonical() {
        // Pass 1: resolveByName returns an actress whose canonical_name matches the normalized input.
        var entry = new TitleExtract.CastEntry("slug-c", "Aika", "F");
        Actress actress = Actress.builder().id(10L).canonicalName("Aika").build();
        when(actressRepo.resolveByName("aika")).thenReturn(Optional.of(actress));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(10L, result.actressId());
        assertEquals("canonical", result.via(), "Pass 1 canonical match must set via='canonical'");
    }

    @Test
    void autoLinkActress_pass2AliasMatch_viaIsAlias() {
        // Pass 2: resolveByName returns an actress whose canonical_name DIFFERS from the lookup —
        // meaning the match was via an alias.
        var entry = new TitleExtract.CastEntry("slug-a", "Ai Kago", "F");
        // The actress's canonical_name is "Aika" (≠ "ai kago"), so this is an alias match.
        Actress actress = Actress.builder().id(20L).canonicalName("Aika").build();
        when(actressRepo.resolveByName("ai kago")).thenReturn(Optional.of(actress));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(20L, result.actressId());
        assertEquals("alias", result.via(), "Pass 2 alias match must set via='alias'");
    }

    @Test
    void autoLinkActress_pass2_5StageNameMatch_viaIsStageName() {
        var entry = new TitleExtract.CastEntry("slug-sn", "森日向子", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        Actress matched = Actress.builder().id(321L).canonicalName("Hinako Mori")
                .stageName("森日向子").build();
        when(actressRepo.findByStageName("森日向子")).thenReturn(Optional.of(matched));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(321L, result.actressId());
        assertEquals("stage_name", result.via(), "Pass 2.5 stage_name match must set via='stage_name'");
    }

    @Test
    void autoLinkActress_pass3SlugMatch_viaIsSlug() {
        seedActress(7L, "SomeName");
        var entry = new TitleExtract.CastEntry("known-slug-v", "Unknown Name", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("known-slug-v")).thenReturn(Optional.of(7L));
        Actress actress = Actress.builder().id(7L).canonicalName("SomeName").build();
        when(actressRepo.findById(7L)).thenReturn(Optional.of(actress));
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(7L, result.actressId());
        assertEquals("slug", result.via(), "Pass 3 slug match must set via='slug'");
    }

    @Test
    void autoLinkActress_pass4FuzzyMatch_viaIsFuzzy() {
        seedActress(77L, "Yuma Asami");
        var entry = new TitleExtract.CastEntry("slug-fz", "浅見ゆま", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-fz")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("浅見ゆま"), anyLong(), anyLong()))
                .thenReturn(Optional.of("Yuma Asami"));
        Actress matched = Actress.builder().id(77L).canonicalName("Yuma Asami").build();
        when(fuzzyMatcher.match("Yuma Asami"))
                .thenReturn(Optional.of(new ActressFuzzyMatcher.MatchResult(77L, ActressFuzzyMatcher.Rule.EXACT)));
        when(actressRepo.findById(77L)).thenReturn(Optional.of(matched));

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertEquals(77L, result.actressId());
        assertEquals("fuzzy", result.via(), "Pass 4 fuzzy match must set via='fuzzy'");
    }

    @Test
    void autoLinkActress_pass5aPrefill_viaIsPrefill() {
        var entry = new TitleExtract.CastEntry("slug-pf", "田中みく", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-pf")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(eq("田中みく"), anyLong(), anyLong()))
                .thenReturn(Optional.of("Miku Tanaka"));
        when(fuzzyMatcher.match("Miku Tanaka")).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertNull(result.actressId());
        assertEquals("Miku", result.englishFirst());
        assertEquals("Tanaka", result.englishLast());
        assertEquals("prefill", result.via(), "Pass 5a prefill must set via='prefill'");
    }

    @Test
    void autoLinkActress_pass5bNoMatch_viaIsNull() {
        var entry = new TitleExtract.CastEntry("slug-5bv", "木村花子", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(actressRepo.findByStageName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-5bv")).thenReturn(Optional.empty());
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result);
        assertNull(result.via(), "Pass 5b total miss must have null via");
    }

    @Test
    void writeCastSlots_pass1Match_persistsResolvedViaOnSlot() throws Exception {
        seedActress(42L, "Aika");

        when(titleRepo.findById(1L)).thenReturn(Optional.of(stubTitle(1L, "TST-1")));
        stubSlugSuccess("TST-1", "tst-1");
        when(javdbClient.fetchTitlePage("tst-1")).thenReturn("<html/>");
        var cast = List.of(new TitleExtract.CastEntry("a1v", "Aika", "F"));
        when(extractor.extractTitle(any(), any(), any())).thenReturn(stubExtract("TST-1", "tst-1", cast));

        Actress actress = Actress.builder().id(42L).canonicalName("Aika").build();
        when(actressRepo.resolveByName("aika")).thenReturn(Optional.of(actress));
        when(translationService.resolveStageNameBlocking(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        var slots = draftCastRepo.findByDraftTitleId(result.draftTitleId());
        assertEquals(1, slots.size());
        assertEquals("canonical", slots.get(0).getResolvedVia(),
                "slot persisted by Pass 1 must carry resolved_via='canonical'");
    }

    // ── resolverSourceLabel: Defect B (two spellings for the same source) ───────

    @Test
    void resolverSourceLabel_codeSearchFallback_emitsUnderscoreForm() {
        var success = new JavdbSlugResolver.Success("slug", JavdbSlugResolver.Source.CODE_SEARCH_FALLBACK);
        assertEquals("code_search_fallback", DraftPopulator.resolverSourceLabel(success),
                "must emit the canonical underscore form, not the old hyphenated 'code-search-fallback'");
    }

    @Test
    void resolverSourceLabel_actressFilmography_emitsUnderscoreForm() {
        var success = new JavdbSlugResolver.Success("slug", JavdbSlugResolver.Source.ACTRESS_FILMOGRAPHY);
        assertEquals("actress_filmography", DraftPopulator.resolverSourceLabel(success),
                "must emit the canonical underscore form, not the old hyphenated 'actress-filmography'");
    }

    /**
     * Cross-agreement check: for every {@link JavdbSlugResolver.Source} value, this method's
     * output must equal {@code EnrichmentRunner.resolverSourceLabel}'s output for the same
     * value, so the two functions can never drift apart again (the original bug). Invoked via
     * reflection since EnrichmentRunner's method is private in a different package.
     */
    @Test
    void resolverSourceLabel_agreesWithEnrichmentRunner_forEveryEnumValue() throws Exception {
        var enrichmentRunnerMethod = com.organizer3.javdb.enrichment.EnrichmentRunner.class
                .getDeclaredMethod("resolverSourceLabel", JavdbSlugResolver.Source.class);
        enrichmentRunnerMethod.setAccessible(true);

        for (JavdbSlugResolver.Source source : JavdbSlugResolver.Source.values()) {
            String expected = (String) enrichmentRunnerMethod.invoke(null, source);
            String actual = DraftPopulator.resolverSourceLabel(new JavdbSlugResolver.Success("slug", source));
            assertEquals(expected, actual,
                    "DraftPopulator.resolverSourceLabel must agree with EnrichmentRunner's for source=" + source);
        }
    }
}
