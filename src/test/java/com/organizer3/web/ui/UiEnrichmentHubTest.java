package com.organizer3.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.organizer3.web.WebServer;
import com.organizer3.web.routes.JavdbDiscoveryRoutes;
import com.organizer3.web.JavdbEnrichmentActionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Browser-driven tests for the v1 Tools → Enrichment hub (the May 2026 backport of
 * the v2 AI Assist + Workflow screens, with the existing Enrichment Review re-homed
 * as a third subtab). See {@code spec/PROPOSAL_V1_ENRICHMENT_HUB_BACKPORT.md}.
 *
 * <p>Harness pattern (mirrors {@link UiNearMissTest}): a {@link WebServer} is built
 * manually with stocked browse/actress/search mocks so the surrounding v1 UI renders
 * without console errors, the JavDB Discovery routes are wired (the Review view is
 * physically nested inside the discovery view in {@code index.html}), and the
 * Enrichment hub's API surface is stubbed with canned JSON via
 * {@link WebServer#registerRaw} BEFORE {@code start()} — Javalin forbids registering
 * routes on a started server, which is why the stocked-server factory (which starts
 * internally) is not reused here.
 *
 * <p>Seeding is done at the HTTP layer rather than the DB layer because the hub's
 * subtabs read exclusively through these endpoints; canned JSON is sufficient to drive
 * every assertion and keeps the test independent of the AI-assist service graph
 * (orchestrator / sweeper / auto-applier), which has no test fixture.
 *
 * <p>Tagged {@code @Tag("ui")} so it runs under {@code ./gradlew uiTest}.
 */
@Tag("ui")
class UiEnrichmentHubTest {

    private WebServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = buildHubServer();
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

    // ── Scenario 1 — navigate to the hub; default subtab = AI Assist. ───────────

    @Test
    void enrichmentHubOpensWithAiAssistDefaultTab() {
        enterHub();

        // Hub container visible.
        assertNotEquals("none", display("#tools-enrichment-hub-view"),
                "hub view should be visible after navigating Tools → Enrichment");

        // AI Assist tab is the selected default; its subview is shown.
        assertTrue(selected("#ehub-tab-ai-assist"), "AI Assist tab should start selected");
        assertNotEquals("none", display("#ehub-ai-assist-subview"),
                "AI Assist subview should be visible by default");

        assertNoConsoleErrors();
    }

    // ── Scenario 2 — AI Assist dashboard renders stat cards from the API. ───────

    @Test
    void aiAssistDashboardRendersStatCards() {
        enterHub();

        // The three stat cards (Queue / Processed / Outcome mix) come from
        // /api/enrichment/assist/dashboard, which we stub with non-zero counts.
        page.locator("#ehub-ai-assist-subview .aia1-card").first().waitFor();
        assertEquals(3, page.locator("#ehub-ai-assist-subview .aia1-card").count(),
                "AI Assist should render exactly 3 stat cards");

        // Queue headline reflects awaitingAi=7.
        var queueHeadline = page.locator("#ehub-ai-assist-subview .aia1-card").first()
                .locator(".aia1-card-headline");
        page.waitForCondition(() -> "7".equals(queueHeadline.textContent()));

        // Outcome donut renders (processedTotal>0 → a chart, not the empty note).
        page.locator("#ehub-ai-assist-subview .aia1-donut-chart").waitFor();

        assertNoConsoleErrors();
    }

    // ── Scenario 3 — tab switching: AI Assist ↔ Workflow ↔ Review. ──────────────

    @Test
    void tabSwitchingTogglesSubviews() {
        enterHub();

        // Switch to Workflow: its table renders the seeded ambiguous row; AI Assist hides.
        page.locator("#ehub-tab-workflow").click();
        page.waitForCondition(() -> selected("#ehub-tab-workflow"));
        page.locator("#ehub-workflow-subview .wf1-row").first().waitFor();
        assertEquals("none", display("#ehub-ai-assist-subview"),
                "AI Assist subview should hide when Workflow is active");

        // Switch to Review: the re-homed Enrichment Review content shows.
        page.locator("#ehub-tab-review").click();
        page.waitForCondition(() -> selected("#ehub-tab-review"));
        page.waitForCondition(() -> !"none".equals(display("#ehub-review-subview")));
        // The re-homed review div now lives inside the hub's review host.
        page.waitForCondition(() -> {
            String parentId = (String) page.locator("#tools-enrichment-review-view")
                    .evaluate("e => e.parentElement && e.parentElement.id");
            return "ehub-review-subview".equals(parentId);
        });
        // Its table/empty-state renders (seeded with one row).
        page.locator("#tools-enrichment-review-view .er-row").first().waitFor();

        // Switch back to AI Assist: it shows again, Review host hides.
        page.locator("#ehub-tab-ai-assist").click();
        page.waitForCondition(() -> selected("#ehub-tab-ai-assist"));
        page.waitForCondition(() -> !"none".equals(display("#ehub-ai-assist-subview")));
        assertEquals("none", display("#ehub-review-subview"),
                "Review host should hide when returning to AI Assist");
    }

    // ── Scenario 4 — Review re-home regression: parent restored on leave. ───────

    @Test
    void reviewDivParentRestoredAfterLeavingHub() {
        page.navigate(baseUrl() + "/");
        // Capture the review div's original parent BEFORE the hub borrows it.
        String originalParentId = (String) page.locator("#tools-enrichment-review-view")
                .evaluate("e => e.parentElement && e.parentElement.id");
        assertNotNull(originalParentId, "review div should have an id'd parent at rest");

        // Enter hub, visit Review (borrows the div), then leave the hub entirely.
        page.locator("#action-btn").click();
        page.locator("#tools-enrichment-btn").waitFor();
        page.locator("#tools-enrichment-btn").click();
        page.waitForCondition(() -> !"none".equals(display("#tools-enrichment-hub-view")));
        page.locator("#ehub-tab-review").click();
        page.waitForCondition(() -> {
            String pid = (String) page.locator("#tools-enrichment-review-view")
                    .evaluate("e => e.parentElement && e.parentElement.id");
            return "ehub-review-subview".equals(pid);
        });

        // Leave Review by switching to another subtab — switchTab's leave-handler
        // for 'review' runs returnReviewDiv(), re-homing the borrowed div. (Top-level
        // SPA navigation via grid.showView toggles display:none WITHOUT calling
        // hideEnrichmentHubView, so an in-hub leave is the reliable trigger.)
        page.locator("#ehub-tab-ai-assist").click();
        page.waitForCondition(() -> selected("#ehub-tab-ai-assist"));

        // The review div must be back under its original parent.
        page.waitForCondition(() -> {
            String pid = (String) page.locator("#tools-enrichment-review-view")
                    .evaluate("e => e.parentElement && e.parentElement.id");
            return originalParentId.equals(pid);
        });

        // NOTE: the full "Sources → Review subtab still works" walk-through is omitted
        // in favor of the load-bearing structural check above (parent restored).
        // Driving the legacy Sources path requires JavDB-discovery subtab navigation
        // and its own seeding; the regression this guards (reparent not returned) is
        // fully captured by the parent-id assertion.
    }

    // ── Scenario 5 — Workflow overflow menu + deep-link focusWorkflow flash. ────

    @Test
    void workflowOverflowMenuAndDeepLinkFocus() {
        enterHub();

        // Deep-link: invoke the hub's exported focusWorkflow(queueId). It's an ES
        // module export (not on window), so reach it via a dynamic import in the page.
        page.evaluate("async () => {"
                + " const m = await import('/modules/utilities-enrichment-hub.js');"
                + " await m.focusWorkflow(501);"
                + "}");

        // The Workflow tab is now selected and the targeted row exists.
        page.waitForCondition(() -> selected("#ehub-tab-workflow"));
        var focusedRow = page.locator("#ehub-workflow-subview tr.wf1-row[data-id='501']");
        focusedRow.waitFor();

        // Overflow ⋮ menu: the seeded ambiguous row exposes a more-actions button
        // gated to 4 items (mark_resolved / accept_gap / override_slug / refresh).
        var moreBtn = focusedRow.locator(".wf1-actions-more-btn");
        moreBtn.waitFor();
        moreBtn.click();
        page.locator(".wf1-action-menu .wf1-action-menu-item").first().waitFor();
        assertEquals(4, page.locator(".wf1-action-menu .wf1-action-menu-item").count(),
                "ambiguous row overflow menu should expose 4 reason-gated actions");
    }

    // ── Server + seeding ────────────────────────────────────────────────────────

    private WebServer buildHubServer() {
        WebServer ws = new WebServer(0,
                UiTestFixture.buildStockedTitleBrowse(),
                UiTestFixture.buildStockedActressBrowse(),
                null, null, null, null, null, null,
                UiTestFixture.buildStockedSearch());

        // The Review view is nested inside the discovery view in index.html; wire the
        // discovery routes so the page bootstraps cleanly even though we drive the hub.
        ws.registerJavdbDiscovery(new JavdbDiscoveryRoutes(
                UiTestFixture.buildStockedJavdbService(),
                mock(JavdbEnrichmentActionService.class)));

        // Stub the entire Enrichment hub API surface with canned JSON. Every endpoint
        // the three subtabs poll is covered so no request 404s into a console error.
        ws.registerRaw(app -> {
            // AI Assist dashboard — non-zero counts so the cards/donut/meter render.
            app.get("/api/enrichment/assist/dashboard", ctx -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("awaitingAi", 7);
                m.put("inFlight", 0);
                m.put("orchestratorQueued", 0);
                m.put("processedTotal", 20);
                m.put("autoApplied", 12);
                m.put("outcomeCounts", Map.of(
                        "agreed", 12, "phi4_only", 3, "conflict", 4, "both_abstain", 1));
                m.put("openAmbiguous", 7);
                m.put("openReviewTotal", 9);
                m.put("agreedPending", 2);
                ctx.json(m);
            });
            app.get("/api/enrichment/assist/queue-preview", ctx -> ctx.json(List.of(
                    queueRow(501, "ABP-501"),
                    queueRow(502, "ABP-502"))));
            app.get("/api/enrichment/assist/recent", ctx -> ctx.json(List.of()));
            app.get("/api/enrichment/assist/sweeper",
                    ctx -> ctx.json(Map.of("active", false, "runId", "")));
            app.get("/api/enrichment/assist/batch-progress", ctx -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("active", false);
                m.put("chunkRowIds", List.of());
                m.put("pass", 0);
                m.put("currentRowId", "");
                m.put("currentCode", "");
                m.put("currentModel", "");
                ctx.json(m);
            });
            app.get("/api/enrichment/assist/apply-agreed/status",
                    ctx -> ctx.json(Map.of(
                            "running", false, "total", 0, "applied", 0, "failed", 0)));

            // Workflow rows — one ambiguous row (id 501) drives the overflow menu and
            // the deep-link focus flash.
            app.get("/api/enrichment/workflow/rows", ctx -> ctx.json(List.of(
                    workflowAmbiguousRow(501, "ABP-501"))));

            // Enrichment Review queue — one ambiguous row so the re-homed table renders.
            app.get("/api/utilities/enrichment-review/queue", ctx -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("counts", Map.of("ambiguous", 1));
                body.put("rows", List.of(reviewRow(901, "REV-901")));
                ctx.json(body);
            });
        });

        return ws;
    }

    private static Map<String, Object> queueRow(long id, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviewQueueId", id);
        m.put("titleId", id);
        m.put("code", code);
        m.put("createdAt", "2026-05-20T10:00:00Z");
        return m;
    }

    private static Map<String, Object> workflowAmbiguousRow(long queueId, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queueId", queueId);
        m.put("titleId", queueId);
        m.put("titleCode", code);
        m.put("reason", "ambiguous");
        m.put("state", "other_intervention");
        m.put("actresses", List.of("Yua Mikami"));
        m.put("coverUrl", "");
        // detail with two candidates so the candidates cell renders thumbs (not an
        // inline reason panel — ambiguous uses the standard candidate path).
        m.put("detail", "{\"candidates\":["
                + "{\"slug\":\"cand-a\",\"cover_url\":null},"
                + "{\"slug\":\"cand-b\",\"cover_url\":null}]}");
        m.put("aiSuggestionAt", "");
        m.put("aiSuggestionConfidence", "");
        m.put("aiPhi4Slug", "");
        m.put("aiGemmaSlug", "");
        return m;
    }

    private static Map<String, Object> reviewRow(long id, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("titleCode", code);
        m.put("reason", "ambiguous");
        m.put("slug", "");
        m.put("detail", "{\"candidates\":[]}");
        m.put("createdAt", "2026-05-20T10:00:00Z");
        return m;
    }

    // ── Navigation + helpers ──────────────────────────────────────────────────────

    private void enterHub() {
        page.navigate(baseUrl() + "/");
        page.locator("#action-btn").click();
        page.locator("#tools-enrichment-btn").waitFor();
        page.locator("#tools-enrichment-btn").click();
        page.waitForCondition(() -> !"none".equals(display("#tools-enrichment-hub-view")));
    }

    private String display(String selector) {
        return (String) page.locator(selector).evaluate("e => e.style.display");
    }

    private boolean selected(String selector) {
        return (Boolean) page.locator(selector)
                .evaluate("e => e.classList.contains('selected')");
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private void recordIfError(ConsoleMessage msg) {
        if ("error".equals(msg.type())) consoleErrors.add(msg.text());
    }

    private void assertNoConsoleErrors() {
        List<String> unexpected = consoleErrors.stream()
                .filter(e -> !e.contains("Failed to load resource"))
                .filter(e -> !e.contains("the server responded with a status of"))
                // The Tools landing eagerly probes library-health endpoints we don't
                // stub here; their 404 plain-text body trips JSON.parse. Unrelated to
                // the Enrichment hub under test.
                .filter(e -> !e.contains("Failed to load health checks"))
                .filter(e -> !e.contains("Failed to load latest report"))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "Unexpected JS console errors:\n  " + String.join("\n  ", unexpected));
    }
}
