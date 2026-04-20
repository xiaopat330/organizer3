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

    @Test
    void actressesNavClickShowsActressLanding() {
        page.navigate(baseUrl() + "/");

        // Landing starts hidden (inline style="display:none" in the template).
        assertEquals("none", page.locator("#actress-landing").evaluate("e => e.style.display"));

        page.locator("#actresses-btn").click();

        // After click, showView() must reveal the actress landing.
        page.locator("#actress-landing").waitFor();
        String display = (String) page.locator("#actress-landing").evaluate("e => e.style.display");
        assertNotEquals("none", display,
                "actress-landing should be visible after clicking actresses nav button");
    }

    @Test
    void titlesNavClickShowsTitleLanding() {
        page.navigate(baseUrl() + "/");

        assertEquals("none", page.locator("#title-landing").evaluate("e => e.style.display"));

        page.locator("#titles-browse-btn").click();

        page.locator("#title-landing").waitFor();
        String display = (String) page.locator("#title-landing").evaluate("e => e.style.display");
        assertNotEquals("none", display,
                "title-landing should be visible after clicking titles nav button");
    }

    @Test
    void toolsNavClickShowsActionLanding() {
        page.navigate(baseUrl() + "/");

        assertEquals("none", page.locator("#action-landing").evaluate("e => e.style.display"));

        page.locator("#action-btn").click();

        page.locator("#action-landing").waitFor();
        String display = (String) page.locator("#action-landing").evaluate("e => e.style.display");
        assertNotEquals("none", display,
                "action-landing should be visible after clicking tools nav button");
    }

    @Test
    void clickingBetweenTabsSwapsVisiblePanels() {
        page.navigate(baseUrl() + "/");

        page.locator("#actresses-btn").click();
        page.locator("#actress-landing").waitFor();
        assertNotEquals("none", page.locator("#actress-landing").evaluate("e => e.style.display"));

        page.locator("#titles-browse-btn").click();
        page.locator("#title-landing").waitFor();

        // Actress landing must be hidden again once titles view is active.
        String actressDisplay = (String) page.locator("#actress-landing").evaluate("e => e.style.display");
        assertEquals("none", actressDisplay, "actress-landing must hide when switching to titles");
        String titleDisplay = (String) page.locator("#title-landing").evaluate("e => e.style.display");
        assertNotEquals("none", titleDisplay);
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
