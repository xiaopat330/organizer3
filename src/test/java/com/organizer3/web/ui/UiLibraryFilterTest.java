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
 * Pins down library-filter UI behavior ahead of a planned state-factory
 * rewrite of {@code title-browse.js}. These tests exercise the 11
 * library-state module vars (libraryCode, libraryCompany, librarySort,
 * libraryOrder, tagsBarOpen, activeTags, etc.) through their visible DOM
 * handles so the upcoming refactor has a regression net.
 *
 * <p>Selectors pulled from {@code title-browse.js} renderLibraryFilterPanel().
 */
@Tag("ui")
class UiLibraryFilterTest {

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

    // ── Entry / panel render ──────────────────────────────────────────

    @Test
    void libraryButtonClickRendersFilterPanel() {
        page.navigate(baseUrl() + "/");
        page.locator("#titles-browse-btn").click();
        page.locator("#title-tags-btn").click();

        // Panel populates via renderLibraryFilterPanel — all three primary controls must appear.
        page.locator("#library-company-select").waitFor();
        page.locator("#library-sort-select").waitFor();
        page.locator("#library-order-btn").waitFor();
    }

    @Test
    void libraryCompanyDropdownPopulatesFromListAllCompanies() {
        enterLibrary();
        // Fixture stubs listAllCompanies() → ["Prestige", "S1 No.1 Style"].
        page.waitForCondition(() ->
                page.locator("#library-company-select option").count() >= 3); // "All" + 2 companies
    }

    // ── State round-trips ─────────────────────────────────────────────

    @Test
    void orderButtonTogglesLabelBetweenAZAndZA() {
        enterLibrary();
        String before = page.locator("#library-order-btn").innerText();
        page.locator("#library-order-btn").click();
        page.waitForCondition(() -> !before.equals(page.locator("#library-order-btn").innerText()));
        String after = page.locator("#library-order-btn").innerText();
        // Label flips between the two glyphs: "A–Z" ↔ "Z–A".
        assertTrue(after.contains("A") && after.contains("Z"), "got: " + after);
        assertNotEquals(before, after);
    }

    @Test
    void sortSelectChangeDoesNotThrow() {
        enterLibrary();
        page.locator("#library-sort-select").selectOption("productCode");
        // After change, the select must reflect the new value (proves the change event landed).
        assertEquals("productCode",
                page.locator("#library-sort-select").evaluate("e => e.value"));
    }

    @Test
    void companySelectChangeReflectsInSelectValue() {
        enterLibrary();
        page.locator("#library-company-select").selectOption("Prestige");
        assertEquals("Prestige",
                page.locator("#library-company-select").evaluate("e => e.value"));
    }

    // ── Tags bar toggle ───────────────────────────────────────────────

    @Test
    void tagsToggleButtonShowsAndHidesTagsSection() {
        enterLibrary();
        // Section starts hidden.
        assertEquals("none",
                page.locator("#library-tags-section").evaluate("e => e.style.display"));

        page.locator("#library-tags-toggle-btn").click();
        page.waitForCondition(() -> !"none".equals(
                page.locator("#library-tags-section").evaluate("e => e.style.display")));

        page.locator("#library-tags-toggle-btn").click();
        page.waitForCondition(() -> "none".equals(
                page.locator("#library-tags-section").evaluate("e => e.style.display")));
    }

    @Test
    void tagToggleClickMarksChipActiveAndRendersChipBar() {
        enterLibrary();
        page.locator("#library-tags-toggle-btn").click();

        // Tags catalog loads from /api/tags (real resource). Pick the first toggle if any rendered.
        page.waitForCondition(() ->
                page.locator("#library-tags-section .tag-toggle").count() > 0);
        var firstToggle = page.locator("#library-tags-section .tag-toggle").first();
        String tagName = firstToggle.getAttribute("data-tag");

        firstToggle.click();

        // The active class is applied, the tags-bar becomes visible, and a chip appears.
        page.waitForCondition(() -> firstToggle.evaluate("e => e.classList.contains('active')")
                .equals(Boolean.TRUE));
        page.waitForCondition(() ->
                page.locator("#library-tag-chips .library-tag-chip[data-tag=\"" + tagName + "\"]").count() > 0);
    }

    @Test
    void chipRemoveButtonClearsActiveTagAndDeactivatesToggle() {
        enterLibrary();
        page.locator("#library-tags-toggle-btn").click();
        page.waitForCondition(() ->
                page.locator("#library-tags-section .tag-toggle").count() > 0);
        var firstToggle = page.locator("#library-tags-section .tag-toggle").first();
        firstToggle.click();

        // Wait for the chip to render, then click its remove button.
        page.waitForCondition(() ->
                page.locator("#library-tag-chips .library-tag-chip").count() > 0);
        page.locator("#library-tag-chips .library-tag-chip-remove").first().click();

        // Active class removed from the toggle; chip list empties.
        page.waitForCondition(() -> !(Boolean) firstToggle.evaluate("e => e.classList.contains('active')"));
        // The chips bar may hide on a timer — the container emptying is the contract.
        page.waitForCondition(() -> page.locator("#library-tag-chips .library-tag-chip").count() == 0);
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void enterLibrary() {
        page.navigate(baseUrl() + "/");
        page.locator("#titles-browse-btn").click();
        page.locator("#title-tags-btn").click();
        page.locator("#library-company-select").waitFor();
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }
}
