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
 * Playwright UI pins for Phase 4 of PROPOSAL_CURATION_COMPLETION — processed/terminal state.
 *
 * <p>Pins:
 * <ol>
 *   <li>A queue row with {@code processed:true} shows the "✓ processed" pill.</li>
 *   <li>Opening a processed title in no-draft mode: Skip and Enrich are disabled,
 *       processed pill appears in the editor header, Save remains enabled-capable
 *       (it is dirty-gated — but it exists and is not forced-disabled for processed titles).</li>
 * </ol>
 *
 * <p>These pins cover the durable UI contract: {@code state.detail.processed === true}
 * gates Skip/Enrich in {@code _renderNoDraft} and surfaces a "Processed via javdb" badge.
 * The backend {@code curated_at → processed} mapping is tested in backend repository tests;
 * this pin tests only the JS contract.
 */
@Tag("ui")
class UiProcessedStateTest {

    // A processed title — complete:false so it's visible in the default (hide-complete) view.
    static final long   PROCESSED_TITLE_ID = 8800L;
    static final String PROCESSED_CODE     = "PRC-001";

    // A non-processed title — also present to confirm the pill is absent on unprocessed rows.
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

        // The processed row must have the pill.
        var processedRow = page.locator(".un-queue-row[data-title-id='" + PROCESSED_TITLE_ID + "']");
        processedRow.waitFor();
        long pillCount = processedRow.locator(".un-processed-pill").count();
        assertEquals(1, pillCount,
                "Expected processed queue row to have exactly one .un-processed-pill");

        // The pill text must include "processed".
        String pillText = processedRow.locator(".un-processed-pill").textContent();
        assertTrue(pillText.contains("processed"),
                "Expected pill text to contain 'processed', got: " + pillText);

        // The plain (non-processed) row must NOT have the pill.
        var plainRow = page.locator(".un-queue-row[data-title-id='" + PLAIN_TITLE_ID + "']");
        plainRow.waitFor();
        long noPillCount = plainRow.locator(".un-processed-pill").count();
        assertEquals(0, noPillCount,
                "Expected plain queue row to have no .un-processed-pill");

        assertConsoleClean();
    }

    // ── Pin 2: opening a processed no-draft title gates Skip + Enrich ────

    @Test
    void pin2_processedNoDraftTitle_skipAndEnrichDisabled_processedPillShown() {
        navigateAndWaitForQueue();

        // Click the processed row to open the no-draft editor.
        page.locator(".un-queue-row[data-title-id='" + PROCESSED_TITLE_ID + "']").click();

        // Wait for the editor pane to render the no-draft shell (code el appears).
        page.waitForCondition(() -> page.locator("#un-ed-code").count() > 0);

        // Skip must be disabled.
        boolean skipDisabled = (boolean) page.locator("#un-skip-btn").evaluate("el => el.disabled");
        assertTrue(skipDisabled, "Expected Skip button to be disabled for a processed title");

        // Enrich must be disabled.
        boolean enrichDisabled = (boolean) page.locator("#un-enrich-btn").evaluate("el => el.disabled");
        assertTrue(enrichDisabled, "Expected Enrich button to be disabled for a processed title");

        // The processed pill must appear in the editor header code row.
        long headerPillCount = page.locator(".un-editor-code-row .un-processed-pill").count();
        assertEquals(1, headerPillCount,
                "Expected one .un-processed-pill in the editor header for a processed title");

        // Save must be present (not force-disabled for processed — it remains dirty-gated).
        long saveBtnCount = page.locator("#un-save-btn").count();
        assertEquals(1, saveBtnCount, "Expected Save button to be present for processed title");

        assertConsoleClean();
    }

    // ── Navigation helpers ─────────────────────────────────────────────────

    private void navigateAndWaitForQueue() {
        page.navigate(baseUrl() + "/v2-unprocessed.html");
        page.waitForCondition(() -> page.locator(".un-queue-row").count() > 0);
    }

    // ── Server builder ─────────────────────────────────────────────────────

    private WebServer buildServer() {
        var populator        = mock(DraftPopulator.class);
        var imageFetcher     = mock(ImageFetcher.class);
        var promotionService = mock(DraftPromotionService.class);
        var coverStore       = new DraftCoverScratchStore(dataDir);
        var draftTitleRepo          = new DraftTitleRepository(jdbi);
        var draftEnrichRepo         = new DraftTitleEnrichmentRepository(jdbi);

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

        ws.registerRaw(app -> {
            // Queue sidebar — two rows: one processed (processed:true), one plain.
            // Both are complete:false so they're visible in the default (hide-complete) view.
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

            // Detail for the plain title — processed absent / false.
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
