package com.organizer3.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.organizer3.web.WebServer;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Browser-driven smoke tests for the web UI. Opt-in: run with {@code ./gradlew uiTest}.
 *
 * <p>Each test spins up a real {@link WebServer} (all services null — structural-only
 * pages still render since the JS handles missing/empty API responses gracefully),
 * launches headless Chromium via Playwright, and exercises one happy path.
 *
 * <p>Assertions are deliberately structural (element presence, no console errors)
 * rather than text-based, so copy edits don't break tests.
 */
@Tag("ui")
class UiSmokeTest {

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = new WebServer(0);
        server.start();

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

    @Test
    void rootPageLoadsAndMainNavRenders() {
        page.navigate(baseUrl() + "/");

        // Structural: main nav buttons exist (they're always in the HTML template).
        assertTrue(page.locator("#actresses-btn").count() > 0, "actresses nav button missing");
        assertTrue(page.locator("#titles-browse-btn").count() > 0, "titles browse nav button missing");
        assertTrue(page.locator("#action-btn").count() > 0, "action nav button missing");

        // Page title is the app name (static HTML; no DB needed).
        assertEquals("JAV Helper", page.title());

        assertNoConsoleErrors();
    }

    // ── helpers ───────────────────────────────────────────────────────

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) {
            consoleErrors.add(msg.text());
        }
    }

    private void assertNoConsoleErrors() {
        // Filter out expected 404s / network errors from the no-op WebServer lacking services.
        List<String> unexpected = consoleErrors.stream()
                .filter(e -> !e.contains("Failed to load resource"))
                .filter(e -> !e.contains("the server responded with a status of"))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "Unexpected JS console errors:\n  " + String.join("\n  ", unexpected));
    }
}
