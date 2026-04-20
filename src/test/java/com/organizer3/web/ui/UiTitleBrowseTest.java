package com.organizer3.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down title-browse mode switching ahead of a state-factory
 * rewrite of {@code title-browse.js}. Complements {@link UiLibraryFilterTest}
 * (which covers library mode) by exercising the other mode-switching paths
 * the refactor will touch: collections, studio, and the exclusive-selected
 * invariant.
 */
@Tag("ui")
class UiTitleBrowseTest {

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = UiTestFixture.buildStockedServer();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        context = browser.newContext();
        page = context.newPage();
        page.onConsoleMessage(this::recordIfError);
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
    void collectionsButtonActivatesCollectionsMode() {
        enterTitles();
        var btn = page.locator("#title-collections-btn");
        btn.click();
        page.waitForCondition(() ->
                (Boolean) btn.evaluate("e => e.classList.contains('selected')"));
    }

    @Test
    void studioButtonOpensStudioGroupRow() {
        enterTitles();
        page.locator("#title-studio-btn").click();
        // Studio mode reveals the studio-group sub-nav row.
        page.waitForCondition(() -> {
            String d = (String) page.locator("#title-studio-group-row")
                    .evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    @Test
    void libraryButtonActivatesLibraryMode() {
        enterTitles();
        var btn = page.locator("#title-tags-btn");
        btn.click();
        page.waitForCondition(() ->
                (Boolean) btn.evaluate("e => e.classList.contains('selected')"));
    }

    @Test
    void switchingTitleBrowseModesUpdatesSelectedClassExclusively() {
        enterTitles();
        var coll = page.locator("#title-collections-btn");
        var lib = page.locator("#title-tags-btn");

        coll.click();
        page.waitForCondition(() -> (Boolean) coll.evaluate("e => e.classList.contains('selected')"));

        lib.click();
        page.waitForCondition(() -> (Boolean) lib.evaluate("e => e.classList.contains('selected')"));
        assertFalse((Boolean) coll.evaluate("e => e.classList.contains('selected')"),
                "collections should lose selected class when library activates");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void enterTitles() {
        page.navigate(baseUrl() + "/");
        page.locator("#titles-browse-btn").click();
        page.locator("#title-dashboard-btn").waitFor();
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) consoleErrors.add(msg.text());
    }
}
