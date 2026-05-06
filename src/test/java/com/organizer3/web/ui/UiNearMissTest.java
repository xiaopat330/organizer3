package com.organizer3.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.organizer3.curation.NearMissResolveService;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftActress;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftPatchService;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPromotionService;
import com.organizer3.javdb.draft.DraftTitle;
import com.organizer3.javdb.draft.DraftTitleActress;
import com.organizer3.javdb.draft.DraftTitleActressesRepository;
import com.organizer3.javdb.draft.DraftTitleEnrichmentRepository;
import com.organizer3.javdb.draft.DraftTitleRepository;
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.TranslationNormalization;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.repository.StageNameLookupRepository;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.jdbi.JdbiStageNameLookupRepository;
import com.organizer3.translation.repository.jdbi.JdbiStageNameSuggestionRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationQueueRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.web.ImageFetcher;
import com.organizer3.web.JavdbEnrichmentActionService;
import com.organizer3.web.WebServer;
import com.organizer3.web.routes.CurationRoutes;
import com.organizer3.web.routes.DraftRoutes;
import com.organizer3.web.routes.JavdbDiscoveryRoutes;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Playwright UI smoke pins for the Near-Miss Resolver feature.
 *
 * <p>Four pins per spec §9:
 * <ol>
 *   <li>Alias outcome cascade — resolve via Tools page, assert both drafts gain link_to_existing_id.</li>
 *   <li>Tools → Pending Kanji — two rows render sorted desc; resolve clears one row.</li>
 *   <li>Modal translating→ready transition — synchronous mock makes fields enable immediately.</li>
 *   <li>Cancel / Escape / backdrop — modal closes without saving.</li>
 * </ol>
 */
@Tag("ui")
class UiNearMissTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @TempDir
    Path dataDir;

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private DraftActressRepository draftActressRepo;
    private DraftTitleRepository draftTitleRepo;
    private DraftTitleEnrichmentRepository draftEnrichRepo;
    private DraftTitleActressesRepository draftTitleActressesRepo;
    private StageNameLookupRepository lookupRepo;
    private StageNameSuggestionRepository suggestionRepo;
    private TranslationQueueRepository queueRepo;
    private TranslationService translationService;

    // Kanji shared by sibling draft actresses (pins 1, 2, 3, 4)
    static final String KANJI_A = "夏目彩春";
    // Separate kanji for a lone draft (pin 2)
    static final String KANJI_B = "高橋ひかる";

    @BeforeEach
    void setUp() throws Exception {
        // Use a file-based SQLite DB so Javalin's multi-threaded request handling can
        // share the same schema without SQLite single-connection serialization issues.
        Path dbFile = dataDir.resolve("test.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        connection = DriverManager.getConnection(jdbcUrl);
        jdbi = Jdbi.create(jdbcUrl);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        actressRepo             = new JdbiActressRepository(jdbi);
        draftActressRepo        = new DraftActressRepository(jdbi);
        draftTitleRepo          = new DraftTitleRepository(jdbi);
        draftEnrichRepo         = new DraftTitleEnrichmentRepository(jdbi);
        draftTitleActressesRepo = new DraftTitleActressesRepository(jdbi);
        lookupRepo              = new JdbiStageNameLookupRepository(jdbi);
        suggestionRepo          = new JdbiStageNameSuggestionRepository(jdbi);
        queueRepo               = new JdbiTranslationQueueRepository(jdbi);
        translationService      = mock(TranslationService.class);

        // By default, resolveOrSuggestStageName returns immediately for Pin 3.
        when(translationService.resolveOrSuggestStageName(anyString()))
                .thenReturn(Optional.of("Natsume Ayaharu"));

        server = buildNearMissServer();
        server.start();

        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        context = browser.newContext();
        page = context.newPage();
        page.onConsoleMessage(this::recordIfError);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (context   != null) context.close();
        if (browser   != null) browser.close();
        if (playwright != null) playwright.close();
        if (server    != null) server.stop();
        if (connection != null) connection.close();
    }

    // ── Pin 1: Alias outcome → cascade ───────────────────────────────────────

    /**
     * Seeds two draft_actresses with the same kanji plus one existing actress to alias to.
     * Opens the modal via Tools → Pending Kanji (same modal as editor "?" badge).
     * Picks the existing actress candidate, saves.
     * Asserts both draft_actress rows gain link_to_existing_id via DB assertion.
     *
     * The "?" badge in the title editor uses the identical modal code path; the cascade
     * logic (both drafts linked) is the critical invariant verified here.
     */
    @Test
    void pin1_aliasOutcomeCascadesBothDrafts() {
        long actressId = seedActress("Iroha Natsume");
        // Pre-seed suggestion so modal goes straight to "ready" state
        seedSuggestion(KANJI_A, "Natsume Iroha");
        // Seed alias so the fuzzy matcher returns the candidate
        actressRepo.insertAliasIfAbsent(actressId, "Natsume Iroha");

        seedDraftActress("slug-a1", KANJI_A);
        seedDraftActress("slug-a2", KANJI_A);

        navigateToPendingKanjiView();
        waitForKanjiRow(KANJI_A);

        // Click Resolve on the KANJI_A row
        page.locator(".upk-row")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(KANJI_A))
                .locator(".upk-resolve-btn").click();

        page.locator("#nm-overlay").waitFor();
        // Wait for ready state: Last name input is enabled (suggestion pre-seeded → no poll delay)
        page.locator("#nm-last").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
        page.waitForCondition(() -> page.locator("#nm-last").isEnabled());

        // Confirm-line reports 2 drafts
        String confirmText = page.locator(".nm-confirm-line").textContent();
        assertTrue(confirmText.contains("2"),
                "Expected '2' in confirm line, got: " + confirmText);

        // Pick the existing actress from the candidate list (loads async after ready)
        page.waitForCondition(() -> page.locator(".nm-candidate").count() > 0);
        page.locator(".nm-candidate").first().click();

        // Save becomes enabled once a candidate is selected (ALIAS outcome)
        page.waitForCondition(() -> !page.locator("#nm-save").isDisabled());
        page.locator("#nm-save").click();

        // Modal closes
        page.waitForCondition(() -> page.locator("#nm-overlay").count() == 0);

        // Both drafts must now have link_to_existing_id pointing at the actress
        assertDraftLinkedInDb("slug-a1", actressId);
        assertDraftLinkedInDb("slug-a2", actressId);

        assertConsoleClean();
    }

    // ── Pin 2: Tools Pending Kanji list → rows render → resolve clears row ────

    /**
     * Seeds three drafts: kanji-A×2, kanji-B×1. Verifies the table shows 2 rows
     * sorted by count desc (A first). Resolves kanji-A via ALIAS outcome.
     * Asserts only kanji-B remains after the view auto-refreshes.
     */
    @Test
    void pin2_pendingKanjiListRendersAndClearsAfterAliasResolve() {
        long actressId = seedActress("Iroha Natsume");
        seedSuggestion(KANJI_A, "Natsume Iroha");
        seedSuggestion(KANJI_B, "Takahashi Hikaru");
        actressRepo.insertAliasIfAbsent(actressId, "Natsume Iroha");

        seedDraftActress("slug-a1", KANJI_A);
        seedDraftActress("slug-a2", KANJI_A);
        seedDraftActress("slug-b1", KANJI_B);

        navigateToPendingKanjiView();

        // Table renders 2 rows (one per distinct kanji)
        page.locator("#tools-pending-kanji-view table").waitFor();
        page.waitForCondition(() -> page.locator(".upk-row").count() == 2);

        // First row is KANJI_A (count=2, sorted desc)
        String firstKanji = page.locator(".upk-row .upk-kanji-cell").first().textContent().trim();
        assertEquals(KANJI_A, firstKanji);

        String firstCount = page.locator(".upk-row .upk-count-cell").first().textContent().trim();
        assertEquals("2", firstCount);

        // Click Resolve on the first row
        page.locator(".upk-row").first().locator(".upk-resolve-btn").click();
        page.locator("#nm-overlay").waitFor();

        // Wait for ready state (suggestion pre-seeded → goes straight to ready)
        page.locator("#nm-last").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
        page.waitForCondition(() -> page.locator("#nm-last").isEnabled());

        // Pick candidate (ALIAS outcome default)
        page.waitForCondition(() -> page.locator(".nm-candidate").count() > 0);
        page.locator(".nm-candidate").first().click();

        page.waitForCondition(() -> !page.locator("#nm-save").isDisabled());
        page.locator("#nm-save").click();

        // Modal closes
        page.waitForCondition(() -> page.locator("#nm-overlay").count() == 0);

        // Tools page auto-refreshes: only kanji-B row remains
        page.waitForCondition(() -> page.locator(".upk-row").count() == 1);
        String remaining = page.locator(".upk-row .upk-kanji-cell").first().textContent().trim();
        assertEquals(KANJI_B, remaining);

        assertConsoleClean();
    }

    // ── Pin 3: Modal translating→ready transition ──────────────────────────────

    /**
     * Seeds a draft with a kanji that has NO suggestion row (status: missing on first poll).
     * The mocked TranslationService returns romaji synchronously when invoked.
     * After the stage-name-translate call completes, the modal must transition from
     * the disabled translating state to the ready state with form fields enabled.
     */
    @Test
    void pin3_modalTransitionsFromTranslatingToReady() {
        // KANJI_B has no suggestion row → status endpoint returns "missing" → modal triggers POST translate
        seedDraftActress("slug-b1", KANJI_B);

        navigateToPendingKanjiView();
        page.locator("#tools-pending-kanji-view table").waitFor();

        page.locator(".upk-resolve-btn").first().click();
        page.locator("#nm-overlay").waitFor();

        // Initially the fields are disabled (translating or loading state)
        // Wait for transition to ready: Last name input becomes enabled
        page.locator("#nm-last").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
        page.waitForCondition(() -> page.locator("#nm-last").isEnabled());

        // Romaji from the mock ("Natsume Ayaharu") should appear in the translation row
        String translationText = page.locator(".nm-translation-value").textContent();
        assertFalse(translationText.isBlank(),
                "Expected non-blank romaji in translation row after ready transition");

        // Cancel cleanly
        page.locator("#nm-cancel").click();
        page.waitForCondition(() -> page.locator("#nm-overlay").count() == 0);

        assertConsoleClean();
    }

    // ── Pin 4: Cancel / Escape / backdrop ─────────────────────────────────────

    /**
     * Verifies the modal closes on Escape key, backdrop click, and Cancel button —
     * without persisting any resolution. Confirms the kanji is still listed after each close.
     */
    @Test
    void pin4_modalClosesWithoutSavingOnEscapeCancelAndBackdrop() {
        seedSuggestion(KANJI_A, "Natsume Iroha");
        seedDraftActress("slug-a1", KANJI_A);

        navigateToPendingKanjiView();
        page.locator("#tools-pending-kanji-view table").waitFor();

        // ── Escape ────────────────────────────────────────────────────────────
        openModalFromFirstRow();
        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.locator("#nm-overlay").count() == 0);
        assertDraftStillUnresolved("slug-a1");
        waitForKanjiRow(KANJI_A); // row still present

        // ── Backdrop click ────────────────────────────────────────────────────
        openModalFromFirstRow();
        // Click the overlay background — position (5,5) is outside the modal card
        page.locator("#nm-overlay").click(
                new com.microsoft.playwright.Locator.ClickOptions().setPosition(5, 5));
        page.waitForCondition(() -> page.locator("#nm-overlay").count() == 0);
        assertDraftStillUnresolved("slug-a1");
        waitForKanjiRow(KANJI_A);

        // ── Cancel button ─────────────────────────────────────────────────────
        openModalFromFirstRow();
        page.locator("#nm-cancel").click();
        page.waitForCondition(() -> page.locator("#nm-overlay").count() == 0);
        assertDraftStillUnresolved("slug-a1");

        // Kanji is still in the list after all three cancels
        assertTrue(page.locator(".upk-row").count() >= 1,
                "Kanji should still appear in list after cancel");

        assertConsoleClean();
    }

    // ── Fixture builder ────────────────────────────────────────────────────────

    private WebServer buildNearMissServer() {
        var actressFuzzyMatcher = new ActressFuzzyMatcher(actressRepo);
        var resolveService      = new NearMissResolveService(actressRepo, draftActressRepo);

        var curationRoutes = new CurationRoutes(
                resolveService, draftActressRepo, actressRepo,
                actressFuzzyMatcher, lookupRepo, suggestionRepo, queueRepo,
                translationService);

        var populator        = mock(DraftPopulator.class);
        var imageFetcher     = mock(ImageFetcher.class);
        var promotionService = mock(DraftPromotionService.class);
        var patchService     = new DraftPatchService(jdbi, draftTitleRepo, draftActressRepo,
                                                     draftTitleActressesRepo);
        var coverStore       = new DraftCoverScratchStore(dataDir);

        var draftRoutes = new DraftRoutes(
                populator, draftTitleRepo, draftEnrichRepo,
                draftTitleActressesRepo, draftActressRepo,
                coverStore, imageFetcher, promotionService, patchService,
                new ObjectMapper(), jdbi);

        WebServer ws = new WebServer(0,
                UiTestFixture.buildStockedTitleBrowse(),
                UiTestFixture.buildStockedActressBrowse(),
                null, null, null, null, null, null,
                UiTestFixture.buildStockedSearch());

        ws.registerCuration(curationRoutes);
        ws.registerDraftRoutes(draftRoutes);

        ws.registerJavdbDiscovery(new JavdbDiscoveryRoutes(
                UiTestFixture.buildStockedJavdbService(),
                mock(JavdbEnrichmentActionService.class)));

        // Stub the UnsortedEditorRoutes endpoints that are NOT registered elsewhere.
        // /api/tags, /api/queues/volumes, and /api/actresses are already wired by the
        // WebServer base constructor (via TitleRoutes, registerRoutes, ActressRoutes).
        ws.registerRaw(app -> {
            app.get("/api/unsorted/titles",           ctx -> ctx.json(List.of()));
            app.get("/api/unsorted/actresses/search", ctx -> ctx.json(List.of()));
        });

        return ws;
    }

    // ── Seed helpers ───────────────────────────────────────────────────────────

    private long seedActress(String canonicalName) {
        return jdbi.withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO actresses(canonical_name, tier, first_seen_at, is_sentinel)
                    VALUES (:name, 'LIBRARY', :today, 0)
                    """)
                    .bind("name",  canonicalName)
                    .bind("today", java.time.LocalDate.now().toString())
                    .execute();
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    private void seedDraftActress(String slug, String kanji) {
        DraftActress da = DraftActress.builder()
                .javdbSlug(slug)
                .stageName(TranslationNormalization.normalize(kanji))
                .createdAt(now())
                .updatedAt(now())
                .build();
        draftActressRepo.upsertBySlug(da);
    }

    private void seedSuggestion(String kanji, String romaji) {
        suggestionRepo.recordSuggestion(
                TranslationNormalization.normalize(kanji), romaji, now());
    }

    // ── Assertion helpers ──────────────────────────────────────────────────────

    private void assertDraftLinkedInDb(String slug, long expectedActressId) {
        Optional<DraftActress> da = draftActressRepo.findBySlug(slug);
        assertTrue(da.isPresent(), "Draft actress not found: " + slug);
        Long linkId = da.get().getLinkToExistingId();
        assertNotNull(linkId, slug + " should have link_to_existing_id after alias cascade");
        assertEquals(expectedActressId, linkId.longValue(),
                slug + " should link to actress " + expectedActressId);
    }

    private void assertDraftStillUnresolved(String slug) {
        Optional<DraftActress> da = draftActressRepo.findBySlug(slug);
        assertTrue(da.isPresent(), "Draft actress not found: " + slug);
        assertNull(da.get().getLinkToExistingId(),
                slug + " should NOT be linked after cancel, found: " + da.get().getLinkToExistingId());
        assertNull(da.get().getLinkToDraftSlug(),
                slug + " should NOT have link_to_draft_slug after cancel");
    }

    // ── Navigation helpers ─────────────────────────────────────────────────────

    private void navigateToPendingKanjiView() {
        page.navigate(baseUrl() + "/");
        page.locator("#action-btn").click();
        page.locator("#action-landing").waitFor();
        page.locator("#tools-pending-kanji-btn").click();
    }

    private void waitForKanjiRow(String kanji) {
        page.waitForCondition(() ->
                page.locator(".upk-row .upk-kanji-cell").allTextContents().stream()
                        .anyMatch(t -> t.trim().equals(kanji)));
    }

    private void openModalFromFirstRow() {
        // The view should already be showing the kanji table
        page.locator(".upk-resolve-btn").first().click();
        page.locator("#nm-overlay").waitFor();
        page.waitForCondition(() -> page.locator("#nm-save").count() > 0);
    }

    // ── Misc helpers ───────────────────────────────────────────────────────────

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private static String now() {
        return ISO_UTC.format(Instant.now());
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) {
            consoleErrors.add(msg.text());
        }
    }

    private void assertConsoleClean() {
        // Filter noise that is harmless in the test environment:
        //   • "Failed to load resource" / status 404 — fixture doesn't serve every endpoint
        //   • net::ERR_* — network errors from optional stubs that aren't wired
        //   • "Failed to load health checks" / "Failed to load latest report" — from health.js /
        //     utilities-health.js polling /api/health and /api/utilities/report; Javalin returns
        //     "Endpoint GET /api/... not found" plain text which the JS can't parse as JSON.
        //     These are fixture-gap noise, not production JS bugs.
        //     SURFACED: health.js and utilities-health.js JS error-handler messages propagate to
        //     console.error even though the fetch failure is expected in this fixture setup.
        List<String> realErrors = consoleErrors.stream()
                .filter(e -> !e.contains("Failed to load resource"))
                .filter(e -> !e.contains("the server responded with a status"))
                .filter(e -> !e.contains("404"))
                .filter(e -> !e.contains("net::ERR_"))
                .filter(e -> !e.contains("Failed to load health checks"))
                .filter(e -> !e.contains("Failed to load latest report"))
                .toList();
        assertTrue(realErrors.isEmpty(),
                "Unexpected console JS errors in this test: " + realErrors);
        consoleErrors.clear();
    }
}
