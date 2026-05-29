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
    private TitleRepository         titleRepo;
    private ActressRepository       actressRepo;
    private JavdbSlugResolver       slugResolver;
    private JavdbClient             javdbClient;
    private JavdbExtractor          extractor;
    private JavdbStagingRepository  stagingRepo;
    private ImageFetcher            imageFetcher;
    private TranslationService      translationService;
    private ActressFuzzyMatcher     fuzzyMatcher;

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
        titleRepo          = mock(TitleRepository.class);
        actressRepo        = mock(ActressRepository.class);
        slugResolver       = mock(JavdbSlugResolver.class);
        javdbClient        = mock(JavdbClient.class);
        extractor          = mock(JavdbExtractor.class);
        stagingRepo        = mock(JavdbStagingRepository.class);
        imageFetcher       = mock(ImageFetcher.class);
        translationService = mock(TranslationService.class);
        fuzzyMatcher       = mock(ActressFuzzyMatcher.class);

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
                coverStore, imageFetcher, JSON, translationService, fuzzyMatcher);
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
        when(translationService.resolveOrSuggestStageName(any())).thenReturn(Optional.empty());
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
        when(translationService.resolveOrSuggestStageName(any())).thenReturn(Optional.empty());
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
        when(translationService.resolveOrSuggestStageName(any())).thenReturn(Optional.empty());
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
        when(translationService.resolveOrSuggestStageName(any())).thenReturn(Optional.empty());
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
        when(translationService.resolveOrSuggestStageName(null)).thenReturn(Optional.empty());
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
        when(translationService.resolveOrSuggestStageName("浅見ゆま")).thenReturn(Optional.of("Yuma Asami"));
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
        when(translationService.resolveOrSuggestStageName("渡辺まや")).thenReturn(Optional.of("Maya Watanabe"));
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
        when(translationService.resolveOrSuggestStageName("田中みく")).thenReturn(Optional.of("Miku Tanaka"));
        when(fuzzyMatcher.match("Miku Tanaka")).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertNull(result.actressId());
        assertEquals("Miku", result.englishFirst());
        assertEquals("Tanaka", result.englishLast());
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
        verify(translationService, never()).resolveOrSuggestStageName(anyString());
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
        when(translationService.resolveOrSuggestStageName("誰でもない")).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result);
    }

    @Test
    void autoLinkActress_pass5b_enqueuedReturnsEmpty() {
        var entry = new TitleExtract.CastEntry("slug-5b", "木村花子", "F");
        when(actressRepo.resolveByName(any())).thenReturn(Optional.empty());
        when(stagingRepo.findActressIdByJavdbSlug("slug-5b")).thenReturn(Optional.empty());
        when(translationService.resolveOrSuggestStageName("木村花子")).thenReturn(Optional.empty());

        DraftPopulator.AutoLinkResult result = populator.autoLinkActress(entry);

        assertSame(DraftPopulator.AutoLinkResult.EMPTY, result);
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
        when(translationService.resolveOrSuggestStageName(fullWidthName)).thenReturn(Optional.empty());
        when(imageFetcher.fetch(anyString())).thenReturn(new ImageFetcher.Fetched(new byte[]{0}, "jpg"));

        var result = populator.populate(1L);
        assertEquals(DraftPopulator.Status.CREATED, result.status());

        DraftActress da = draftActressRepo.findBySlug("slug-nfkc").orElseThrow();
        assertEquals("テスト1号", da.getStageName(), "full-width digit should be NFKC-normalized to half-width");
    }
}
