package com.organizer3.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftEnrichment;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPromotionService;
import com.organizer3.javdb.draft.DraftTitle;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Playwright UI pin for the v2 Unprocessed draft tag panel.
 *
 * <p>Regression guard for the bug where {@code buildDraftTagState} read
 * {@code enrichmentImpliedTags} from {@code state.detail.enrichmentImpliedTags}
 * (always empty before promotion) instead of {@code state.draft.enrichment.resolvedTags}.
 *
 * <p>Pin: a draft with enrichment tags (resolvedTags populated by the
 * {@code GET /api/drafts/:titleId} route via {@code enrichment_tag_definitions})
 * must show those tag chips as active+implied in the v2 draft editor tag panel.
 */
@Tag("ui")
class UiDraftEnrichmentTagsTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // Canonical title_id used across all seed helpers.
    static final long   TITLE_ID = 7700L;
    static final String CODE     = "ENR-001";

    // Curated tag names from tags.yaml that will be used as enrichment tags.
    // "creampie" exists in the "act" category; "solo-actress" in the "format" category.
    static final String TAG_CREAMPIE    = "creampie";
    static final String TAG_SOLO_ACTRESS = "solo-actress";

    // Raw javdb tag name that maps to TAG_CREAMPIE via curated_alias.
    static final String RAW_TAG_CREAMPIE    = "中出し";
    // Raw javdb tag name that maps to TAG_SOLO_ACTRESS via curated_alias.
    static final String RAW_TAG_SOLO_ACTRESS = "単体作品";

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
    private DraftTitleRepository draftTitleRepo;
    private DraftTitleEnrichmentRepository draftEnrichRepo;

    @BeforeEach
    void setUp() throws Exception {
        // File-based SQLite so the Javalin multi-thread handler can share the schema.
        Path dbFile = dataDir.resolve("test.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        connection = DriverManager.getConnection(jdbcUrl);
        jdbi = Jdbi.create(jdbcUrl);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        draftTitleRepo  = new DraftTitleRepository(jdbi);
        draftEnrichRepo = new DraftTitleEnrichmentRepository(jdbi);

        // Seed: canonical tags (tags.yaml tags that the enrichment_tag_definitions point to)
        seedTags();
        // Seed: enrichment tag definitions with curated_alias pointing to the canonical tags.
        seedEnrichmentTagDefinitions();
        // Seed: canonical title stub (required by FK from draft_titles.title_id).
        seedTitle();
        // Seed: draft title + enrichment with raw javdb tags.
        long draftId = seedDraftTitle();
        seedDraftEnrichment(draftId);

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

    /**
     * Pin: enrichment tags from the draft appear as implied chips in the v2 draft tag panel.
     *
     * <p>Before the fix, {@code buildDraftTagState} read {@code enrichmentImpliedTags} from
     * {@code state.detail.enrichmentImpliedTags} which is empty for unpromoted drafts.
     * After the fix it reads from {@code state.draft.enrichment.resolvedTags} instead.
     */
    @Test
    void pin1_draftEnrichmentTagsAppearAsImpliedChipsInTagPanel() {
        navigateToDraftEditor();

        // Both enrichment-implied chips must be present and active.
        // Tags-pane.js marks them with class "chip chip-active un-chip-implied".
        assertTagChipImplied(TAG_CREAMPIE);
        assertTagChipImplied(TAG_SOLO_ACTRESS);

        // Verify neither chip is interactive (enrichment-implied chips are disabled).
        boolean creampieDisabled = (boolean) page.locator(tagChipSelector(TAG_CREAMPIE))
                .evaluate("el => el.disabled");
        assertTrue(creampieDisabled, "'" + TAG_CREAMPIE + "' chip should be disabled (implied/locked)");

        assertConsoleClean();
    }

    // ── Seed helpers ───────────────────────────────────────────────────────

    private void seedTags() {
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO tags(name, category, description) VALUES ('" + TAG_CREAMPIE + "', 'act', 'Creampie')");
            h.execute("INSERT OR IGNORE INTO tags(name, category, description) VALUES ('" + TAG_SOLO_ACTRESS + "', 'format', 'Solo actress')");
        });
    }

    private void seedEnrichmentTagDefinitions() {
        jdbi.useHandle(h -> {
            h.createUpdate("""
                    INSERT OR IGNORE INTO enrichment_tag_definitions(name, curated_alias, title_count, surface)
                    VALUES (:rawName, :curatedAlias, 1, 1)
                    """)
                    .bind("rawName",      RAW_TAG_CREAMPIE)
                    .bind("curatedAlias", TAG_CREAMPIE)
                    .execute();
            h.createUpdate("""
                    INSERT OR IGNORE INTO enrichment_tag_definitions(name, curated_alias, title_count, surface)
                    VALUES (:rawName, :curatedAlias, 1, 1)
                    """)
                    .bind("rawName",      RAW_TAG_SOLO_ACTRESS)
                    .bind("curatedAlias", TAG_SOLO_ACTRESS)
                    .execute();
        });
    }

    private void seedTitle() {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (:id, :code, :code, 'ENR', 1)")
                .bind("id",   TITLE_ID)
                .bind("code", CODE)
                .execute());
    }

    private long seedDraftTitle() {
        DraftTitle dt = DraftTitle.builder()
                .titleId(TITLE_ID)
                .code(CODE)
                .titleOriginal("テスト")
                .createdAt(now())
                .updatedAt(now())
                .build();
        return draftTitleRepo.insert(dt);
    }

    private void seedDraftEnrichment(long draftId) {
        // Store the raw javdb tag names as JSON — DraftRoutes.registerGetDraft resolves
        // them to curated_alias values and returns them as enrichment.resolvedTags.
        String tagsJson = "[\"" + RAW_TAG_CREAMPIE + "\",\"" + RAW_TAG_SOLO_ACTRESS + "\"]";
        DraftEnrichment enr = DraftEnrichment.builder()
                .draftTitleId(draftId)
                .javdbSlug("test-slug")
                .tagsJson(tagsJson)
                .updatedAt(now())
                .build();
        draftEnrichRepo.upsert(draftId, enr);
    }

    // ── Server builder ─────────────────────────────────────────────────────

    private WebServer buildServer() {
        var populator        = mock(DraftPopulator.class);
        var imageFetcher     = mock(ImageFetcher.class);
        var promotionService = mock(DraftPromotionService.class);
        var coverStore       = new DraftCoverScratchStore(dataDir);

        // Wire DraftRoutes with a real jdbi so resolvedTags resolution works.
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
            // Queue sidebar row — one entry for TITLE_ID so the user can click it.
            app.get("/api/unsorted/titles", ctx -> ctx.json(List.of(
                    Map.of(
                            "titleId",      TITLE_ID,
                            "code",         CODE,
                            "folderName",   CODE,
                            "actressCount", 0,
                            "hasCover",     false,
                            "complete",     false
                    )
            )));
            // Title detail — empty directTags/enrichmentImpliedTags (canonical is pre-promotion).
            app.get("/api/unsorted/titles/" + TITLE_ID, ctx -> ctx.json(Map.of(
                    "titleId",               TITLE_ID,
                    "code",                  CODE,
                    "folderName",            CODE,
                    "detail",                Map.of("folderName", CODE, "code", CODE),
                    "directTags",            List.of(),
                    "labelImpliedTags",      List.of(),
                    "enrichmentImpliedTags", List.of()   // intentionally empty: pre-promotion
            )));
            app.get("/api/unsorted/actresses/search", ctx -> ctx.json(List.of()));
        });

        return ws;
    }

    // ── Navigation helpers ─────────────────────────────────────────────────

    private void navigateToDraftEditor() {
        page.navigate(baseUrl() + "/v2-unprocessed.html");

        // Wait for the sidebar queue to load and show the one entry.
        page.waitForCondition(() -> page.locator(".un-queue-row").count() > 0);

        // Click the queue row to load the draft editor.
        page.locator(".un-queue-row").first().click();

        // Wait for the draft pane shell (DRAFT pill confirms draft mode is active).
        page.waitForCondition(() -> page.locator(".un-draft-pill").count() > 0);

        // Wait for the tag panel to render (it contains at least one chip).
        page.waitForCondition(() -> page.locator("#un-tags-panel .chip").count() > 0);
    }

    // ── Assertion helpers ──────────────────────────────────────────────────

    private static String tagChipSelector(String tagName) {
        return "#un-tags-panel .chip[data-tag='" + tagName + "']";
    }

    private void assertTagChipImplied(String tagName) {
        String sel = tagChipSelector(tagName);
        page.waitForCondition(() -> page.locator(sel).count() > 0,
                new Page.WaitForConditionOptions().setTimeout(5000));
        long count = page.locator(sel + ".chip-active.un-chip-implied").count();
        assertEquals(1, count,
                "Expected tag '" + tagName + "' chip to be active+implied (enrichment-sourced), " +
                "but classes were: " + page.locator(sel).getAttribute("class"));
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
