package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TitleBrowseService#findAdminTitlesPaged}.
 *
 * <p>Contract:
 * <ul>
 *   <li>Attention buckets: no-content (0 videos) → 1000, multi-location (>1 row) → 100, clean → 0</li>
 *   <li>Tiebreaker within bucket: release_date DESC (nulls last), then code ASC</li>
 *   <li>Pagination is 1-indexed; page &gt; totalPages is clamped to the last page</li>
 *   <li>Empty actress: page=1, totalPages=0, empty list</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminTitlesPagedTest {

    @Mock TitleRepository titleRepo;
    @Mock ActressRepository actressRepo;
    @Mock CoverPath coverPath;
    @Mock LabelRepository labelRepo;
    @Mock TitleActressRepository titleActressRepo;
    @Mock WatchHistoryRepository watchHistoryRepo;
    @Mock VideoRepository videoRepo;

    TitleBrowseService service;

    @BeforeEach
    void setUp() {
        service = new TitleBrowseService(
                titleRepo, actressRepo, coverPath, labelRepo, titleActressRepo,
                watchHistoryRepo, videoRepo, Map.of(), 500);
    }

    // ── ordering ────────────────────────────────────────────────────────────

    @Test
    void noContentSortsAboveMultiLocationSortsAboveClean() {
        // noContent: id=1, 0 videos, 1 location
        Title noContent = title(1L, "AAA-001", LocalDate.of(2024, 1, 1), singleLoc(1L));
        // multiLoc: id=2, 1+ videos, 2 locations
        Title multiLoc  = title(2L, "BBB-001", LocalDate.of(2024, 1, 1), singleLoc(2L), singleLoc2(2L));
        // clean: id=3, 1+ videos, 1 location
        Title clean     = title(3L, "CCC-001", LocalDate.of(2024, 1, 1), singleLoc(3L));

        when(titleRepo.findByActress(99L)).thenReturn(List.of(clean, multiLoc, noContent));
        when(videoRepo.countByTitleIds(any())).thenReturn(Map.of(2L, 2, 3L, 1)); // id=1 absent → 0
        mockToSummariesDeps();

        var page = service.findAdminTitlesPaged(99L, 1, 10);

        assertEquals(3, page.titles().size());
        assertEquals("AAA-001", page.titles().get(0).getCode()); // bucket 1000
        assertEquals("BBB-001", page.titles().get(1).getCode()); // bucket 100
        assertEquals("CCC-001", page.titles().get(2).getCode()); // bucket 0
    }

    @Test
    void twoCleanTitlesSortByReleaseDateDesc() {
        // Both clean, date differs
        Title older = title(1L, "AAA-001", LocalDate.of(2022, 6, 1), singleLoc(1L));
        Title newer = title(2L, "BBB-001", LocalDate.of(2023, 6, 1), singleLoc(2L));

        when(titleRepo.findByActress(99L)).thenReturn(List.of(older, newer));
        when(videoRepo.countByTitleIds(any())).thenReturn(Map.of(1L, 1, 2L, 1));
        mockToSummariesDeps();

        var page = service.findAdminTitlesPaged(99L, 1, 10);

        assertEquals("BBB-001", page.titles().get(0).getCode()); // newer first
        assertEquals("AAA-001", page.titles().get(1).getCode());
    }

    @Test
    void nullReleaseDateSortsLast() {
        Title withDate    = title(1L, "AAA-001", LocalDate.of(2024, 1, 1), singleLoc(1L));
        Title nullDate    = title(2L, "BBB-001", null, singleLoc(2L));

        when(titleRepo.findByActress(99L)).thenReturn(List.of(nullDate, withDate));
        when(videoRepo.countByTitleIds(any())).thenReturn(Map.of(1L, 1, 2L, 1));
        mockToSummariesDeps();

        var page = service.findAdminTitlesPaged(99L, 1, 10);

        assertEquals("AAA-001", page.titles().get(0).getCode()); // has date → first
        assertEquals("BBB-001", page.titles().get(1).getCode()); // null date → last
    }

    // ── pagination ──────────────────────────────────────────────────────────

    @Test
    void paginationPage1ReturnsFirstSlice() {
        List<Title> five = fiveTitles();
        when(titleRepo.findByActress(99L)).thenReturn(five);
        when(videoRepo.countByTitleIds(any())).thenReturn(Map.of(1L,1, 2L,1, 3L,1, 4L,1, 5L,1));
        mockToSummariesDeps();

        var page = service.findAdminTitlesPaged(99L, 1, 2);

        assertEquals(1, page.page());
        assertEquals(3, page.totalPages()); // ceil(5/2)
        assertEquals(2, page.pageSize());
        assertEquals(2, page.titles().size());
    }

    @Test
    void paginationPage2ReturnsSecondSlice() {
        List<Title> five = fiveTitles();
        when(titleRepo.findByActress(99L)).thenReturn(five);
        when(videoRepo.countByTitleIds(any())).thenReturn(Map.of(1L,1, 2L,1, 3L,1, 4L,1, 5L,1));
        mockToSummariesDeps();

        var page = service.findAdminTitlesPaged(99L, 2, 2);

        assertEquals(2, page.page());
        assertEquals(2, page.titles().size());
    }

    @Test
    void lastPageClampWhenPageExceedsTotal() {
        List<Title> five = fiveTitles();
        when(titleRepo.findByActress(99L)).thenReturn(five);
        when(videoRepo.countByTitleIds(any())).thenReturn(Map.of(1L,1, 2L,1, 3L,1, 4L,1, 5L,1));
        mockToSummariesDeps();

        var page = service.findAdminTitlesPaged(99L, 99, 2);

        // last page is 3 (ceil(5/2)); last slice has 1 title
        assertEquals(3, page.page());
        assertEquals(3, page.totalPages());
        assertEquals(1, page.titles().size());
    }

    // ── empty actress ────────────────────────────────────────────────────────

    @Test
    void emptyActressReturnsPage1TotalPages0EmptyList() {
        when(titleRepo.findByActress(99L)).thenReturn(List.of());

        var page = service.findAdminTitlesPaged(99L, 1, 5);

        assertEquals(1, page.page());
        assertEquals(0, page.totalPages());
        assertEquals(5, page.pageSize());
        assertTrue(page.titles().isEmpty());
        verifyNoInteractions(videoRepo);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Stubs the shared toSummaries() dependencies with empty/null responses so that
     * only the ordering/pagination logic is actually exercised.
     */
    private void mockToSummariesDeps() {
        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        when(coverPath.find(any())).thenReturn(java.util.Optional.empty());
        when(watchHistoryRepo.findWatchStatsBatch(any())).thenReturn(Map.of());
        when(titleRepo.findEnrichmentTagsByTitleIds(any())).thenReturn(Map.of());
        when(titleRepo.findRatingDataByTitleIds(any())).thenReturn(Map.of());
        when(titleRepo.findTitleOriginalEnByTitleIds(any())).thenReturn(Map.of());
        when(titleActressRepo.findCreditsByTitle(anyLong())).thenReturn(List.of());
    }

    private static Title title(long id, String code, LocalDate releaseDate, TitleLocation... locs) {
        return Title.builder()
                .id(id).code(code).baseCode(code.replace("-", "-00")).label(code.split("-")[0])
                .releaseDate(releaseDate)
                .locations(List.of(locs))
                .build();
    }

    private static TitleLocation singleLoc(long titleId) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId("vol-a").partitionId("stars")
                .path(Path.of("/vol-a/stars/title-" + titleId))
                .lastSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static TitleLocation singleLoc2(long titleId) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId("vol-b").partitionId("stars")
                .path(Path.of("/vol-b/stars/title-" + titleId))
                .lastSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    /** Five clean titles with distinct release dates (newest first after sort). */
    private static List<Title> fiveTitles() {
        return List.of(
                title(1L, "AAA-001", LocalDate.of(2024, 5, 1), singleLoc(1L)),
                title(2L, "AAA-002", LocalDate.of(2024, 4, 1), singleLoc(2L)),
                title(3L, "AAA-003", LocalDate.of(2024, 3, 1), singleLoc(3L)),
                title(4L, "AAA-004", LocalDate.of(2024, 2, 1), singleLoc(4L)),
                title(5L, "AAA-005", LocalDate.of(2024, 1, 1), singleLoc(5L))
        );
    }
}
