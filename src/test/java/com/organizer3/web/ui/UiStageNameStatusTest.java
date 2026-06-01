package com.organizer3.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
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
 * Playwright UI pins for Phase 6d Slice B1 — stage-name translation badge in the draft editor.
 *
 * <p>Two pins:
 * <ol>
 *   <li>Queued state → "translating…" badge appears for a kanji-only cast slot.</li>
 *   <li>Ready transition → badge is replaced by "Suggested: &lt;romaji&gt;" reveal;
 *       English name inputs remain empty.</li>
 * </ol>
 *
 * <p>Poll interval is overridden via {@code window.__phase6dPollMs = 200} before mount
 * so the test completes in well under a second rather than waiting 5 s.
 */
@Tag("ui")
class UiStageNameStatusTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static final String KANJI = "蒼井そら";
    static final String SLUG  = "sn-status-test-slug";
    static final long   TITLE_ID = 9901L;
    static final String CODE     = "SNS-001";

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

    // surrogate FK for the seeded draft title row
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

        seedTitle();
        seedDraftActress();
        draftTitleId = seedDraftTitle();
        seedDraftTitleActress(draftTitleId);

        server = buildServer();
        server.start();

        playwright = Playwright.create();
        browser    = playwright.chromium().launch();
        context    = browser.newContext();
        page       = context.newPage();
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

    // ── Pin 1: queued → translating badge appears ─────────────────────────

    @Test
    void pin1_queuedState_translatingBadgeAppearsForKanjiSlot() {
        // Seed a pending queue row so stage-name-status returns "queued".
        seedQueuedRow(TranslationNormalization.normalize(KANJI));

        navigateToDraftEditor();

        // Override poll interval before the badge logic fires (200 ms so test stays fast).
        page.evaluate("window.__phase6dPollMs = 200");

        // The "translating…" badge should appear alongside the kanji stage name.
        page.waitForCondition(() -> page.locator(".sn-translating-badge").count() > 0);

        String badgeText = page.locator(".sn-translating-badge").first().textContent();
        assertTrue(badgeText.contains("translating"),
                "Expected badge text to contain 'translating', got: " + badgeText);

        // No English name inputs should be filled.
        assertEnglishInputsEmpty();

        assertConsoleClean();
    }

    // ── Pin 2: ready transition → badge gone, autofill cue present ────────
    // Updated for B2: badge disappears on ready; auto-fill cue appears (no B1 "Suggested:" reveal).

    @Test
    void pin2_readyTransition_badgeGone_autofillCueAppears() {
        // Start in queued state.
        seedQueuedRow(TranslationNormalization.normalize(KANJI));

        navigateToDraftEditor();

        // Set fast poll interval before the first poll fires.
        page.evaluate("window.__phase6dPollMs = 200");

        // Badge must appear first (queued).
        page.waitForCondition(() -> page.locator(".sn-translating-badge").count() > 0);

        // Transition to ready: insert a suggestion row; next poll will return "ready".
        seedSuggestion(TranslationNormalization.normalize(KANJI), "Aoi Sora");

        // Badge should disappear and the autofill cue should appear.
        page.waitForCondition(() -> page.locator(".sn-autofill-cue").count() > 0);
        page.waitForCondition(() -> page.locator(".sn-translating-badge").count() == 0);

        // B2: no old "Suggested:" read-only reveal.
        assertEquals(0, page.locator(".sn-suggested-reveal").count(),
                "B2 must not render the B1 Suggested reveal");

        // Cue text should mention accept/edit.
        String cueText = page.locator(".sn-autofill-cue").first().textContent();
        assertTrue(cueText.contains("filled") || cueText.contains("accept"),
                "Expected autofill cue text, got: " + cueText);

        assertConsoleClean();
    }

    // ── Pin 3: ready → auto-fills clean slot inputs ────────────────────────

    @Test
    void pin3_readyTransition_autoFillsCleanSlotInputs() {
        // Seed queued state then navigate.
        seedQueuedRow(TranslationNormalization.normalize(KANJI));

        navigateToDraftEditor();
        page.evaluate("window.__phase6dPollMs = 200");

        // Wait for badge (queued).
        page.waitForCondition(() -> page.locator(".sn-translating-badge").count() > 0);

        // Seed 2-token suggestion: "Yuyu Esumi" → first=Yuyu, last=Esumi.
        seedSuggestion(TranslationNormalization.normalize(KANJI), "Yuyu Esumi");

        // Wait for auto-fill cue to appear (signals fill is done).
        page.waitForCondition(() -> page.locator(".sn-autofill-cue").count() > 0);

        // Last-name input must contain "Esumi".
        String lastVal = (String) page.locator(".queue-cast-picker-name-input[data-name-field='last']")
                .first().evaluate("el => el.value");
        assertEquals("Esumi", lastVal,
                "Expected last-name input to be 'Esumi', got: " + lastVal);

        // First-name input must contain "Yuyu".
        String firstVal = (String) page.locator(".queue-cast-picker-name-input[data-name-field='first']")
                .first().evaluate("el => el.value");
        assertEquals("Yuyu", firstVal,
                "Expected first-name input to be 'Yuyu', got: " + firstVal);

        // B1 reveal must not be present.
        assertEquals(0, page.locator(".sn-suggested-reveal").count(),
                "B2 must not render the B1 Suggested reveal");

        assertConsoleClean();
    }

    // ── Pin 4: user typed before ready → auto-fill suppressed ─────────────

    @Test
    void pin4_userTypedBeforeReady_suppressesAutoFill() {
        // Seed queued state then navigate.
        seedQueuedRow(TranslationNormalization.normalize(KANJI));

        navigateToDraftEditor();
        page.evaluate("window.__phase6dPollMs = 200");

        // Open the Create-new form so the inputs are accessible.
        page.locator(".queue-cast-picker .queue-btn:not(.queue-btn-secondary)").first().click();

        // Type into the last-name input BEFORE the suggestion is seeded — marks it dirty.
        page.locator(".queue-cast-picker-name-input[data-name-field='last']").first().fill("Manual");

        // Now seed the suggestion so next poll returns "ready".
        seedSuggestion(TranslationNormalization.normalize(KANJI), "Yuyu Esumi");

        // Give polling time to cycle a few times and process ready.
        page.waitForCondition(() -> page.locator(".sn-translating-badge").count() == 0);

        // The manually typed value must be preserved.
        String lastVal = (String) page.locator(".queue-cast-picker-name-input[data-name-field='last']")
                .first().evaluate("el => el.value");
        assertEquals("Manual", lastVal,
                "Expected manually-typed value to be preserved, got: " + lastVal);

        // First-name input must remain empty (auto-fill skipped entirely).
        String firstVal = (String) page.locator(".queue-cast-picker-name-input[data-name-field='first']")
                .first().evaluate("el => el.value");
        assertTrue(firstVal == null || firstVal.isBlank(),
                "Expected first-name input to be empty (dirty slot), got: " + firstVal);

        // No autofill cue — dirty slot.
        assertEquals(0, page.locator(".sn-autofill-cue").count(),
                "Expected no autofill cue for dirty slot");

        assertConsoleClean();
    }

    // ── Pin 5: prefilled romaji at render time → inputs populated, no poll ──
    // Targets the v2 unprocessed surface (/v2-unprocessed.html → cast-pane.js).

    @Test
    void pin5_prefilledRomaji_populatesInputsOnInitialRender() {
        // Seed a DraftActress that already has english names (backend blocking-wait path).
        // No queued translation row → needsPoll is false; the UI must surface names directly
        // via the _fillCreateNew helper called from renderCast.
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE draft_actresses
                   SET english_first_name = 'Sora',
                       english_last_name  = 'Aoi',
                       updated_at         = :now
                 WHERE javdb_slug = :slug
                """)
                .bind("slug", SLUG)
                .bind("now",  now())
                .execute());

        navigateToV2DraftEditor();

        // No translating badge — the romaji was already ready at render time.
        assertEquals(0, page.locator(".sn-translating-badge").count(),
                "Expected no translating badge when romaji is pre-filled");

        // Autofill cue must appear synchronously on initial render (no poll).
        page.waitForCondition(() -> page.locator(".sn-autofill-cue").count() > 0);

        String cueText = page.locator(".sn-autofill-cue").first().textContent();
        assertTrue(cueText.contains("filled") || cueText.contains("accept"),
                "Expected autofill cue text, got: " + cueText);

        // Last-name input must contain "Aoi".
        String lastVal = (String) page.locator(".un-cast-picker-name-input[data-name-field='last']")
                .first().evaluate("el => el.value");
        assertEquals("Aoi", lastVal,
                "Expected last-name input to be 'Aoi', got: " + lastVal);

        // First-name input must contain "Sora".
        String firstVal = (String) page.locator(".un-cast-picker-name-input[data-name-field='first']")
                .first().evaluate("el => el.value");
        assertEquals("Sora", firstVal,
                "Expected first-name input to be 'Sora', got: " + firstVal);

        assertConsoleClean();
    }

    // ── Seed helpers ───────────────────────────────────────────────────────

    private void seedTitle() {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (:id, :code, :code, 'SNS', 1)")
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
                .titleOriginal("テスト作品")
                .createdAt(now())
                .updatedAt(now())
                .build();
        return draftTitleRepo.insert(dt);
    }

    private void seedDraftTitleActress(long draftId) {
        draftTitleActressesRepo.replaceForDraft(draftId,
                List.of(new DraftTitleActress(draftId, SLUG, "unresolved")));
    }

    /**
     * Seeds a pending translation_queue row for the given kanji.
     * Inserts the label_basic strategy row first if it doesn't exist.
     */
    private void seedQueuedRow(String normalizedKanji) {
        jdbi.useHandle(h -> {
            h.execute("""
                    INSERT OR IGNORE INTO translation_strategy(name, model_id, prompt_template)
                    VALUES ('label_basic', 'test-model', '{jp}')
                    """);
            long stratId = h.createQuery("SELECT id FROM translation_strategy WHERE name = 'label_basic'")
                    .mapTo(Long.class).one();
            h.createUpdate("""
                    INSERT INTO translation_queue(source_text, strategy_id, submitted_at, status)
                    VALUES (:src, :sid, :at, 'pending')
                    """)
                    .bind("src", normalizedKanji)
                    .bind("sid", stratId)
                    .bind("at",  now())
                    .execute();
        });
    }

    private void seedSuggestion(String normalizedKanji, String romaji) {
        suggestionRepo.recordSuggestion(normalizedKanji, romaji, now());
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    /**
     * Navigate to the v2 unprocessed surface and open the draft editor for the
     * seeded title. Targets /v2-unprocessed.html which mounts cast-pane.js.
     */
    private void navigateToV2DraftEditor() {
        page.navigate(baseUrl() + "/v2-unprocessed.html");
        // Wait for the sidebar to render at least one row.
        page.waitForCondition(() -> page.locator(".un-queue-row").count() > 0);
        // Click the first row to load the draft.
        page.locator(".un-queue-row").first().click();
        // Wait for the cast section to appear inside the editor pane.
        page.waitForCondition(() -> page.locator(".un-cast-section").count() > 0);
    }

    private void navigateToDraftEditor() {
        page.navigate(baseUrl() + "/");
        page.locator("#action-btn").click();
        page.locator("#action-landing").waitFor();
        // tools-queue-btn opens the Curation view (unprocessed tab = title editor).
        page.locator("#tools-queue-btn").click();
        // Wait for the queue view with the sidebar to appear.
        page.locator("#tools-queue-view").waitFor();
        // The sidebar should show our seeded title; click it to open the draft pane.
        page.waitForCondition(() -> page.locator(".queue-list-item").count() > 0);
        page.locator(".queue-list-item").first().click();
        // Wait for the draft pane to become visible.
        page.locator("#queue-draft-pane").waitFor();
        page.waitForCondition(() ->
                !"none".equals(page.locator("#queue-draft-pane").evaluate("el => el.style.display")));
    }

    // ── Assertion helpers ──────────────────────────────────────────────────

    private void assertEnglishInputsEmpty() {
        // The Create-new form inputs for last/first name must be empty (B1: no auto-fill).
        page.locator(".queue-cast-picker-name-input").all().forEach(loc -> {
            String val = (String) loc.evaluate("el => el.value");
            assertTrue(val == null || val.isBlank(),
                    "Expected English name input to be empty, got: " + val);
        });
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
                UiTestFixture.buildStockedActressBrowse(),
                null, null, null, null, null, null,
                UiTestFixture.buildStockedSearch());

        ws.registerCuration(curationRoutes);
        ws.registerDraftRoutes(draftRoutes);

        ws.registerJavdbDiscovery(new JavdbDiscoveryRoutes(
                UiTestFixture.buildStockedJavdbService(),
                mock(JavdbEnrichmentActionService.class)));

        // Stub the queue sidebar and title detail endpoints.
        // NOTE: /api/drafts and /api/drafts/{titleId} are already registered by DraftRoutes.
        //       /api/tags is already registered by TitleRoutes (via WebServer base constructor).
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
            // title detail fetched in parallel by loadDetail; needed for the no-draft path
            // and for deriving folderName in the draft-pane header.
            app.get("/api/unsorted/titles/" + TITLE_ID, ctx -> ctx.json(Map.of(
                    "titleId",    TITLE_ID,
                    "code",       CODE,
                    "folderName", CODE,
                    "detail",     Map.of("folderName", CODE),
                    "directTags", List.of()
            )));
            app.get("/api/unsorted/actresses/search", ctx -> ctx.json(List.of()));
        });

        return ws;
    }

    // ── Misc helpers ────────────────────────��──────────────────────────────

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private static String now() {
        return ISO_UTC.format(Instant.now());
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) consoleErrors.add(msg.text());
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
