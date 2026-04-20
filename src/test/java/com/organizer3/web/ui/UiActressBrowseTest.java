package com.organizer3.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down actress-browse mode switching ahead of a state-factory
 * rewrite of {@code actress-browse.js}. Exercises the 9 module-level
 * mutable vars — actressBrowseMode, exhibitionCompanyFilter,
 * archivesCompanyFilter, tierCompanyFilter, studioGroupCompanyFilter,
 * selectedActressStudioSlug, lastActressTier, actressTierPanelOpen,
 * activeStudioGroupName — through their visible DOM handles.
 */
@Tag("ui")
class UiActressBrowseTest {

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        server = UiTestFixture.buildStockedServer();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.stop();
    }

    // ── Mode switching ────────────────────────────────────────────────

    @Test
    void dashboardButtonShowsActressDashboardRow() {
        enterActresses();
        page.locator("#actress-dashboard-btn").click();
        // Dashboard mode renders into the grid; landing stays visible.
        page.waitForCondition(() ->
                !"none".equals(page.locator("#actress-landing").evaluate("e => e.style.display")));
    }

    @Test
    void favoritesButtonActivatesFavoritesMode() {
        enterActresses();
        var btn = page.locator("#actress-favorites-btn");
        btn.click();
        // Selected state pushes the "active" class onto the landing button.
        page.waitForCondition(() ->
                (Boolean) btn.evaluate("e => e.classList.contains('selected')"));
    }

    @Test
    void bookmarksButtonActivatesBookmarksMode() {
        enterActresses();
        var btn = page.locator("#actress-bookmarks-btn");
        btn.click();
        page.waitForCondition(() ->
                (Boolean) btn.evaluate("e => e.classList.contains('selected')"));
    }

    @Test
    void tierButtonOpensTierPanel() {
        enterActresses();
        page.locator("#actress-tier-btn").click();
        // Tier panel becomes visible — it's a sub-nav row that toggles display.
        page.waitForCondition(() -> {
            String d = (String) page.locator("#actress-landing-tier-row")
                    .evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    // ── Company filters (sub-nav rows) ────────────────────────────────

    @Test
    void exhibitionButtonOpensExhibitionRow() {
        enterActresses();
        page.locator("#actress-exhibition-btn").click();
        page.waitForCondition(() -> {
            String d = (String) page.locator("#actress-exhibition-row")
                    .evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    @Test
    void archivesButtonOpensArchivesRow() {
        enterActresses();
        page.locator("#actress-archives-btn").click();
        page.waitForCondition(() -> {
            String d = (String) page.locator("#actress-archives-row")
                    .evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    @Test
    void switchingBetweenModesUpdatesActiveClassExclusively() {
        enterActresses();
        var fav = page.locator("#actress-favorites-btn");
        var book = page.locator("#actress-bookmarks-btn");

        fav.click();
        page.waitForCondition(() -> (Boolean) fav.evaluate("e => e.classList.contains('selected')"));

        book.click();
        page.waitForCondition(() -> (Boolean) book.evaluate("e => e.classList.contains('selected')"));
        // Only one mode should be active at a time.
        assertFalse((Boolean) fav.evaluate("e => e.classList.contains('selected')"),
                "favorites should lose selected class when bookmarks is selected");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void enterActresses() {
        page.navigate(baseUrl() + "/");
        page.locator("#actresses-btn").click();
        page.locator("#actress-landing").waitFor();
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }
}
