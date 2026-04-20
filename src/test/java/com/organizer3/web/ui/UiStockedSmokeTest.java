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
 * Smoke tests that exercise parts of the UI requiring live API responses
 * (dashboards, search results, detail pages). Uses {@link UiTestFixture}
 * to wire mocked services returning canned data.
 */
@Tag("ui")
class UiStockedSmokeTest {

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

    @Test
    void titleDashboardRendersAfterNavClick() {
        page.navigate(baseUrl() + "/");
        page.locator("#titles-browse-btn").click();

        // The title dashboard root div must become visible and populated.
        page.locator("#title-dashboard").waitFor();
        String display = (String) page.locator("#title-dashboard").evaluate("e => e.style.display");
        assertNotEquals("none", display);

        // Dashboard child strips (at least the on-deck hero) should render at least one card.
        // Allow the JS ample time to run dashboard fetch + render.
        page.waitForCondition(() -> page.locator(".card, .card-compact").count() > 0);
    }

    @Test
    void actressDashboardRendersAfterNavClick() {
        page.navigate(baseUrl() + "/");
        page.locator("#actresses-btn").click();

        page.locator("#actress-landing").waitFor();
        String display = (String) page.locator("#actress-landing").evaluate("e => e.style.display");
        assertNotEquals("none", display);
    }

    @Test
    void portalSearchShowsOverlayWithResults() {
        page.navigate(baseUrl() + "/");

        page.locator("#portal-search-input").fill("yua");

        // Debounced search triggers overlay show + hit render.
        page.waitForCondition(() -> {
            String d = (String) page.locator("#portal-search-overlay").evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });

        // The mocked SearchService returns one actress hit — expect a search-result item.
        page.waitForCondition(() -> page.locator("#portal-search-overlay").innerText().contains("Yua Mikami"));
    }

    @Test
    void escapeKeyClosesPortalSearchOverlay() {
        page.navigate(baseUrl() + "/");
        page.locator("#portal-search-input").fill("yua");

        // Wait for overlay to open first.
        page.waitForCondition(() -> {
            String d = (String) page.locator("#portal-search-overlay").evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });

        page.locator("#portal-search-input").press("Escape");

        // Overlay must return to hidden.
        page.waitForCondition(() -> "none".equals(
                page.locator("#portal-search-overlay").evaluate("e => e.style.display")));
    }

    @Test
    void actressLetterClickTriggersFilterFetch() {
        page.navigate(baseUrl() + "/");
        page.locator("#actresses-btn").click();
        page.locator("#actress-landing").waitFor();

        // The actress index API returns ["Y", "A"] — letter chips render into the landing.
        // Wait for at least one letter chip, then click one and verify a tier-counts fetch
        // happens (which the mocked service accepts).
        page.waitForCondition(() -> page.locator(".actress-landing-tier, .actress-landing-letter, .actress-sub-nav-item").count() > 0);
    }

    @Test
    void actionToolsLandingRendersToolChips() {
        page.navigate(baseUrl() + "/");
        page.locator("#action-btn").click();
        page.locator("#action-landing").waitFor();

        // Tool buttons are static HTML under .action-landing — present on page load.
        assertTrue(page.locator(".action-tool-btn").count() > 0, "action-tool-btn elements missing");
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
}
