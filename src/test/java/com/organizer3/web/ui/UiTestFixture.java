package com.organizer3.web.ui;

import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.ActressSummary;
import com.organizer3.web.SearchService;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.TitleBrowseService.LibraryStatsDto;
import com.organizer3.web.TitleBrowseService.TitleDashboard;
import com.organizer3.web.TitleSummary;
import com.organizer3.web.WebServer;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Builds a {@link WebServer} with mocked services returning canned data — enough
 * for the UI to render dashboards, search overlays, and detail pages without
 * hitting a real database.
 *
 * <p>Each call to {@link #buildStockedServer()} returns a fresh, started server
 * bound to an ephemeral port. Caller must {@link WebServer#stop() stop} it.
 *
 * <p>Canned data is deliberately minimal and generic. Tests that need specific
 * values should extend the factory or stub overrides on the returned mocks.
 */
final class UiTestFixture {

    private UiTestFixture() {}

    /** Starts a WebServer with all UI-critical endpoints returning sane canned data. */
    static WebServer buildStockedServer() {
        TitleBrowseService titleBrowse = mock(TitleBrowseService.class);
        ActressBrowseService actressBrowse = mock(ActressBrowseService.class);
        SearchService searchService = mock(SearchService.class);

        // ── Titles ─────────────────────────────────────────────────────
        when(titleBrowse.findRecent(anyInt(), anyInt())).thenReturn(List.of(sampleTitle()));
        when(titleBrowse.searchByCodePaged(anyString(), anyInt(), anyInt())).thenReturn(List.of(sampleTitle()));
        when(titleBrowse.findFavoritesPaged(anyInt(), anyInt())).thenReturn(List.of());
        when(titleBrowse.findBookmarksPaged(anyInt(), anyInt())).thenReturn(List.of());
        when(titleBrowse.labelAutocomplete(any())).thenReturn(List.of());
        when(titleBrowse.listLabels()).thenReturn(List.of());
        when(titleBrowse.listStudioGroups()).thenReturn(List.of());
        when(titleBrowse.listAllCompanies()).thenReturn(List.of("Prestige", "S1 No.1 Style"));
        when(titleBrowse.findLibraryPaged(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(sampleTitle()));
        when(titleBrowse.findRandom(anyInt())).thenReturn(List.of(sampleTitle()));
        when(titleBrowse.findLastVisited(anyInt())).thenReturn(List.of());
        when(titleBrowse.findMostVisited(anyInt())).thenReturn(List.of());
        when(titleBrowse.listVolumes()).thenReturn(List.of());
        when(titleBrowse.buildDashboard()).thenReturn(sampleDashboard());
        when(titleBrowse.getSpotlight(any())).thenReturn(sampleTitle());

        // ── Actresses ──────────────────────────────────────────────────
        when(actressBrowse.findPrefixIndex()).thenReturn(List.of("Y", "A"));
        when(actressBrowse.findTierCountsByPrefix(any())).thenReturn(Map.of("GODDESS", 2, "POPULAR", 5));
        when(actressBrowse.findRandom(anyInt())).thenReturn(List.of(sampleActress()));
        when(actressBrowse.findAllPaged(anyInt(), anyInt())).thenReturn(List.of(sampleActress()));
        when(actressBrowse.findByPrefixPaged(any(), any(), anyInt(), anyInt())).thenReturn(List.of(sampleActress()));
        when(actressBrowse.findByTierPaged(any(), any(), anyInt(), anyInt())).thenReturn(List.of(sampleActress()));
        when(actressBrowse.findFavoritesPaged(anyInt(), anyInt())).thenReturn(List.of());
        when(actressBrowse.findBookmarksPaged(anyInt(), anyInt())).thenReturn(List.of());
        when(actressBrowse.findById(anyLong())).thenReturn(java.util.Optional.of(sampleActress()));
        when(actressBrowse.buildDashboard()).thenReturn(sampleActressDashboard());
        when(actressBrowse.getSpotlight(any())).thenReturn(sampleActress());

        // ── Search ─────────────────────────────────────────────────────
        // Use LinkedHashMap so null coverUrl is allowed (Map.of rejects nulls).
        java.util.Map<String, Object> actressHit = new java.util.LinkedHashMap<>();
        actressHit.put("id", 1L);
        actressHit.put("canonicalName", "Yua Mikami");
        actressHit.put("tier", "GODDESS");
        actressHit.put("grade", "SSS");
        actressHit.put("favorite", false);
        actressHit.put("bookmark", false);
        actressHit.put("titleCount", 100);
        actressHit.put("coverUrl", null);
        java.util.Map<String, Object> searchResult = new java.util.LinkedHashMap<>();
        searchResult.put("actresses",   List.of(actressHit));
        searchResult.put("titles",      List.of());
        searchResult.put("labels",      List.of());
        searchResult.put("companies",   List.of());
        searchResult.put("avActresses", List.of());
        when(searchService.search(anyString(), anyBoolean(), anyBoolean())).thenReturn(searchResult);
        when(searchService.searchByCodePrefix(anyString(), anyInt())).thenReturn(List.of());

        WebServer server = new WebServer(0, titleBrowse, actressBrowse, null, null, null, null,
                null, null, searchService);
        server.start();
        return server;
    }

    private static TitleSummary sampleTitle() {
        return TitleSummary.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP")
                .actressName("Yua Mikami").actressTier("GODDESS").actressId(1L)
                .addedDate("2024-01-01")
                .tags(List.of())
                .actresses(List.of())
                .locations(List.of())
                .nasPaths(List.of())
                .locationEntries(List.of())
                .build();
    }

    private static ActressSummary sampleActress() {
        return ActressSummary.builder()
                .id(1L).canonicalName("Yua Mikami").tier("GODDESS").grade("SSS")
                .titleCount(100).favorite(false).bookmark(false).rejected(false)
                .coverUrls(List.of()).folderPaths(List.of())
                .build();
    }

    private static TitleDashboard sampleDashboard() {
        return new TitleDashboard(
                List.of(sampleTitle()),  // onDeck
                List.of(sampleTitle()),  // justAdded
                List.of(),               // fromFavoriteLabels
                List.of(),               // recentlyViewed
                sampleTitle(),           // spotlight
                List.of(),               // forgottenAttic
                List.of(),               // forgottenFavorites
                List.of(),               // onThisDay
                List.of(),               // topLabels
                new LibraryStatsDto(1L, 1L, 0L, 0L, 1L)  // libraryStats
        );
    }

    private static ActressBrowseService.ActressDashboard sampleActressDashboard() {
        return new ActressBrowseService.ActressDashboard(
                sampleActress(),  // spotlight
                List.of(),        // birthdaysToday
                List.of(),        // newFaces
                List.of(),        // bookmarks
                List.of(sampleActress()),  // recentlyViewed
                List.of(),        // undiscoveredElites
                List.of(),        // forgottenGems
                List.of(),        // topGroups
                List.of(),        // researchGaps
                null              // libraryStats
        );
    }
}
