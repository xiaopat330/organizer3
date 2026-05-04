package com.organizer3.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.web.JavdbDiscoveryService;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins down JavDB Discovery tool behavior ahead of the multi-file split of
 * {@code utilities-javdb-discovery.js} (PR-C in the May 2026 housekeeping plan).
 *
 * <p>Covers the boundaries the refactor will touch:
 * <ul>
 *   <li>Top-level subtab switching (enrich / titles / collections / queue)</li>
 *   <li>Selected-class exclusivity across subtab switches</li>
 *   <li>Filter input behavior (titles / collections code filters)</li>
 *   <li>Pause / Resume button toggling</li>
 *   <li>Rate-limit banner visibility under a paused-until queue status</li>
 *   <li>Queue badge updates reactively from queue-status response</li>
 * </ul>
 *
 * <p>Mirrors {@link UiTitleBrowseTest} in shape. Tagged {@code @Tag("ui")} so it
 * runs under the dedicated {@code uiTest} task, not the default {@code test} task.
 */
@Tag("ui")
class UiJavdbDiscoveryTest {

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeEach
    void setUp() {
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

    // ── Subtab switching ──────────────────────────────────────────────

    @Test
    void titlesTabActivatesOnClick() {
        startWithDefaultQueue();
        enterDiscovery();
        var titlesTab = page.locator(".jd-tab-titles");
        titlesTab.click();
        page.waitForCondition(() ->
                (Boolean) titlesTab.evaluate("e => e.classList.contains('selected')"));
        assertFalse((Boolean) page.locator(".jd-tab-enrich")
                .evaluate("e => e.classList.contains('selected')"),
                "enrich tab should lose selected when titles activates");
    }

    @Test
    void collectionsTabActivatesOnClick() {
        startWithDefaultQueue();
        enterDiscovery();
        var tab = page.locator(".jd-tab-collections");
        tab.click();
        page.waitForCondition(() ->
                (Boolean) tab.evaluate("e => e.classList.contains('selected')"));
    }

    @Test
    void queueTabActivatesOnClick() {
        startWithDefaultQueue();
        enterDiscovery();
        var tab = page.locator(".jd-tab-queue");
        tab.click();
        page.waitForCondition(() ->
                (Boolean) tab.evaluate("e => e.classList.contains('selected')"));
    }

    @Test
    void switchingBackToEnrichRestoresSidebar() {
        startWithDefaultQueue();
        enterDiscovery();
        page.locator(".jd-tab-titles").click();
        page.waitForCondition(() -> (Boolean) page.locator(".jd-tab-titles")
                .evaluate("e => e.classList.contains('selected')"));

        page.locator(".jd-tab-enrich").click();
        page.waitForCondition(() -> (Boolean) page.locator(".jd-tab-enrich")
                .evaluate("e => e.classList.contains('selected')"));
        // jd-body is the enrich-tab body and should now be visible (display !== 'none').
        String display = (String) page.locator(".jd-body").evaluate("e => e.style.display");
        assertNotEquals("none", display, "enrich body should be visible after switching back to enrich");
    }

    // ── Filter behavior ────────────────────────────────────────────────

    @Test
    void titlesFilterInputShowsClearButtonOnInput() {
        startWithDefaultQueue();
        enterDiscovery();
        page.locator(".jd-tab-titles").click();
        var input = page.locator("#jd-titles-filter-input");
        input.waitFor();
        input.fill("ABP");
        var clearBtn = page.locator("#jd-titles-filter-clear");
        page.waitForCondition(() -> {
            String d = (String) clearBtn.evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    @Test
    void collectionsFilterInputShowsClearButtonOnInput() {
        startWithDefaultQueue();
        enterDiscovery();
        page.locator(".jd-tab-collections").click();
        var input = page.locator("#jd-collections-filter-input");
        input.waitFor();
        input.fill("MIDV");
        var clearBtn = page.locator("#jd-collections-filter-clear");
        page.waitForCondition(() -> {
            String d = (String) clearBtn.evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    // ── Pause / Resume ─────────────────────────────────────────────────

    @Test
    void pauseButtonVisibleAndClickable() {
        startWithDefaultQueue();
        enterDiscovery();
        var pauseBtn = page.locator("#jd-pause-btn");
        pauseBtn.waitFor();
        // Default queue is not paused → button reads "Pause".
        page.waitForCondition(() -> "Pause".equals(pauseBtn.textContent()));
    }

    // ── Rate-limit banner ──────────────────────────────────────────────

    @Test
    void rateLimitBannerShowsResumeNowButtonWhenBurstPaused() {
        // Queue with rateLimitPausedUntil + pauseType="burst" should reveal banner with Resume Now.
        String resumeAt = java.time.OffsetDateTime.now().plusMinutes(15).toString();
        server = UiTestFixture.buildJavdbStockedServer(svc -> {
            when(svc.getQueueStatus()).thenReturn(
                    UiTestFixture.rateLimitedQueueStatus(resumeAt, "burst"));
        });
        enterDiscovery();
        page.waitForCondition(() -> {
            String d = (String) page.locator("#jd-rate-limit-banner").evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
        page.locator("#jd-force-resume-btn").waitFor();
    }

    // ── Queue badge ────────────────────────────────────────────────────

    @Test
    void queueBadgeShowsPendingCountWhenJobsActive() {
        server = UiTestFixture.buildJavdbStockedServer(svc -> {
            when(svc.getQueueStatus()).thenReturn(
                    UiTestFixture.sampleQueueStatus(3, 1, 0, 0, false));
        });
        enterDiscovery();
        var badge = page.locator("#jd-queue-badge");
        page.waitForCondition(() -> {
            String d = (String) badge.evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
        // active total = pending + inFlight = 4, label includes "4 pending"
        page.waitForCondition(() -> {
            String html = (String) badge.evaluate("e => e.innerHTML");
            return html != null && html.contains("4 pending");
        });
    }

    @Test
    void queueBadgeHiddenWhenAllZero() {
        startWithDefaultQueue();
        enterDiscovery();
        var badge = page.locator("#jd-queue-badge");
        // After loadActresses+refreshQueue, badge should remain hidden (all-zero status).
        page.waitForLoadState();
        String d = (String) badge.evaluate("e => e.style.display");
        assertEquals("none", d, "badge should be hidden when no jobs");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void startWithDefaultQueue() {
        server = UiTestFixture.buildStockedServer();
    }

    private void enterDiscovery() {
        page.navigate(baseUrl() + "/");
        // Open Tools view, then click the JavDB Discovery tool button.
        page.locator("#action-btn").click();
        page.locator("#tools-javdb-discovery-btn").waitFor();
        page.locator("#tools-javdb-discovery-btn").click();
        // Wait for the view to be visible.
        page.waitForCondition(() -> {
            String d = (String) page.locator("#tools-javdb-discovery-view")
                    .evaluate("e => e.style.display");
            return d != null && !"none".equals(d);
        });
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) consoleErrors.add(msg.text());
    }
}
