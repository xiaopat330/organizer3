package com.organizer3.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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
import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.ActressSummary;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Playwright UI pins for Phase 6d Slice C — alias-capture modal on manual re-link.
 *
 * <p>Pins:
 * <ol>
 *   <li>Link to Actress whose name already covers both kanji and romaji → modal does NOT open.</li>
 *   <li>Link to Actress covering neither → modal opens with both rows + 4 buttons.</li>
 *   <li>Click "Add both" → PUT called with merged aliases, modal dismisses.</li>
 *   <li>Click "Skip" → no PUT, modal dismisses.</li>
 *   <li>Only-kanji-needed → modal shows single row + 2 buttons (Add alias / Skip).</li>
 * </ol>
 */
@Tag("ui")
class UiAliasCaptureTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // Cast slot with a kanji stage name and a romaji suggestion seeded
    static final String KANJI      = "蒼井そら";
    static final String SLUG       = "ac-test-slug";
    static final long   TITLE_ID   = 9951L;
    static final String CODE       = "ACT-001";

    // Actress whose name already covers both kanji and romaji
    static final long   ACTRESS_COVERED     = 101L;
    static final String ACTRESS_COVERED_NAME = "Sora Aoi";

    // Actress covering neither kanji nor romaji
    static final long   ACTRESS_UNCOVERED     = 102L;
    static final String ACTRESS_UNCOVERED_NAME = "Different Name";

    // Actress covering romaji but not kanji (only-kanji needed)
    static final long   ACTRESS_ROMAJI_COVERED   = 103L;
    static final String ACTRESS_ROMAJI_COVERED_NAME = "Sora Aoi";   // same canonical as romaji

    @TempDir
    Path dataDir;

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors    = new ArrayList<>();
    private final List<String> consoleInfo      = new ArrayList<>();

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

    /** Captures aliases sent to PUT /api/actresses/{id}/aliases; null if not called. */
    private AtomicReference<List<String>> capturedAliases;
    private ActressBrowseService actressBrowse;

    private long draftTitleId;

    @BeforeEach
    void setUp() throws Exception {
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
        when(translationService.resolveOrSuggestStageName(anyString()))
                .thenReturn(Optional.empty());

        capturedAliases = new AtomicReference<>(null);
        actressBrowse   = buildActressBrowseMock();

        seedTitle();
        seedDraftActress();
        draftTitleId = seedDraftTitle();
        seedDraftTitleActress(draftTitleId);
        // Seed a suggestion so status returns "ready" and auto-fill populates "Sora Aoi"
        suggestionRepo.recordSuggestion(TranslationNormalization.normalize(KANJI), "Sora Aoi", now());

        server = buildServer();
        server.start();

        playwright = Playwright.create();
        browser    = playwright.chromium().launch();
        context    = browser.newContext();
        page       = context.newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) consoleErrors.add(msg.text());
            if ("info".equals(msg.type()))  consoleInfo.add(msg.text());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (context    != null) context.close();
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
        if (server     != null) server.stop();
        if (connection != null) connection.close();
    }

    // ── Pin 1: already-covered → no modal ─────────────────────────────────

    @Test
    void pin1_alreadyCovered_noModal() {
        // Actress 101: canonical="Sora Aoi", alias=["蒼井そら"]
        // After auto-fill fills "Sora Aoi", BOTH are covered → modal must not open.
        navigateToDraftEditorAndWaitAutoFill();

        selectActress(ACTRESS_COVERED_NAME);

        // Modal must NOT appear.
        page.waitForCondition(() -> {
            // Give the network round-trip time to resolve.
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            return true;
        });
        assertEquals(0, page.locator(".ac-overlay").count(),
                "Modal should NOT open when actress already covers both names");
        assertNull(capturedAliases.get(), "PUT must not be called");
        assertConsoleClean();
    }

    // ── Pin 2: neither covered → modal opens with both rows + 4 buttons ───

    @Test
    void pin2_neitherCovered_modalOpensBothRows() {
        // Actress 102: canonical="Different Name", no aliases → both kanji and romaji uncovered.
        navigateToDraftEditorAndWaitAutoFill();

        selectActress(ACTRESS_UNCOVERED_NAME);

        // Wait for the modal to open.
        page.waitForCondition(() -> page.locator(".ac-overlay").count() > 0);

        // Both alias rows must be present.
        assertEquals(2, page.locator(".ac-alias-row").count(),
                "Expected 2 alias rows (kanji + romaji)");

        // Buttons: Add both, Add kanji only, Add romaji only, Skip.
        assertNotNull(page.locator("#ac-add-both").elementHandle(),   "Add both button missing");
        assertNotNull(page.locator("#ac-add-kanji").elementHandle(),  "Add kanji only button missing");
        assertNotNull(page.locator("#ac-add-romaji").elementHandle(), "Add romaji only button missing");
        assertNotNull(page.locator("#ac-skip").elementHandle(),       "Skip button missing");

        // Verify INFO log was emitted.
        page.waitForCondition(() -> consoleInfo.stream().anyMatch(m -> m.contains("alias-capture: trigger")));
        assertTrue(consoleInfo.stream().anyMatch(m -> m.contains("actressId=" + ACTRESS_UNCOVERED)),
                "Expected alias-capture trigger log for actressId=" + ACTRESS_UNCOVERED);

        assertConsoleClean();
    }

    // ── Pin 3: Add both → PUT called with merged aliases, modal dismissed ─

    @Test
    void pin3_addBoth_putCalledAndModalDismisses() {
        navigateToDraftEditorAndWaitAutoFill();
        selectActress(ACTRESS_UNCOVERED_NAME);
        page.waitForCondition(() -> page.locator(".ac-overlay").count() > 0);

        // Click "Add both".
        page.locator("#ac-add-both").click();

        // Modal must dismiss.
        page.waitForCondition(() -> page.locator(".ac-overlay").count() == 0);

        // PUT must have been called with both aliases in the list.
        page.waitForCondition(() -> capturedAliases.get() != null);
        List<String> sent = capturedAliases.get();
        assertTrue(sent.contains(KANJI),      "PUT must include kanji alias");
        assertTrue(sent.contains("Sora Aoi"), "PUT must include romaji alias");

        // Dismissal log must appear.
        page.waitForCondition(() -> consoleInfo.stream().anyMatch(m -> m.contains("dismissed via=add_both")));
        assertConsoleClean();
    }

    // ── Pin 4: Skip → no PUT, modal dismissed ─────────────────────────────

    @Test
    void pin4_skip_noPutAndModalDismisses() {
        navigateToDraftEditorAndWaitAutoFill();
        selectActress(ACTRESS_UNCOVERED_NAME);
        page.waitForCondition(() -> page.locator(".ac-overlay").count() > 0);

        page.locator("#ac-skip").click();

        // Modal must dismiss.
        page.waitForCondition(() -> page.locator(".ac-overlay").count() == 0);

        // Small delay to confirm PUT was NOT called.
        page.waitForCondition(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            return true;
        });
        assertNull(capturedAliases.get(), "PUT must NOT be called on Skip");

        page.waitForCondition(() -> consoleInfo.stream().anyMatch(m -> m.contains("dismissed via=skip")));
        assertConsoleClean();
    }

    // ── Pin 5: only-kanji-needed → single row + 2 buttons ─────────────────

    @Test
    void pin5_onlyKanjiNeeded_singleRowTwoButtons() {
        // Actress 103: canonical="Sora Aoi" (matches auto-filled romaji), no kanji alias.
        // Romaji "Sora Aoi" == canonicalName → romajiNeedsAlias=false.
        // Kanji "蒼井そら" not in aliases → kanjiNeedsAlias=true.
        navigateToDraftEditorAndWaitAutoFill();

        selectActress(ACTRESS_ROMAJI_COVERED_NAME + "3");  // search text unique to actress 103

        page.waitForCondition(() -> page.locator(".ac-overlay").count() > 0);

        // Only one alias row (kanji).
        assertEquals(1, page.locator(".ac-alias-row").count(),
                "Expected 1 alias row (kanji only)");

        // Buttons: Add alias and Skip (2-button form).
        assertNotNull(page.locator("#ac-add-one").elementHandle(), "Add alias button missing");
        assertNotNull(page.locator("#ac-skip").elementHandle(),    "Skip button missing");
        assertEquals(0, page.locator("#ac-add-both").count(),   "Add both must not be present");
        assertEquals(0, page.locator("#ac-add-kanji").count(),  "Add kanji only must not be present");
        assertEquals(0, page.locator("#ac-add-romaji").count(), "Add romaji only must not be present");

        assertConsoleClean();
    }

    // ── Seed helpers ───────────────────────────────────────────────────────

    private void seedTitle() {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (:id, :code, :code, 'ACT', 1)")
                .bind("id",   TITLE_ID)
                .bind("code", CODE)
                .execute());
    }

    private void seedDraftActress() {
        DraftActress da = DraftActress.builder()
                .javdbSlug(SLUG)
                .stageName(TranslationNormalization.normalize(KANJI))
                .createdAt(now())
                .updatedAt(now())
                .build();
        draftActressRepo.upsertBySlug(da);
    }

    private long seedDraftTitle() {
        DraftTitle dt = DraftTitle.builder()
                .titleId(TITLE_ID)
                .code(CODE)
                .titleOriginal("テスト作品2")
                .createdAt(now())
                .updatedAt(now())
                .build();
        return draftTitleRepo.insert(dt);
    }

    private void seedDraftTitleActress(long draftId) {
        draftTitleActressesRepo.replaceForDraft(draftId,
                List.of(new DraftTitleActress(draftId, SLUG, "unresolved")));
    }

    // ── Mock builder ───────────────────────────────────────────────────────

    private ActressBrowseService buildActressBrowseMock() {
        ActressBrowseService mock = UiTestFixture.buildStockedActressBrowse();

        // Actress 101: covers both kanji (via alias) and romaji "Sora Aoi" (canonical).
        ActressSummary coveredActress = ActressSummary.builder()
                .id(ACTRESS_COVERED).canonicalName(ACTRESS_COVERED_NAME).tier("GODDESS")
                .favorite(false).bookmark(false).rejected(false).titleCount(0)
                .coverUrls(List.of()).folderPaths(List.of())
                .aliases(List.of(ActressSummary.AliasDto.builder().name(KANJI).actressId(null).build()))
                .build();
        when(mock.findById(ACTRESS_COVERED)).thenReturn(Optional.of(coveredActress));

        // Actress 102: covers neither → modal should fire.
        ActressSummary uncoveredActress = ActressSummary.builder()
                .id(ACTRESS_UNCOVERED).canonicalName(ACTRESS_UNCOVERED_NAME).tier("A")
                .favorite(false).bookmark(false).rejected(false).titleCount(0)
                .coverUrls(List.of()).folderPaths(List.of())
                .aliases(List.of())
                .build();
        when(mock.findById(ACTRESS_UNCOVERED)).thenReturn(Optional.of(uncoveredActress));

        // Actress 103: canonical="Sora Aoi" (covers romaji), no kanji alias → only kanji needed.
        ActressSummary romajiCoveredActress = ActressSummary.builder()
                .id(ACTRESS_ROMAJI_COVERED).canonicalName(ACTRESS_ROMAJI_COVERED_NAME).tier("A")
                .favorite(false).bookmark(false).rejected(false).titleCount(0)
                .coverUrls(List.of()).folderPaths(List.of())
                .aliases(List.of())
                .build();
        when(mock.findById(ACTRESS_ROMAJI_COVERED)).thenReturn(Optional.of(romajiCoveredActress));

        // updateAliases: capture the aliases list and return success.
        when(mock.updateAliases(anyLong(), org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            List<String> submitted = inv.getArgument(1);
            capturedAliases.set(submitted);
            return ActressBrowseService.AliasUpdateResult.success();
        });

        return mock;
    }

    // ── Navigation + helpers ───────────────────────────────────────────────

    /**
     * Navigate to the draft editor and wait for auto-fill to complete (suggestion is pre-seeded
     * as 'ready', so auto-fill fires on first poll). Returns after the autofill cue appears.
     */
    private void navigateToDraftEditorAndWaitAutoFill() {
        page.navigate(baseUrl() + "/");
        page.locator("#action-btn").click();
        page.locator("#action-landing").waitFor();
        page.locator("#tools-queue-btn").click();
        page.locator("#tools-queue-view").waitFor();
        page.waitForCondition(() -> page.locator(".queue-list-item").count() > 0);
        page.locator(".queue-list-item").first().click();
        page.locator("#queue-draft-pane").waitFor();
        page.waitForCondition(() ->
                !"none".equals(page.locator("#queue-draft-pane").evaluate("el => el.style.display")));

        // Speed up poll interval so auto-fill fires quickly.
        page.evaluate("window.__phase6dPollMs = 200");

        // Wait for auto-fill cue — confirms suggestion is "ready" and fields are filled.
        page.waitForCondition(() -> page.locator(".sn-autofill-cue").count() > 0);
    }

    /**
     * Types the given text into the actress search box and clicks the first matching suggestion.
     */
    private void selectActress(String searchText) {
        // The search input is inside .queue-cast-picker-search.
        page.locator(".queue-cast-picker-input[placeholder*='Search']").fill(searchText);
        page.waitForCondition(() -> page.locator(".queue-cast-picker-suggest-item").count() > 0);
        page.locator(".queue-cast-picker-suggest-item").first().click();
    }

    // ── Server builder ─────────────────────────────────────────────────────

    private WebServer buildServer() {
        var actressFuzzyMatcher = new ActressFuzzyMatcher(actressRepo);
        var resolveService      = new NearMissResolveService(actressRepo, draftActressRepo);

        var curationRoutes = new CurationRoutes(
                resolveService, draftActressRepo, actressRepo,
                actressFuzzyMatcher, lookupRepo, suggestionRepo, queueRepo,
                translationService, jdbi);

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
                actressBrowse,
                null, null, null, null, null, null,
                UiTestFixture.buildStockedSearch());

        ws.registerCuration(curationRoutes);
        ws.registerDraftRoutes(draftRoutes);

        ws.registerJavdbDiscovery(new JavdbDiscoveryRoutes(
                UiTestFixture.buildStockedJavdbService(),
                mock(JavdbEnrichmentActionService.class)));

        ws.registerRaw(app -> {
            app.get("/api/unsorted/titles", ctx -> ctx.json(List.of(
                    Map.of(
                            "titleId",      TITLE_ID,
                            "code",         CODE,
                            "folderName",   CODE,
                            "actressCount", 1,
                            "hasCover",     false,
                            "complete",     false
                    )
            )));
            app.get("/api/unsorted/titles/" + TITLE_ID, ctx -> ctx.json(Map.of(
                    "titleId",    TITLE_ID,
                    "code",       CODE,
                    "folderName", CODE,
                    "detail",     Map.of("folderName", CODE),
                    "directTags", List.of()
            )));
            // Actress search: return all three test actresses so pins can pick the right one.
            app.get("/api/unsorted/actresses/search", ctx -> {
                String q = ctx.queryParam("q");
                if (q == null) q = "";
                List<Map<String, Object>> hits = new ArrayList<>();
                if (ACTRESS_COVERED_NAME.toLowerCase().contains(q.toLowerCase()) && !q.contains("3")) {
                    hits.add(Map.of("id", ACTRESS_COVERED, "canonicalName", ACTRESS_COVERED_NAME,
                            "isSentinel", false));
                }
                if (ACTRESS_UNCOVERED_NAME.toLowerCase().contains(q.toLowerCase())) {
                    hits.add(Map.of("id", ACTRESS_UNCOVERED, "canonicalName", ACTRESS_UNCOVERED_NAME,
                            "isSentinel", false));
                }
                // Pin 5: search "Sora Aoi3" to uniquely select actress 103.
                if (q.contains("3")) {
                    hits.add(Map.of("id", ACTRESS_ROMAJI_COVERED, "canonicalName", ACTRESS_ROMAJI_COVERED_NAME,
                            "isSentinel", false));
                }
                ctx.json(hits);
            });
        });

        return ws;
    }

    // ── Misc helpers ───────────────────────────────────────────────────────

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private static String now() {
        return ISO_UTC.format(Instant.now());
    }

    private void assertConsoleClean() {
        List<String> realErrors = consoleErrors.stream()
                .filter(e -> !e.contains("Failed to load resource"))
                .filter(e -> !e.contains("the server responded with a status"))
                .filter(e -> !e.contains("404"))
                .filter(e -> !e.contains("net::ERR_"))
                .filter(e -> !e.contains("Failed to load health checks"))
                .filter(e -> !e.contains("Failed to load latest report"))
                .toList();
        assertTrue(realErrors.isEmpty(),
                "Unexpected console JS errors: " + realErrors);
        consoleErrors.clear();
    }
}
