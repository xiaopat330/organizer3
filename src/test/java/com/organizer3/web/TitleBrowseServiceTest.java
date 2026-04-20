package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TitleBrowseServiceTest {

    @Mock TitleRepository titleRepo;
    @Mock ActressRepository actressRepo;
    @Mock CoverPath coverPath;
    @Mock LabelRepository labelRepo;
    @Mock TitleActressRepository titleActressRepo;
    @Mock WatchHistoryRepository watchHistoryRepo;

    TitleBrowseService service;

    @BeforeEach
    void setUp() {
        service = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, titleActressRepo, watchHistoryRepo, Map.of());
    }

    @Test
    void mapsActressNameAndCoverUrl() {
        Title title = title("ABP-123", "ABP-00123", "ABP", 10L, LocalDate.of(2024, 1, 15));
        Actress actress = Actress.builder().id(10L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        Path coverFile = Path.of("/data/covers/ABP/ABP-00123.jpg");

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(actressRepo.findById(10L)).thenReturn(Optional.of(actress));
        when(coverPath.find(title)).thenReturn(Optional.of(coverFile));

        List<TitleSummary> results = service.findRecent(0, 24);

        assertEquals(1, results.size());
        TitleSummary s = results.get(0);
        assertEquals("ABP-123", s.getCode());
        assertEquals("ABP-00123", s.getBaseCode());
        assertEquals("ABP", s.getLabel());
        assertEquals("Yui Hatano", s.getActressName());
        assertEquals("POPULAR", s.getActressTier());
        assertEquals("2024-01-15", s.getAddedDate());
        assertEquals("/covers/ABP/ABP-00123.jpg", s.getCoverUrl());
        assertEquals("/mnt/vol-a/stars/ABP-123", s.getLocation());
    }

    @Test
    void nullActressIdYieldsNullActressTier() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).getActressTier());
    }

    @Test
    void mapsPathAsLocation() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertEquals("/mnt/vol-a/stars/ABP-123", service.findRecent(0, 24).get(0).getLocation());
    }

    @Test
    void nullActressIdYieldsNullActressName() {
        Title title = title("SSIS-001", "SSIS-00001", "SSIS", null, LocalDate.of(2024, 6, 1));

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findRecent(0, 24);

        assertNull(results.get(0).getActressName());
        verifyNoInteractions(actressRepo);
    }

    @Test
    void missingCoverYieldsNullCoverUrl() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).getCoverUrl());
    }

    @Test
    void nullAddedDateYieldsNullAddedDate() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).getAddedDate());
    }

    @Test
    void limitsAreCappedAtMax() {
        when(titleRepo.findRecent(TitleBrowseService.MAX_LIMIT, 0)).thenReturn(List.of());

        service.findRecent(0, 9999);

        verify(titleRepo).findRecent(TitleBrowseService.MAX_LIMIT, 0);
    }

    @Test
    void deduplicatesActressLookups() {
        Actress actress = Actress.builder().id(5L).canonicalName("Airi Suzumura")
                .tier(Actress.Tier.MINOR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        Title t1 = title("ABP-001", "ABP-00001", "ABP", 5L, null);
        Title t2 = title("ABP-002", "ABP-00002", "ABP", 5L, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(t1, t2));
        when(actressRepo.findById(5L)).thenReturn(Optional.of(actress));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findRecent(0, 24);

        assertEquals("Airi Suzumura", results.get(0).getActressName());
        assertEquals("Airi Suzumura", results.get(1).getActressName());
        verify(actressRepo, times(1)).findById(5L); // only one DB call despite two titles
    }

    @Test
    void mapsActressIdToTitleSummary() {
        Title title = title("ABP-123", "ABP-00123", "ABP", 10L, null);
        Actress actress = Actress.builder().id(10L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(actressRepo.findById(10L)).thenReturn(Optional.of(actress));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertEquals(10L, service.findRecent(0, 24).get(0).getActressId());
    }

    @Test
    void mapsCompanyAndLabelNameFromLabelRepo() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(
                Map.of("ABP", new Label("ABP", "Absolutely Perfect", "Prestige", null, null)));

        TitleSummary s = service.findRecent(0, 24).get(0);
        assertEquals("Prestige", s.getCompanyName());
        assertEquals("Absolutely Perfect", s.getLabelName());
    }

    @Test
    void missingLabelEntryYieldsNullCompanyAndLabelName() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        // labelRepo returns empty map by default — no entry for ABP

        TitleSummary s = service.findRecent(0, 24).get(0);
        assertNull(s.getCompanyName());
        assertNull(s.getLabelName());
    }

    // --- findByVolumeQueue ---

    @Test
    void findByVolumeQueueDelegatesToVolumeAndPartitionRepo() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, LocalDate.of(2024, 3, 1));

        when(titleRepo.findByVolumeAndPartition("vol-a", "queue", 24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findByVolumeQueue("vol-a", 0, 24);

        assertEquals(1, results.size());
        assertEquals("ABP-123", results.get(0).getCode());
        verify(titleRepo).findByVolumeAndPartition("vol-a", "queue", 24, 0);
    }

    @Test
    void findByVolumeQueueInfersActressFromStarsCopyViaBaseCode() {
        // Queue title has a base_code but no actress_id (unorganized)
        Title queueTitle = Title.builder()
                .id(1L).code("ABP-123").baseCode("ABP-00123").label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("queue")
                        .path(Path.of("/mnt/vol-a/queue/ABP-123"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
        Title starsTitle = title("ABP-123", "ABP-00123", "ABP", 7L, null); // same base_code, has actress
        Actress actress = Actress.builder().id(7L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();

        when(titleRepo.findByVolumeAndPartition("vol-a", "queue", 24, 0)).thenReturn(List.of(queueTitle));
        when(titleRepo.findByBaseCode("ABP-00123")).thenReturn(List.of(queueTitle, starsTitle));
        when(actressRepo.findById(7L)).thenReturn(Optional.of(actress));

        List<TitleSummary> results = service.findByVolumeQueue("vol-a", 0, 24);

        assertEquals(1, results.size());
        assertEquals(7L, results.get(0).getActressId());
        assertEquals("Yui Hatano", results.get(0).getActressName());
    }

    @Test
    void findByVolumeQueueHandlesNullLabel() {
        Title title = titleWithNullLabel("NOCODE-001", null, null);

        when(titleRepo.findByVolumeAndPartition("unsorted", "queue", 24, 0)).thenReturn(List.of(title));

        List<TitleSummary> results = service.findByVolumeQueue("unsorted", 0, 24);

        assertEquals(1, results.size());
        assertNull(results.get(0).getCoverUrl());
        assertNull(results.get(0).getCompanyName());
        assertNull(results.get(0).getLabelName());
        verifyNoInteractions(coverPath);
    }

    // --- findByVolumePartition (pool) ---

    @Test
    void findByVolumePartitionDelegatesToCorrectPartition() {
        Title title = Title.builder()
                .id(1L).code("ABP-123").baseCode("ABP-00123").label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("pool").partitionId("pool")
                        .path(Path.of("/Yui Hatano (ABP-123)"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .addedDate(LocalDate.of(2024, 3, 1))
                        .build()))
                .build();

        when(titleRepo.findByVolumeAndPartition("pool", "pool", 24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findByVolumePartition("pool", "pool", 0, 24);

        assertEquals(1, results.size());
        assertEquals("ABP-123", results.get(0).getCode());
        verify(titleRepo).findByVolumeAndPartition("pool", "pool", 24, 0);
    }

    @Test
    void findByVolumePartitionInfersActressViaBaseCode() {
        Title poolTitle = Title.builder()
                .id(1L).code("ABP-123").baseCode("ABP-00123").label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("pool").partitionId("pool")
                        .path(Path.of("/Yui Hatano (ABP-123)"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
        Title starsTitle = title("ABP-123", "ABP-00123", "ABP", 7L, null);
        Actress actress = Actress.builder().id(7L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();

        when(titleRepo.findByVolumeAndPartition("pool", "pool", 24, 0)).thenReturn(List.of(poolTitle));
        when(titleRepo.findByBaseCode("ABP-00123")).thenReturn(List.of(poolTitle, starsTitle));
        when(actressRepo.findById(7L)).thenReturn(Optional.of(actress));

        List<TitleSummary> results = service.findByVolumePartition("pool", "pool", 0, 24);

        assertEquals(7L, results.get(0).getActressId());
        assertEquals("Yui Hatano", results.get(0).getActressName());
    }

    // --- tags (direct + indirect) ---

    @Test
    void includesLabelTagsInTitleSummaryTags() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);
        Label label = new Label("ABP", "Prestige", "Prestige International", null, null,
                null, null, null, null, List.of("exclusive-actress", "solo-actress"));

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("ABP", label));

        List<String> tags = service.findRecent(0, 24).get(0).getTags();
        assertTrue(tags.contains("exclusive-actress"));
        assertTrue(tags.contains("solo-actress"));
    }

    @Test
    void mergesDirectAndLabelTagsWithoutDuplicates() {
        Title title = Title.builder()
                .id(1L).code("ABP-123").baseCode("ABP-00123").label("ABP").seqNum(1)
                .tags(List.of("creampie", "solo-actress"))
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("stars")
                        .path(Path.of("/mnt/vol-a/stars/ABP-123"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
        Label label = new Label("ABP", "Prestige", "Prestige International", null, null,
                null, null, null, null, List.of("exclusive-actress", "solo-actress"));

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("ABP", label));

        List<String> tags = service.findRecent(0, 24).get(0).getTags();
        // solo-actress appears in both direct and label — must appear exactly once
        assertEquals(1, tags.stream().filter("solo-actress"::equals).count());
        assertTrue(tags.contains("creampie"));
        assertTrue(tags.contains("exclusive-actress"));
    }

    @Test
    void tagsAreSorted() {
        Title title = Title.builder()
                .id(1L).code("ABP-123").baseCode("ABP-00123").label("ABP").seqNum(1)
                .tags(List.of("solo-actress"))
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("stars")
                        .path(Path.of("/mnt/vol-a/stars/ABP-123"))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
        Label label = new Label("ABP", "Prestige", "Prestige International", null, null,
                null, null, null, null, List.of("amateur", "creampie"));

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("ABP", label));

        List<String> tags = service.findRecent(0, 24).get(0).getTags();
        assertEquals(List.of("amateur", "creampie", "solo-actress"), tags);
    }

    @Test
    void noTagsWhenLabelMissingAndTitleHasNone() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        // labelRepo returns empty map — no label found

        assertTrue(service.findRecent(0, 24).get(0).getTags().isEmpty());
    }

    // ── searchByCodePaged ─────────────────────────────────────────────────

    @Test
    void searchByCodePagedDelegatesWithParsedPrefix() {
        Title t = title("ABP-123", "ABP-00123", "ABP", null, null);
        when(titleRepo.findByCodePrefixPaged(eq("ABP"), any(), eq(24), eq(0))).thenReturn(List.of(t));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        assertEquals(1, service.searchByCodePaged("ABP-123", 0, 24).size());
    }

    @Test
    void searchByCodePagedReturnsEmptyWhenNoLabelPrefix() {
        assertTrue(service.searchByCodePaged("", 0, 24).isEmpty());
        assertTrue(service.searchByCodePaged("   ", 0, 24).isEmpty());
        verify(titleRepo, never()).findByCodePrefixPaged(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void searchByCodePagedCapsLimit() {
        when(titleRepo.findByCodePrefixPaged(anyString(), any(), eq(TitleBrowseService.MAX_LIMIT), eq(0)))
                .thenReturn(List.of());
        service.searchByCodePaged("ABP", 0, 9999);
        verify(titleRepo).findByCodePrefixPaged(eq("ABP"), any(), eq(TitleBrowseService.MAX_LIMIT), eq(0));
    }

    // ── findFavoritesPaged / findBookmarksPaged ───────────────────────────

    @Test
    void findFavoritesPagedDelegates() {
        when(titleRepo.findFavoritesPaged(24, 0)).thenReturn(List.of());
        service.findFavoritesPaged(0, 24);
        verify(titleRepo).findFavoritesPaged(24, 0);
    }

    @Test
    void findBookmarksPagedDelegates() {
        when(titleRepo.findBookmarksPaged(24, 0)).thenReturn(List.of());
        service.findBookmarksPaged(0, 24);
        verify(titleRepo).findBookmarksPaged(24, 0);
    }

    // ── findLibraryPaged ───────────────────────────────────────────────────

    @Test
    void findLibraryPagedPassesNullCompanyListWhenCompanyBlank() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(titleRepo.findLibraryPaged(any(), any(), eq(List.of()), eq(List.of()), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.findLibraryPaged(null, null, List.of(), "addedDate", "desc", 0, 24);

        verify(titleRepo).findLibraryPaged(any(), any(), eq(List.of()), eq(List.of()), eq("addedDate"), eq(false), eq(24), eq(0));
    }

    @Test
    void findLibraryPagedResolvesCompanyToLabels() {
        Label abp = new Label("ABP", "Prestige Label", "Prestige", null, null, null, null, null, null, List.of());
        Label mdvr = new Label("MDVR", "Moodyz VR", "Moodyz", null, null, null, null, null, null, List.of());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("ABP", abp, "MDVR", mdvr));
        when(titleRepo.findLibraryPaged(any(), any(), eq(List.of("ABP")), eq(List.of()), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.findLibraryPaged(null, "Prestige", List.of(), null, null, 0, 24);

        verify(titleRepo).findLibraryPaged(any(), any(), eq(List.of("ABP")), eq(List.of()), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void findLibraryPagedReturnsEmptyWhenCompanyHasNoLabels() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());

        assertTrue(service.findLibraryPaged(null, "NonExistentCompany", List.of(), null, null, 0, 24).isEmpty());
        verify(titleRepo, never()).findLibraryPaged(any(), any(), anyList(), anyList(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void findLibraryPagedPassesAscOrder() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(titleRepo.findLibraryPaged(any(), any(), anyList(), anyList(), any(), eq(true), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.findLibraryPaged(null, null, List.of(), "productCode", "asc", 0, 24);

        verify(titleRepo).findLibraryPaged(any(), any(), anyList(), anyList(), eq("productCode"), eq(true), anyInt(), anyInt());
    }

    @Test
    void findLibraryPagedTreatsNullTagsAsEmpty() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(titleRepo.findLibraryPaged(any(), any(), anyList(), eq(List.of()), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.findLibraryPaged(null, null, null, null, null, 0, 24);

        verify(titleRepo).findLibraryPaged(any(), any(), anyList(), eq(List.of()), any(), anyBoolean(), anyInt(), anyInt());
    }

    // ── findByTagsPaged / findRandom ───────────────────────────────────────

    @Test
    void findByTagsPagedDelegates() {
        when(titleRepo.findByTagsPaged(List.of("creampie"), 24, 0)).thenReturn(List.of());
        service.findByTagsPaged(List.of("creampie"), 0, 24);
        verify(titleRepo).findByTagsPaged(List.of("creampie"), 24, 0);
    }

    @Test
    void findRandomDelegatesWithCappedLimit() {
        when(titleRepo.findRandom(TitleBrowseService.MAX_LIMIT)).thenReturn(List.of());
        service.findRandom(9999);
        verify(titleRepo).findRandom(TitleBrowseService.MAX_LIMIT);
    }

    // ── findByVolumePaged / findByVolumePartition ──────────────────────────

    @Test
    void findByVolumePagedDelegates() {
        when(titleRepo.findByVolumePaged("vol-a", 24, 0)).thenReturn(List.of());
        service.findByVolumePaged("vol-a", 0, 24);
        verify(titleRepo).findByVolumePaged("vol-a", 24, 0);
    }

    @Test
    void findByVolumePartitionInfersActressFromBaseCode() {
        Title pool = title("ABP-001", "ABP-00001", "ABP", null, null);
        Title attributed = title("ABP-001-STARS", "ABP-00001", "ABP", 42L, null);
        when(titleRepo.findByVolumeAndPartition("vol-a", "pool", 24, 0)).thenReturn(List.of(pool));
        when(titleRepo.findByBaseCode("ABP-00001")).thenReturn(List.of(attributed));
        when(actressRepo.findById(42L)).thenReturn(Optional.of(
                Actress.builder().id(42L).canonicalName("X").tier(Actress.Tier.POPULAR).build()));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        TitleSummary s = service.findByVolumePartition("vol-a", "pool", 0, 24).get(0);

        assertEquals(Long.valueOf(42L), s.getActressId());
        assertEquals("X", s.getActressName());
    }

    // ── findByVolumePagedFiltered / findByVolumePartitionFiltered ──────────

    @Test
    void findByVolumePagedFilteredFallsThroughWhenNoFilters() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(titleRepo.findByVolumePaged("vol-a", 24, 0)).thenReturn(List.of());
        service.findByVolumePagedFiltered("vol-a", null, List.of(), 0, 24);
        verify(titleRepo).findByVolumePaged("vol-a", 24, 0);
        verify(titleRepo, never()).findByVolumeFiltered(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void findByVolumePagedFilteredUsesFilteredRepoWhenTagsPresent() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(titleRepo.findByVolumeFiltered(eq("vol-a"), eq(List.of()), eq(List.of("creampie")), eq(24), eq(0)))
                .thenReturn(List.of());
        service.findByVolumePagedFiltered("vol-a", null, List.of("creampie"), 0, 24);
        verify(titleRepo).findByVolumeFiltered("vol-a", List.of(), List.of("creampie"), 24, 0);
    }

    @Test
    void findByVolumePagedFilteredReturnsEmptyWhenCompanyUnknown() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        assertTrue(service.findByVolumePagedFiltered("vol-a", "Ghost", List.of(), 0, 24).isEmpty());
        verifyNoInteractions(titleRepo);
    }

    @Test
    void findByVolumePartitionFilteredDelegatesAndInfers() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        Title t = title("ABP-001", "ABP-00001", "ABP", 7L, null);
        when(titleRepo.findByVolumeAndPartition("vol-a", "pool", 24, 0)).thenReturn(List.of(t));
        when(actressRepo.findById(7L)).thenReturn(Optional.empty());
        when(coverPath.find(any())).thenReturn(Optional.empty());

        service.findByVolumePartitionFiltered("vol-a", "pool", null, List.of(), 0, 24);

        verify(titleRepo).findByVolumeAndPartition("vol-a", "pool", 24, 0);
    }

    // ── listLabels / listAllCompanies / listVolumes ────────────────────────

    @Test
    void listLabelsSortsByCode() {
        Label a = new Label("ZZZ", "Zeta Co", "Zeta", null, null, null, null, null, null, List.of());
        Label b = new Label("AAA", "Alpha Co", "Alpha", null, null, null, null, null, null, List.of());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("ZZZ", a, "AAA", b));

        assertEquals(List.of("AAA", "ZZZ"),
                service.listLabels().stream().map(Label::code).toList());
    }

    @Test
    void listAllCompaniesReturnsDistinctSortedNonNull() {
        Label a = new Label("AAA", "A-label", "Zeta", null, null, null, null, null, null, List.of());
        Label b = new Label("BBB", "B-label", "Alpha", null, null, null, null, null, null, List.of());
        Label c = new Label("CCC", "C-label", "Alpha", null, null, null, null, null, null, List.of()); // dup
        Label d = new Label("DDD", "D-label", null, null, null, null, null, null, null, List.of());   // null
        when(labelRepo.findAllAsMap()).thenReturn(Map.of("AAA", a, "BBB", b, "CCC", c, "DDD", d));

        assertEquals(List.of("Alpha", "Zeta"), service.listAllCompanies());
    }

    @Test
    void listVolumesSortsBySmbPath() {
        Map<String, String> volumes = new java.util.LinkedHashMap<>();
        volumes.put("b", "//pandora/jav_B");
        volumes.put("a", "//pandora/jav_A");
        TitleBrowseService svc = new TitleBrowseService(titleRepo, actressRepo, coverPath,
                labelRepo, titleActressRepo, watchHistoryRepo, volumes);

        var result = svc.listVolumes();

        assertEquals(2, result.size());
        assertEquals("//pandora/jav_A", result.get(0).smbPath());
        assertEquals("a", result.get(0).id());
    }

    // ── Tag queries and autocomplete ───────────────────────────────────────

    @Test
    void findTagsForCollectionsDelegates() {
        when(titleRepo.findTagsByVolume("collections")).thenReturn(List.of("cream"));
        assertEquals(List.of("cream"), service.findTagsForCollections());
    }

    @Test
    void findTagsForPoolDelegates() {
        when(titleRepo.findTagsByVolumeAndPartition("vol-a", "pool")).thenReturn(List.of("vr"));
        assertEquals(List.of("vr"), service.findTagsForPool("vol-a"));
    }

    @Test
    void labelAutocompleteDelegates() {
        when(titleRepo.findLabelCodesWithPrefix("AB")).thenReturn(List.of("ABP", "ABS"));
        assertEquals(List.of("ABP", "ABS"), service.labelAutocomplete("AB"));
    }

    // ── topActressesByLabels / newestActressesByLabels ─────────────────────

    @Test
    void topActressesByLabelsMapsRowArrays() {
        List<Object[]> rows = java.util.Collections.singletonList(new Object[]{99L, "Yua", "GODDESS", 47L});
        when(titleRepo.findTopActressesByLabels(List.of("ABP"), 10)).thenReturn(rows);

        var out = service.topActressesByLabels(List.of("ABP"), 10);

        assertEquals(1, out.size());
        assertEquals(99L, out.get(0).id());
        assertEquals("Yua", out.get(0).name());
        assertEquals("GODDESS", out.get(0).tier());
        assertEquals(47L, out.get(0).count());
    }

    @Test
    void newestActressesByLabelsMapsRowArraysWithZeroCount() {
        List<Object[]> rows = java.util.Collections.singletonList(new Object[]{1L, "Aya", "POPULAR"});
        when(titleRepo.findNewestActressesByLabels(List.of("SSIS"), 5)).thenReturn(rows);

        var out = service.newestActressesByLabels(List.of("SSIS"), 5);
        assertEquals(1, out.size());
        assertEquals(0L, out.get(0).count());
    }

    // ── findDuplicatesPaged ────────────────────────────────────────────────

    @Test
    void findDuplicatesPagedBuildsDuplicatePage() {
        Title t = title("ABP-123", "ABP-00123", "ABP", null, null);
        when(titleRepo.findWithMultipleLocationsPaged(50, 0, null)).thenReturn(List.of(t));
        when(titleRepo.countWithMultipleLocations(null)).thenReturn(7);
        when(coverPath.find(any())).thenReturn(Optional.empty());

        var page = service.findDuplicatesPaged(0, 50, null);

        assertEquals(1, page.titles().size());
        assertEquals(7, page.total());
    }

    // ── findLastVisited / findMostVisited ──────────────────────────────────

    @Test
    void findLastVisitedDelegates() {
        when(titleRepo.findLastVisited(5)).thenReturn(List.of());
        service.findLastVisited(5);
        verify(titleRepo).findLastVisited(5);
    }

    @Test
    void findMostVisitedDelegates() {
        when(titleRepo.findMostVisited(5)).thenReturn(List.of());
        service.findMostVisited(5);
        verify(titleRepo).findMostVisited(5);
    }

    // ── recordVisit ────────────────────────────────────────────────────────

    @Test
    void recordVisitIncrementsAndReturnsStats() {
        Title before = Title.builder().id(1L).code("ABP-123").build();
        Title after = Title.builder().id(1L).code("ABP-123").visitCount(3)
                .lastVisitedAt(java.time.LocalDateTime.of(2026, 4, 20, 10, 0)).build();
        when(titleRepo.findByCode("ABP-123")).thenReturn(Optional.of(before));
        when(titleRepo.findById(1L)).thenReturn(Optional.of(after));

        var stats = service.recordVisit("ABP-123").orElseThrow();

        verify(titleRepo).recordVisit(1L);
        assertEquals(3, stats.visitCount());
        assertTrue(stats.lastVisitedAt().startsWith("2026-04-20"));
    }

    @Test
    void recordVisitReturnsEmptyForMissingTitle() {
        when(titleRepo.findByCode("NOPE-001")).thenReturn(Optional.empty());
        assertTrue(service.recordVisit("NOPE-001").isEmpty());
        verify(titleRepo, never()).recordVisit(anyLong());
    }

    // ── getSpotlight ───────────────────────────────────────────────────────

    @Test
    void getSpotlightReturnsNullWhenPoolEmpty() {
        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of());

        assertNull(service.getSpotlight(null));
    }

    // --- helpers ---

    private static Title title(String code, String baseCode, String label, Long actressId, LocalDate addedDate) {
        return Title.builder()
                .id(1L).code(code).baseCode(baseCode).label(label).seqNum(1)
                .actressId(actressId)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("stars")
                        .path(Path.of("/mnt/vol-a/stars/" + code))
                        .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(addedDate)
                        .build()))
                .build();
    }

    private static Title titleWithNullLabel(String code, Long actressId, LocalDate addedDate) {
        return Title.builder()
                .id(1L).code(code)
                .actressId(actressId)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("unsorted").partitionId("queue")
                        .path(Path.of("/mnt/unsorted/queue/" + code))
                        .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(addedDate)
                        .build()))
                .build();
    }
}
