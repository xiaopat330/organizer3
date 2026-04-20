package com.organizer3.web;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.organizer3.model.ActressAlias;
import com.organizer3.web.ActressSummary;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActressBrowseServiceTest {

    @Mock ActressRepository actressRepo;
    @Mock TitleRepository titleRepo;
    @Mock CoverPath coverPath;
    @Mock LabelRepository labelRepo;
    @Mock ActressNameLookup nameLookup;

    ActressBrowseService service;

    @BeforeEach
    void setUp() {
        service = new ActressBrowseService(actressRepo, titleRepo, coverPath,
                Map.of("vol-a", "//pandora/jav_A"), labelRepo, nameLookup, null);
        lenient().when(actressRepo.findAliases(anyLong())).thenReturn(List.of());
        lenient().when(labelRepo.findAllAsMap()).thenReturn(Map.of());
    }

    // ── Prefix index ──────────────────────────────────────────────────────

    @Test
    void prefixIndexExcludesEmptyLetters() {
        when(actressRepo.findAll()).thenReturn(List.of(
                actress("Airi Suzumura"),
                actress("Yui Hatano")
        ));

        assertEquals(List.of("A", "Y"), service.findPrefixIndex());
    }

    @Test
    void prefixIndexSortsAlphabetically() {
        when(actressRepo.findAll()).thenReturn(List.of(
                actress("Nana Ogura"),
                actress("Airi Suzumura"),
                actress("Mako Oda")
        ));

        assertEquals(List.of("A", "M", "N"), service.findPrefixIndex());
    }

    @Test
    void prefixIndexAlwaysReturnsSingleLetters() {
        List<Actress> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(actress("Maki Actor" + i));
        }
        many.add(actress("Mio Yamada"));
        many.add(actress("Airi Suzumura"));

        when(actressRepo.findAll()).thenReturn(many);

        List<String> index = service.findPrefixIndex();

        assertTrue(index.contains("M"), "Should have single-letter M");
        assertTrue(index.contains("A"), "Should have single-letter A");
        assertFalse(index.contains("MA"), "Should not have two-char prefixes");
    }

    @Test
    void prefixIndexHandlesSingleNameActresses() {
        when(actressRepo.findAll()).thenReturn(List.of(actress("Madonna")));

        assertEquals(List.of("M"), service.findPrefixIndex());
    }

    // ── findByPrefix ──────────────────────────────────────────────────────

    @Test
    void findByPrefixReturnsTitleCountAndBasicFields() {
        Actress a = actress("Mai Hagiwara");
        Title t1 = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Mai Hagiwara/ABP-00001");
        Title t2 = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Mai Hagiwara/ABP-00002");

        when(actressRepo.findByFirstNamePrefix("MA")).thenReturn(List.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of(t1, t2));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        List<ActressSummary> results = service.findByPrefix("MA");

        assertEquals(1, results.size());
        ActressSummary s = results.get(0);
        assertEquals("Mai Hagiwara", s.getCanonicalName());
        assertEquals("LIBRARY", s.getTier());
        assertFalse(s.isFavorite());
        assertEquals(2, s.getTitleCount());
    }

    @Test
    void findByPrefixIncludesCoverUrls() {
        Actress a = actress("Yui Hatano");
        Title t = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Yui Hatano/ABP-00001");
        Path coverFile = Path.of("/data/covers/ABP/ABP-00001.jpg");

        when(actressRepo.findByFirstNamePrefix("Y")).thenReturn(List.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of(t));
        when(coverPath.find(t)).thenReturn(Optional.of(coverFile));

        ActressSummary s = service.findByPrefix("Y").get(0);

        assertEquals(List.of("/covers/ABP/ABP-00001.jpg"), s.getCoverUrls());
    }

    @Test
    void findByPrefixDerivesSmBFolderPathFromStarredTitles() {
        Actress a = actress("Yui Hatano");
        Title t = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Yui Hatano/ABP-00001");

        when(actressRepo.findByFirstNamePrefix("Y")).thenReturn(List.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of(t));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        ActressSummary s = service.findByPrefix("Y").get(0);

        assertEquals(List.of("//pandora/jav_A/stars/popular/Yui Hatano"), s.getFolderPaths());
    }

    @Test
    void findByPrefixExcludesUnstructuredPartitionsFromFolderPaths() {
        Actress a = actress("Yui Hatano");
        // Title in an unstructured partition — no actress subfolder
        Title t = title(a.getId(), "vol-a", "queue", "/queue/ABP-00001");

        when(actressRepo.findByFirstNamePrefix("Y")).thenReturn(List.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of(t));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        ActressSummary s = service.findByPrefix("Y").get(0);

        assertTrue(s.getFolderPaths().isEmpty());
    }

    @Test
    void findByPrefixDeduplicatesFolderPaths() {
        Actress a = actress("Yui Hatano");
        // Two titles in the same actress folder — should produce one folder path
        Title t1 = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Yui Hatano/ABP-00001");
        Title t2 = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Yui Hatano/ABP-00002");

        when(actressRepo.findByFirstNamePrefix("Y")).thenReturn(List.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of(t1, t2));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        ActressSummary s = service.findByPrefix("Y").get(0);

        assertEquals(1, s.getFolderPaths().size());
    }

    @Test
    void findByPrefixMapsFavoriteFlag() {
        Actress fav = Actress.builder()
                .id(1L).canonicalName("Yui Hatano").tier(Actress.Tier.POPULAR)
                .favorite(true).firstSeenAt(LocalDate.of(2023, 1, 1)).build();

        when(actressRepo.findByFirstNamePrefix("Y")).thenReturn(List.of(fav));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());

        assertTrue(service.findByPrefix("Y").get(0).isFavorite());
    }

    // ── findByTier ────────────────────────────────────────────────────────

    @Test
    void findByTierReturnsSummariesForMatchingActresses() {
        Actress goddess = Actress.builder()
                .id(2L).canonicalName("Yua Mikami").tier(Actress.Tier.GODDESS)
                .favorite(true).firstSeenAt(LocalDate.of(2020, 1, 1)).build();

        when(actressRepo.findByTier(Actress.Tier.GODDESS)).thenReturn(List.of(goddess));
        when(titleRepo.findByActress(2L)).thenReturn(List.of());

        List<ActressSummary> results = service.findByTier("GODDESS");

        assertEquals(1, results.size());
        assertEquals("Yua Mikami", results.get(0).getCanonicalName());
        assertEquals("GODDESS", results.get(0).getTier());
    }

    @Test
    void findByTierIsCaseInsensitive() {
        when(actressRepo.findByTier(Actress.Tier.POPULAR)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.findByTier("popular"));
        verify(actressRepo).findByTier(Actress.Tier.POPULAR);
    }

    @Test
    void findByTierThrowsForUnknownTier() {
        assertThrows(IllegalArgumentException.class, () -> service.findByTier("MEGASTAR"));
    }

    // ── findTitlesByActress ───────────────────────────────────────────────

    @Test
    void findTitlesByActressEnrichesWithLabelInfo() {
        Title t = title(1L, "vol-a", "stars/popular", "/stars/popular/Yui Hatano/ABP-00001");

        when(titleRepo.findByActressPaged(1L, 24, 0)).thenReturn(List.of(t));
        when(coverPath.find(any())).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(
                Map.of("ABP", new Label("ABP", "Absolutely Perfect", "Prestige", null, null)));

        TitleSummary s = service.findTitlesByActress(1L, 0, 24, null, List.of()).get(0);
        assertEquals("Prestige", s.getCompanyName());
        assertEquals("Absolutely Perfect", s.getLabelName());
        assertEquals(1L, s.getActressId());
    }

    @Test
    void findTitlesByActressMergesDirectAndLabelTagsDeduped() {
        Title t = Title.builder()
                .id(1L).code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .actressId(1L)
                .tags(List.of("creampie", "solo-actress"))
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("stars")
                        .path(Path.of("/stars/ABP-001"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
        Label label = new Label("ABP", "Prestige", "Prestige International", null, null,
                null, null, null, null, List.of("exclusive-actress", "solo-actress"));

        when(titleRepo.findByActressPaged(1L, 24, 0)).thenReturn(List.of(t));
        when(coverPath.find(any())).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("ABP", label));

        List<String> tags = service.findTitlesByActress(1L, 0, 24, null, List.of()).get(0).getTags();
        assertEquals(1, tags.stream().filter("solo-actress"::equals).count(), "solo-actress must appear once");
        assertTrue(tags.contains("creampie"));
        assertTrue(tags.contains("exclusive-actress"));
        assertEquals(tags, tags.stream().sorted().toList(), "tags must be sorted");
    }

    // ── companies and aliases ──────────────────────────────────────────

    @Test
    void findByIdIncludesCompaniesFromTitleLabels() {
        Actress a = actress("Yui Hatano");
        Title t1 = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Yui Hatano/ABP-00001");
        Title t2 = title(a.getId(), "vol-a", "stars/popular", "/stars/popular/Yui Hatano/SSIS-001");
        // give t2 a different label
        Title t2WithLabel = Title.builder()
                .id(2L).code("SSIS-001").baseCode("SSIS-00001").label("SSIS").seqNum(1)
                .actressId(a.getId())
                .locations(List.of(TitleLocation.builder()
                        .titleId(2L).volumeId("vol-a").partitionId("stars/popular")
                        .path(Path.of("/stars/popular/Yui Hatano/SSIS-001"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();

        when(actressRepo.findById(a.getId())).thenReturn(Optional.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of(t1, t2WithLabel));
        when(coverPath.find(any())).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of(
                "ABP", new Label("ABP", "Absolutely Perfect", "Prestige", null, null),
                "SSIS", new Label("SSIS", "S1 Special", "S1", null, null)
        ));

        ActressSummary s = service.findById(a.getId()).orElseThrow();

        assertEquals(List.of("Prestige", "S1"), s.getCompanies());
    }

    @Test
    void findByIdIncludesAliases() {
        Actress a = actress("Yui Hatano");

        when(actressRepo.findById(a.getId())).thenReturn(Optional.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of());
        when(actressRepo.findAliases(a.getId())).thenReturn(List.of(
                new ActressAlias(a.getId(), "Hatano Yui"),
                new ActressAlias(a.getId(), "波多野結衣")
        ));

        ActressSummary s = service.findById(a.getId()).orElseThrow();

        List<String> aliasNames = s.getAliases().stream()
                .map(ActressSummary.AliasDto::getName).toList();
        assertEquals(List.of("Hatano Yui", "波多野結衣"), aliasNames);
    }

    @Test
    void findByIdReturnsEmptyListsWhenNoCompaniesOrAliases() {
        Actress a = actress("Yui Hatano");

        when(actressRepo.findById(a.getId())).thenReturn(Optional.of(a));
        when(titleRepo.findByActress(a.getId())).thenReturn(List.of());

        ActressSummary s = service.findById(a.getId()).orElseThrow();

        assertTrue(s.getCompanies().isEmpty());
        assertTrue(s.getAliases().isEmpty());
    }

    // ── Flag toggles (favorite / bookmark / rejected) ─────────────────────

    @Test
    void toggleFavoriteFlipsFavoriteAndLeavesBookmarkAlone() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).bookmark(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleFavorite(42L).orElseThrow();

        assertEquals(42L, result.id());
        assertTrue(result.favorite());
        assertTrue(result.bookmark(), "bookmark should be untouched by favorite toggle");
        assertFalse(result.rejected());
        verify(actressRepo).setFlags(42L, true, true, false);
    }

    @Test
    void toggleFavoriteOnRejectedActressClearsRejected() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).rejected(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleFavorite(42L).orElseThrow();

        assertTrue(result.favorite());
        assertFalse(result.rejected(), "favoriting must clear rejected");
        verify(actressRepo).setFlags(42L, true, false, false);
    }

    @Test
    void toggleFavoriteOffLeavesRejectedAlone() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).favorite(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleFavorite(42L).orElseThrow();

        assertFalse(result.favorite());
        assertFalse(result.rejected());
        verify(actressRepo).setFlags(42L, false, false, false);
    }

    @Test
    void toggleBookmarkOnRejectedActressClearsRejected() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).rejected(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleBookmark(42L).orElseThrow();

        assertTrue(result.bookmark());
        assertFalse(result.rejected(), "bookmarking must clear rejected");
        verify(actressRepo).setFlags(42L, false, true, false);
    }

    @Test
    void toggleBookmarkLeavesFavoriteAlone() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).favorite(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleBookmark(42L).orElseThrow();

        assertTrue(result.favorite(), "favorite should be untouched by bookmark toggle");
        assertTrue(result.bookmark());
        verify(actressRepo).setFlags(42L, true, true, false);
    }

    @Test
    void toggleRejectedOnFavoriteActressClearsFavoriteAndBookmark() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).favorite(true).bookmark(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleRejected(42L).orElseThrow();

        assertTrue(result.rejected());
        assertFalse(result.favorite(), "rejecting must clear favorite");
        assertFalse(result.bookmark(), "rejecting must clear bookmark");
        verify(actressRepo).setFlags(42L, false, false, true);
    }

    @Test
    void toggleRejectedOffLeavesOtherFlagsFalse() {
        Actress a = Actress.builder().id(42L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY).rejected(true)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        when(actressRepo.findById(42L)).thenReturn(Optional.of(a));

        ActressBrowseService.FlagState result = service.toggleRejected(42L).orElseThrow();

        assertFalse(result.rejected());
        assertFalse(result.favorite());
        assertFalse(result.bookmark());
        verify(actressRepo).setFlags(42L, false, false, false);
    }

    @Test
    void toggleFavoriteReturnsEmptyWhenActressNotFound() {
        when(actressRepo.findById(999L)).thenReturn(Optional.empty());

        assertTrue(service.toggleFavorite(999L).isEmpty());
        verify(actressRepo, never()).setFlags(anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    // ── Dashboard composition ─────────────────────────────────────────────

    @Test
    void buildDashboardWiresAllModules() {
        Actress spot   = elite(10L, "Spotlight");
        Actress bday   = elite(11L, "BirthdayActress");
        Actress fresh  = actress("Fresh");
        Actress book   = actress("Bookmarked");
        Actress lastV  = actress("LastVisited");
        Actress undisc = elite(15L, "Undiscovered");
        Actress gem    = elite(16L, "Gem");

        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of(spot));
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of(bday));
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of(fresh));
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of(book));
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of(lastV));
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of(undisc));
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of(gem));
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of());
        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(
                new ActressRepository.ActressLibraryStats(100, 12, 5, 4, 2, 3, 6));
        when(titleRepo.findByActress(anyLong())).thenReturn(List.of());

        ActressBrowseService.ActressDashboard d = service.buildDashboard();

        assertNotNull(d.spotlight());
        assertEquals("Spotlight", d.spotlight().getCanonicalName());
        assertEquals(1, d.birthdaysToday().size());
        assertEquals(1, d.newFaces().size());
        assertEquals(1, d.bookmarks().size());
        assertEquals(1, d.recentlyViewed().size());
        assertEquals(1, d.undiscoveredElites().size());
        assertEquals(1, d.forgottenGems().size());
        assertEquals(100L, d.libraryStats().totalActresses());
        assertEquals(12L, d.libraryStats().favorites());
    }

    @Test
    void buildDashboardExcludesSpotlightFromSubsequentModules() {
        Actress spot = elite(10L, "Spotlight");
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of(spot));
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findNewFacesFallback(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of());
        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(
                new ActressRepository.ActressLibraryStats(0, 0, 0, 0, 0, 0, 0));
        when(titleRepo.findByActress(anyLong())).thenReturn(List.of());

        service.buildDashboard();

        // After spotlight picks id=10, the next call (newFaces) should receive {10} in excludeIds.
        verify(actressRepo).findNewFaces(any(), eq(6), eq(java.util.Set.of(10L)));
    }

    @Test
    void buildDashboardFallsBackToNewFacesFallbackWhenWindowIsEmpty() {
        Actress fallback = actress("Fallback");
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findNewFacesFallback(anyInt(), any())).thenReturn(List.of(fallback));
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of());
        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(
                new ActressRepository.ActressLibraryStats(0, 0, 0, 0, 0, 0, 0));
        when(titleRepo.findByActress(anyLong())).thenReturn(List.of());

        ActressBrowseService.ActressDashboard d = service.buildDashboard();

        assertEquals(1, d.newFaces().size());
        assertEquals("Fallback", d.newFaces().get(0).getCanonicalName());
        verify(actressRepo).findNewFacesFallback(eq(6), any());
    }

    @Test
    void buildDashboardEmitsBirthdaysAsEmptyListWhenNoMatches() {
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findNewFacesFallback(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of());
        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(
                new ActressRepository.ActressLibraryStats(0, 0, 0, 0, 0, 0, 0));

        ActressBrowseService.ActressDashboard d = service.buildDashboard();
        assertNotNull(d.birthdaysToday());
        assertTrue(d.birthdaysToday().isEmpty(), "JS layer hides the panel when empty");
    }

    @Test
    void getSpotlightReturnsNullWhenPoolEmpty() {
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of());
        assertNull(service.getSpotlight(null));
    }

    @Test
    void getSpotlightExcludesGivenId() {
        Actress spot = elite(20L, "Other");
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), eq(java.util.Set.of(99L))))
                .thenReturn(List.of(spot));
        when(titleRepo.findByActress(20L)).thenReturn(List.of());

        ActressSummary result = service.getSpotlight(99L);
        assertNotNull(result);
        assertEquals("Other", result.getCanonicalName());
    }

    @Test
    void researchGapsMarksCompletenessDots() {
        // Actress with biography but missing other fields.
        Actress complete = Actress.builder()
                .id(50L).canonicalName("Complete").tier(Actress.Tier.SUPERSTAR)
                .stageName("name").dateOfBirth(LocalDate.of(1990, 1, 1)).birthplace("Tokyo")
                .heightCm(160).bust(85).waist(58).hip(86)
                .biography("a real bio")
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
        Actress sparse = Actress.builder()
                .id(51L).canonicalName("Sparse").tier(Actress.Tier.SUPERSTAR)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();

        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findNewFacesFallback(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of(complete, sparse));
        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(
                new ActressRepository.ActressLibraryStats(0, 0, 0, 0, 0, 0, 0));
        Title t = title(50L, "vol-a", "stars/popular", "/stars/popular/Complete/ABP-001");
        when(titleRepo.findByActress(50L)).thenReturn(List.of(t));
        when(titleRepo.findByActress(51L)).thenReturn(List.of());
        when(coverPath.find(any())).thenReturn(Optional.empty());

        ActressBrowseService.ActressDashboard d = service.buildDashboard();

        assertEquals(2, d.researchGaps().size());
        var completeGap = d.researchGaps().get(0);
        assertTrue(completeGap.profileFilled());
        assertTrue(completeGap.physicalFilled());
        assertTrue(completeGap.biographyFilled());
        assertTrue(completeGap.portfolioCovered());

        var sparseGap = d.researchGaps().get(1);
        assertFalse(sparseGap.profileFilled());
        assertFalse(sparseGap.physicalFilled());
        assertFalse(sparseGap.biographyFilled());
        assertFalse(sparseGap.portfolioCovered());
    }

    @Test
    void topGroupsAggregatesPerActressEngagement() {
        // Two actresses share an engagement on a label whose company maps to a real studio group.
        // "S1 No.1 Style" is a company under the "WILL Co., Ltd." group in studios.yaml.
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findNewFacesFallback(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(
                new ActressRepository.ActressLibraryStats(0, 0, 0, 0, 0, 0, 0));

        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of(
                new ActressRepository.ActressLabelEngagement(1L, "SSIS", 5, true, false),
                new ActressRepository.ActressLabelEngagement(1L, "SNIS", 0, false, true), // same actress, second label
                new ActressRepository.ActressLabelEngagement(2L, "SSIS", 1, false, false),
                new ActressRepository.ActressLabelEngagement(3L, "ABP", 0, false, false)  // unmapped → no group
        ));
        when(labelRepo.findAllAsMap()).thenReturn(Map.of(
                "SSIS", new Label("SSIS", "S1 Special", "S1 No.1 Style", null, null),
                "SNIS", new Label("SNIS", "S1 Premium",  "S1 No.1 Style", null, null),
                "ABP",  new Label("ABP",  "Absolutely Perfect", "Prestige Unmapped", null, null)
        ));

        ActressBrowseService.ActressDashboard d = service.buildDashboard();

        // Only the WILL group should appear (ABP's company is unmapped).
        assertEquals(1, d.topGroups().size());
        var group = d.topGroups().get(0);
        assertEquals("WILL Co., Ltd.", group.name());
        // Two distinct actresses contributed (1 and 2); actress 3 was filtered.
        assertEquals(2, group.actressCount());
        assertTrue(group.score() > 0);
    }

    private static Actress elite(long id, String name) {
        return Actress.builder()
                .id(id).canonicalName(name).tier(Actress.Tier.SUPERSTAR)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build();
    }

    // ── findByStudioGroupPaged (with optional company filter) ─────────────────

    @Test
    void findByStudioGroupPagedExpandsSlugToAllCompanies() {
        // "will" is a real group in studios.yaml whose companies include Moodyz, S1 No.1 Style, ...
        when(actressRepo.findByStudioGroupCompaniesPaged(any(), anyInt(), anyInt()))
                .thenReturn(List.of(actress("Yua Mikami")));

        List<ActressSummary> result =
                service.findByStudioGroupPaged("will", null, 0, 24);

        assertEquals(1, result.size());
        // Verify the repo was called with the full company list (must contain at least one
        // known WILL member like Moodyz).
        verify(actressRepo).findByStudioGroupCompaniesPaged(
                argThat((List<String> list) -> list != null && list.contains("Moodyz")),
                eq(24), eq(0));
    }

    @Test
    void findByStudioGroupPagedNarrowsToSingleCompanyWhenFilterMatches() {
        when(actressRepo.findByStudioGroupCompaniesPaged(any(), anyInt(), anyInt()))
                .thenReturn(List.of(actress("Moodyz Star")));

        service.findByStudioGroupPaged("will", "Moodyz", 0, 24);

        // Repo should receive a singleton list with just the filtered company.
        verify(actressRepo).findByStudioGroupCompaniesPaged(
                eq(List.of("Moodyz")), eq(24), eq(0));
    }

    @Test
    void findByStudioGroupPagedReturnsEmptyWhenCompanyFilterIsNotInGroup() {
        // "Prestige" is not a member of the "will" group → must NOT fall back to all companies.
        List<ActressSummary> result =
                service.findByStudioGroupPaged("will", "Prestige", 0, 24);

        assertEquals(List.of(), result);
        // Repo should never be hit because the filter is rejected upfront.
        verify(actressRepo, never()).findByStudioGroupCompaniesPaged(any(), anyInt(), anyInt());
    }

    @Test
    void findByStudioGroupPagedReturnsEmptyForUnknownSlug() {
        List<ActressSummary> result =
                service.findByStudioGroupPaged("nonexistent-slug", null, 0, 24);

        assertEquals(List.of(), result);
        verify(actressRepo, never()).findByStudioGroupCompaniesPaged(any(), anyInt(), anyInt());
    }

    @Test
    void findByStudioGroupPagedTreatsBlankCompanyFilterAsAll() {
        when(actressRepo.findByStudioGroupCompaniesPaged(any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.findByStudioGroupPaged("will", "", 0, 24);
        service.findByStudioGroupPaged("will", "   ", 0, 24);

        // Both blank-filter calls should expand to the full company list (≫ 1 entry).
        verify(actressRepo, times(2)).findByStudioGroupCompaniesPaged(
                argThat((List<String> list) -> list != null && list.size() > 1),
                eq(24), eq(0));
    }

    // ── listGroupCompaniesByTitleCount ────────────────────────────────────

    @Test
    void listGroupCompaniesByTitleCountOrdersByCountDescThenName() {
        // Mock title counts for a subset of WILL companies; others stay at zero.
        when(titleRepo.countTitlesByCompanies(any())).thenReturn(java.util.Map.of(
                "Moodyz", 50L,
                "S1 No.1 Style", 120L,
                "Madonna", 50L
        ));

        var result = service.listGroupCompaniesByTitleCount("will");

        // First entry must be the highest-count company.
        assertEquals("S1 No.1 Style", result.get(0).company());
        assertEquals(120L, result.get(0).titleCount());

        // The two 50L companies are ordered alphabetically as the tiebreaker.
        var fifties = result.stream()
                .filter(c -> c.titleCount() == 50L)
                .map(ActressBrowseService.CompanyCount::company)
                .toList();
        assertEquals(List.of("Madonna", "Moodyz"), fifties);

        // All companies belonging to the WILL group should appear, even ones with zero titles.
        long zeroCount = result.stream().filter(c -> c.titleCount() == 0L).count();
        assertTrue(zeroCount > 0, "expected zero-count entries for unmatched group members");
    }

    @Test
    void listGroupCompaniesByTitleCountReturnsEmptyForUnknownSlug() {
        var result = service.listGroupCompaniesByTitleCount("nonexistent-slug");
        assertEquals(List.of(), result);
        verify(titleRepo, never()).countTitlesByCompanies(any());
    }

    @Test
    void listGroupCompaniesByTitleCountIncludesEveryGroupCompanyEvenWhenAllZero() {
        when(titleRepo.countTitlesByCompanies(any())).thenReturn(java.util.Map.of());

        var result = service.listGroupCompaniesByTitleCount("will");

        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(c -> c.titleCount() == 0L));
        // Sorted alphabetically by company name when all counts tie at zero.
        var names = result.stream().map(ActressBrowseService.CompanyCount::company).toList();
        var sorted = new java.util.ArrayList<>(names);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, names);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Actress actress(String name) {
        return Actress.builder()
                .id(1L).canonicalName(name).tier(Actress.Tier.LIBRARY)
                .favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
    }

    // ── updateAliases ─────────────────────────────────────────────────────

    @Test
    void updateAliasesSucceedsWithNoConflicts() {
        when(actressRepo.findById(1L)).thenReturn(Optional.of(actress(1L, "Aya Sazanami")));
        when(actressRepo.findByCanonicalName("Alias One")).thenReturn(Optional.empty());
        when(actressRepo.resolveByName("Alias One")).thenReturn(Optional.empty());

        var result = service.updateAliases(1L, List.of("Alias One"));

        assertTrue(result.ok());
        verify(actressRepo).replaceAllAliases(1L, List.of("Alias One"));
    }

    @Test
    void updateAliasesRejectsAliasMatchingOtherCanonicalName() {
        when(actressRepo.findById(1L)).thenReturn(Optional.of(actress(1L, "Aya Sazanami")));
        when(actressRepo.findByCanonicalName("Hibiki Otsuki")).thenReturn(Optional.of(actress(2L, "Hibiki Otsuki")));

        var result = service.updateAliases(1L, List.of("Hibiki Otsuki"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("Hibiki Otsuki"));
        verify(actressRepo, never()).replaceAllAliases(anyLong(), any());
    }

    @Test
    void updateAliasesRejectsAliasOwnedByOtherActress() {
        when(actressRepo.findById(1L)).thenReturn(Optional.of(actress(1L, "Aya Sazanami")));
        when(actressRepo.findByCanonicalName("Eri Ando")).thenReturn(Optional.empty());
        when(actressRepo.resolveByName("Eri Ando")).thenReturn(Optional.of(actress(2L, "Hibiki Otsuki")));

        var result = service.updateAliases(1L, List.of("Eri Ando"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("Eri Ando"));
        verify(actressRepo, never()).replaceAllAliases(anyLong(), any());
    }

    @Test
    void updateAliasesAllowsActressToKeepOwnAlias() {
        Actress aya = actress(1L, "Aya Sazanami");
        when(actressRepo.findById(1L)).thenReturn(Optional.of(aya));
        when(actressRepo.findByCanonicalName("A-chan")).thenReturn(Optional.empty());
        when(actressRepo.resolveByName("A-chan")).thenReturn(Optional.of(aya));

        var result = service.updateAliases(1L, List.of("A-chan"));

        assertTrue(result.ok());
        verify(actressRepo).replaceAllAliases(1L, List.of("A-chan"));
    }

    @Test
    void updateAliasesReturnsConflictForUnknownActress() {
        when(actressRepo.findById(9999L)).thenReturn(Optional.empty());

        var result = service.updateAliases(9999L, List.of("Anything"));

        assertFalse(result.ok());
        verify(actressRepo, never()).replaceAllAliases(anyLong(), any());
    }

    @Test
    void updateAliasesStripsBlankEntriesBeforeSaving() {
        when(actressRepo.findById(1L)).thenReturn(Optional.of(actress(1L, "Aya Sazanami")));
        when(actressRepo.findByCanonicalName("Real Name")).thenReturn(Optional.empty());
        when(actressRepo.resolveByName("Real Name")).thenReturn(Optional.empty());

        service.updateAliases(1L, List.of("  ", "Real Name", ""));

        verify(actressRepo).replaceAllAliases(1L, List.of("Real Name"));
    }

    private static Actress actress(long id, String name) {
        return Actress.builder().id(id).canonicalName(name)
                .tier(Actress.Tier.LIBRARY).firstSeenAt(LocalDate.now()).build();
    }

    private static Title title(long actressId, String volumeId, String partitionId, String path) {
        return Title.builder()
                .id(1L).code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .actressId(actressId)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId(volumeId).partitionId(partitionId)
                        .path(Path.of(path))
                        .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
    }
}
