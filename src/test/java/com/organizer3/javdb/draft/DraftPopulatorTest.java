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
        titleRepo    = mock(TitleRepository.class);
        actressRepo  = mock(ActressRepository.class);
        slugResolver = mock(JavdbSlugResolver.class);
        javdbClient  = mock(JavdbClient.class);
        extractor    = mock(JavdbExtractor.class);
        stagingRepo  = mock(JavdbStagingRepository.class);
        imageFetcher = mock(ImageFetcher.class);

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
                coverStore, imageFetcher, JSON);
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
        Long result = populator.autoLinkActress(entry);
        assertNull(result);
    }
}
