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

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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

        TitleSummary s = service.findTitlesByActress(1L, 0, 24, null).get(0);
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

        List<String> tags = service.findTitlesByActress(1L, 0, 24, null).get(0).getTags();
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

        assertEquals(List.of("Hatano Yui", "波多野結衣"), s.getAliases());
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

    // ── helpers ───────────────────────────────────────────────────────────

    private static Actress actress(String name) {
        return Actress.builder()
                .id(1L).canonicalName(name).tier(Actress.Tier.LIBRARY)
                .favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
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
