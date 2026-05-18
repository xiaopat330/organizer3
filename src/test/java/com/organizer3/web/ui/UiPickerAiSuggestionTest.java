package com.organizer3.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.javdb.enrichment.EnrichmentQueue.FailedJobSummary;
import com.organizer3.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * UI tests for the AI suggestion banner + AI pick highlight rendered in the
 * v2 JavDB Discovery picker. Track C of the AI Picker Assist Phase 2 plan.
 *
 * <p>The picker is reached via Tools → JavDB Discovery → select an actress →
 * Errors sub-tab → click "Open picker" on an ambiguous-error row. The fixture
 * stubs both {@code JavdbDiscoveryService} and {@code JavdbEnrichmentActionService}
 * to make exactly one ambiguous row appear with a JSON {@code reviewDetail}
 * containing the candidate slugs we assert against.
 */
@Tag("ui")
class UiPickerAiSuggestionTest {

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

    // ──────────────────────────────────────────────────────────────────────
    // Test 1 — "agreed" suggestion renders banner + highlighted card.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void agreedSuggestionRendersBannerAndHighlight() {
        startWithSuggestion("agreed", "candidate-x", "because Y");
        openPicker();

        var banner = page.locator(".er-picker-ai-banner-agreed");
        banner.waitFor();
        String txt = banner.textContent();
        assertNotNull(txt);
        assertTrue(txt.contains("candidate-x"), "banner should name the suggested slug; got: " + txt);
        assertTrue(txt.contains("because Y"),   "banner should include the reason; got: " + txt);

        // Exactly one (non-reference) candidate card has the AI pick highlight class.
        var picked = page.locator(".er-candidate-card-ai-pick");
        picked.first().waitFor();
        assertEquals(1, picked.count(), "exactly one card should be marked as AI pick");

        // The pill has the ✓ glyph for an agreed outcome.
        var pill = picked.locator(".er-ai-pick-pill");
        pill.waitFor();
        assertTrue(pill.textContent().contains("✓"), "agreed-outcome pill should contain ✓");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 2 — "conflict" suggestion renders neutral banner, no highlight.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void conflictSuggestionRendersBannerNoHighlight() {
        startWithSuggestion("conflict", null, null);
        openPicker();

        var banner = page.locator(".er-picker-ai-banner-neutral");
        banner.waitFor();
        String txt = banner.textContent();
        assertNotNull(txt);
        assertTrue(txt.contains("AI couldn't pick"), "conflict banner copy; got: " + txt);

        // No card should be highlighted in conflict state.
        // Wait briefly for cards to render so the absence check is meaningful.
        page.locator(".er-candidate-card").first().waitFor();
        assertEquals(0, page.locator(".er-candidate-card-ai-pick").count(),
                "no card should be highlighted on conflict");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 3 — Pending state shows "AI assist pending" banner + Refresh.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void pendingStateRendersPendingBannerWithRefresh() {
        // No suggestion fields populated yet → pending banner.
        startWithSuggestion(null, null, null);
        openPicker();

        var banner = page.locator(".er-picker-ai-banner-pending");
        banner.waitFor();
        String txt = banner.textContent();
        assertNotNull(txt);
        assertTrue(txt.contains("AI assist pending"), "pending banner copy; got: " + txt);

        var refresh = page.locator(".er-picker-ai-refresh");
        refresh.waitFor();
        assertTrue((Boolean) refresh.evaluate("e => !e.disabled"),
                "refresh button should start enabled");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 4 — "error" outcome → no banner element renders at all.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void errorOutcomeRendersNoBanner() {
        // confidence="error" is explicitly defined as "render nothing".
        // We populate aiSuggestionAt so the pending branch doesn't fire.
        startWithSuggestion("error", null, null);
        openPicker();

        page.locator(".er-candidate-card").first().waitFor();
        assertEquals(0, page.locator(".er-picker-ai-banner").count(),
                "no AI banner should render when confidence=error");
        assertEquals(0, page.locator(".er-candidate-card-ai-pick").count(),
                "no highlighted card on error outcome");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Stands up a fixture server with one ambiguous-error row populated with the
     * given AI suggestion fields. A confidence of {@code null} simulates the
     * "pending" branch (sweeper hasn't filled the row yet — {@code aiSuggestionAt}
     * is also null). Otherwise an ISO-8601 timestamp is supplied.
     */
    private void startWithSuggestion(String confidence, String slug, String reason) {
        String at = (confidence == null) ? null : "2026-05-17T10:00:00Z";
        String reviewDetail = "{"
                + "\"fetched_at\":\"2026-05-17T09:30:00Z\","
                + "\"linked_slugs\":[],"
                + "\"candidates\":["
                + "  {\"slug\":\"candidate-x\",\"title_original\":\"Title X\",\"release_date\":\"2024-01-01\",\"maker\":\"Studio X\",\"cover_url\":null,\"cast\":[]},"
                + "  {\"slug\":\"candidate-y\",\"title_original\":\"Title Y\",\"release_date\":\"2024-02-01\",\"maker\":\"Studio Y\",\"cover_url\":null,\"cast\":[]}"
                + "]"
                + "}";
        FailedJobSummary summary = new FailedJobSummary(
                42L,           // jobId
                7L,            // titleId
                "ABP-001",     // titleCode
                "ambiguous",   // lastError
                3,             // attempts
                "2026-05-17T09:00:00Z",
                100L,          // reviewQueueId
                reviewDetail,
                "ABP",         // titleLabel
                "ABP-00001",   // titleBaseCode
                null,          // coverUrl
                slug,
                confidence,
                reason,
                at
        );
        server = UiTestFixture.buildJavdbStockedServer(
                svc -> { /* defaults are fine — one actress, empty queue */ },
                actions -> when(actions.getErrorsForActress(anyLong()))
                        .thenReturn(List.of(summary))
        );
    }

    /** Navigates directly to the v2 Discovery page, selects the actress, opens Errors, opens picker. */
    private void openPicker() {
        page.navigate(baseUrl() + "/v2-discovery.html");
        // Select the (single) actress from the sidebar.
        var actressRow = page.locator(".jd-actress-item").first();
        actressRow.waitFor();
        actressRow.click();
        // Switch to Errors sub-tab.
        page.locator(".jd-subtab[data-tab='errors']").click();
        // Open the picker on the (single) ambiguous error row.
        var pickerBtn = page.locator(".jd-error-picker-btn").first();
        pickerBtn.waitFor();
        pickerBtn.click();
        // Wait for the picker panel to render at least one candidate card.
        page.locator(".er-picker-panel .er-candidate-card").first().waitFor();
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) consoleErrors.add(msg.text());
    }
}
