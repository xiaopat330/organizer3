package com.organizer3.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPromotionService;
import com.organizer3.javdb.draft.DraftTitleActressesRepository;
import com.organizer3.javdb.draft.DraftTitleEnrichmentRepository;
import com.organizer3.javdb.draft.DraftTitleRepository;
import com.organizer3.web.ImageFetcher;
import com.organizer3.web.JavdbEnrichmentActionService;
import com.organizer3.web.WebServer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Playwright UI pins for the v1 (legacy) Tools→Curation processed/terminal state — the
 * v1 parity port of {@link UiProcessedStateTest} (which covers the v2 standalone page).
 *
 * <p>v1 lives in the main {@code index.html} Tools→Curation tab, reached via
 * {@code #action-btn → #tools-queue-btn → #tools-queue-view} (navigation modeled on
 * {@code UiAliasCaptureTest}). v1 queue rows carry no {@code data-title-id}; they are
 * selected by their visible product code text.
 *
 * <p>Pins:
 * <ol>
 *   <li>A processed queue row ({@code processed:true}) shows the "✓ processed" pill;
 *       a plain row does not.</li>
 *   <li>Opening a processed title in no-draft mode: Skip and Enrich are disabled and the
 *       "Processed via javdb" header badge is visible.</li>
 * </ol>
 *
 * <p>The backend {@code curated_at → processed} mapping is tested in backend repository
 * tests; this pin tests only the v1 JS/HTML contract.
 */
@Tag("ui")
class UiCurationProcessedStateTest {

    // A processed title — complete:false so it's visible in the default (hide-complete) view.
    static final long   PROCESSED_TITLE_ID = 8800L;
    static final String PROCESSED_CODE     = "PRC-001";

    // A non-processed title — confirms the pill is absent on unprocessed rows.
    static final long   PLAIN_TITLE_ID = 8801L;
    static final String PLAIN_CODE     = "PRC-002";

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

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = dataDir.resolve("test.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        connection = DriverManager.getConnection(jdbcUrl);
        jdbi = Jdbi.create(jdbcUrl);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        server = buildServer();
        server.start();

        playwright = Playwright.create();
        browser    = playwright.chromium().launch();
        context    = browser.newContext();
        page       = context.newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) consoleErrors.add(msg.text());
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

    // ── Pin 1: processed queue row shows "✓ processed" pill ──────────────

    @Test
    void pin1_processedQueueRow_showsProcessedPill() {
        navigateAndWaitForQueue();

        // The processed row (selected by its visible code) must have the pill.
        var processedRow = page.locator("#queue-list .queue-list-item",
                new Page.LocatorOptions().setHasText(PROCESSED_CODE));
        processedRow.first().waitFor();
        long pillCount = processedRow.locator(".queue-processed-pill").count();
        assertEquals(1, pillCount,
                "Expected processed queue row to have exactly one .queue-processed-pill");

        String pillText = processedRow.locator(".queue-processed-pill").textContent();
        assertTrue(pillText.contains("processed"),
                "Expected pill text to contain 'processed', got: " + pillText);

        // The plain (non-processed) row must NOT have the pill.
        var plainRow = page.locator("#queue-list .queue-list-item",
                new Page.LocatorOptions().setHasText(PLAIN_CODE));
        plainRow.first().waitFor();
        long noPillCount = plainRow.locator(".queue-processed-pill").count();
        assertEquals(0, noPillCount,
                "Expected plain queue row to have no .queue-processed-pill");

        assertConsoleClean();
    }

    // ── Pin 2: opening a processed no-draft title gates Skip + Enrich ────

    @Test
    void pin2_processedNoDraftTitle_skipAndEnrichDisabled_processedBadgeShown() {
        navigateAndWaitForQueue();

        // Click the processed row to open the no-draft editor.
        page.locator("#queue-list .queue-list-item",
                new Page.LocatorOptions().setHasText(PROCESSED_CODE)).first().click();

        // Wait for the no-draft pane to become visible. #queue-editor-code is static in
        // index.html, so a count()>0 wait would never block — wait on the pane's display.
        page.locator("#queue-editor-pane").waitFor();
        page.waitForCondition(() -> !"none".equals(
                page.locator("#queue-editor-pane").evaluate("el => el.style.display")));

        // Skip must be disabled.
        boolean skipDisabled = (boolean) page.locator("#queue-skip-btn").evaluate("el => el.disabled");
        assertTrue(skipDisabled, "Expected Skip button to be disabled for a processed title");

        // Enrich must be disabled.
        boolean enrichDisabled = (boolean) page.locator("#queue-enrich-btn").evaluate("el => el.disabled");
        assertTrue(enrichDisabled, "Expected Enrich button to be disabled for a processed title");

        // The processed header badge must be visible (display != none).
        String badgeDisplay = (String) page.locator("#queue-processed-badge")
                .evaluate("el => el.style.display");
        assertTrue(!"none".equals(badgeDisplay),
                "Expected #queue-processed-badge to be visible, got display=" + badgeDisplay);

        assertConsoleClean();
    }

    // ── Navigation helpers ─────────────────────────────────────────────────

    private void navigateAndWaitForQueue() {
        page.navigate(baseUrl() + "/");
        page.locator("#action-btn").click();
        page.locator("#action-landing").waitFor();
        page.locator("#tools-queue-btn").click();
        page.locator("#tools-queue-view").waitFor();
        page.waitForCondition(() -> page.locator("#queue-list .queue-list-item").count() > 0);
    }

    // ── Server builder ─────────────────────────────────────────────────────

    private WebServer buildServer() {
        var populator        = mock(DraftPopulator.class);
        var imageFetcher     = mock(ImageFetcher.class);
        var promotionService = mock(DraftPromotionService.class);
        var coverStore       = new DraftCoverScratchStore(dataDir);
        var draftTitleRepo   = new DraftTitleRepository(jdbi);
        var draftEnrichRepo  = new DraftTitleEnrichmentRepository(jdbi);

        var draftRoutes = new DraftRoutes(
                populator, draftTitleRepo, draftEnrichRepo,
                (DraftTitleActressesRepository) null, null,
                coverStore, imageFetcher, promotionService, null,
                new ObjectMapper(), jdbi);

        WebServer ws = new WebServer(0,
                UiTestFixture.buildStockedTitleBrowse(),
                UiTestFixture.buildStockedActressBrowse(),
                null, null, null, null, null, null,
                UiTestFixture.buildStockedSearch());

        ws.registerDraftRoutes(draftRoutes);

        ws.registerJavdbDiscovery(new JavdbDiscoveryRoutes(
                UiTestFixture.buildStockedJavdbService(),
                mock(JavdbEnrichmentActionService.class)));

        // NOTE: /api/tags is registered unconditionally by the WebServer constructor
        // (TitleRoutes), so it must NOT be stubbed here (Javalin rejects duplicate routes).
        // /api/drafts (list) and /api/drafts/:id → 404 come from registerDraftRoutes; the
        // 404 on /api/drafts/:id is what routes the editor to the no-draft pane.
        ws.registerRaw(app -> {
            // Queue sidebar — two rows: one processed, one plain. Both complete:false so
            // they're visible in the default (hide-complete) view.
            app.get("/api/unsorted/titles", ctx -> ctx.json(List.of(
                    Map.of(
                            "titleId",      PROCESSED_TITLE_ID,
                            "code",         PROCESSED_CODE,
                            "folderName",   PROCESSED_CODE,
                            "actressCount", 1,
                            "hasCover",     false,
                            "complete",     false,
                            "processed",    true
                    ),
                    Map.of(
                            "titleId",      PLAIN_TITLE_ID,
                            "code",         PLAIN_CODE,
                            "folderName",   PLAIN_CODE,
                            "actressCount", 0,
                            "hasCover",     false,
                            "complete",     false,
                            "processed",    false
                    )
            )));

            // Detail for the processed title — processed:true on the outer object.
            app.get("/api/unsorted/titles/" + PROCESSED_TITLE_ID, ctx -> ctx.json(Map.of(
                    "titleId",               PROCESSED_TITLE_ID,
                    "code",                  PROCESSED_CODE,
                    "folderName",            PROCESSED_CODE,
                    "detail",                Map.of("folderName", PROCESSED_CODE, "code", PROCESSED_CODE),
                    "directTags",            List.of(),
                    "labelImpliedTags",      List.of(),
                    "enrichmentImpliedTags", List.of(),
                    "processed",             true
            )));

            // Detail for the plain title — processed:false.
            app.get("/api/unsorted/titles/" + PLAIN_TITLE_ID, ctx -> ctx.json(Map.of(
                    "titleId",               PLAIN_TITLE_ID,
                    "code",                  PLAIN_CODE,
                    "folderName",            PLAIN_CODE,
                    "detail",                Map.of("folderName", PLAIN_CODE, "code", PLAIN_CODE),
                    "directTags",            List.of(),
                    "labelImpliedTags",      List.of(),
                    "enrichmentImpliedTags", List.of(),
                    "processed",             false
            )));

            app.get("/api/unsorted/actresses/search", ctx -> ctx.json(List.of()));
        });

        return ws;
    }

    // ── Misc helpers ───────────────────────────────────────────────────────

    private String baseUrl() {
        return "http://localhost:" + server.port();
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
