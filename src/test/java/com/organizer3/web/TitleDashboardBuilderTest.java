package com.organizer3.web;

import com.organizer3.covers.CoverPath;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TitleDashboardBuilder}.
 *
 * <p>The builder is constructed alongside a real {@link TitleBrowseService} backed by
 * mock repositories, so that {@code toSummaries()} works without a database.
 */
@ExtendWith(MockitoExtension.class)
class TitleDashboardBuilderTest {

    @Mock TitleRepository titleRepo;
    @Mock ActressRepository actressRepo;
    @Mock CoverPath coverPath;
    @Mock LabelRepository labelRepo;
    @Mock TitleActressRepository titleActressRepo;
    @Mock WatchHistoryRepository watchHistoryRepo;

    TitleDashboardBuilder builder;

    @BeforeEach
    void setUp() {
        TitleBrowseService service = new TitleBrowseService(
                titleRepo, actressRepo, coverPath, labelRepo,
                titleActressRepo, watchHistoryRepo, Map.of(), 500);

        // Default stubs needed by toSummaries().
        lenient().when(actressRepo.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(coverPath.find(any())).thenReturn(Optional.empty());
        lenient().when(watchHistoryRepo.findWatchStatsBatch(any())).thenReturn(Map.of());
        lenient().when(titleRepo.findEnrichmentTagsByTitleIds(any())).thenReturn(Map.of());
        lenient().when(titleRepo.findRatingDataByTitleIds(any())).thenReturn(Map.of());
        lenient().when(labelRepo.findAllAsMap()).thenReturn(Map.of());

        builder = new TitleDashboardBuilder(titleRepo, actressRepo, labelRepo, service);
    }

    // ── getSpotlight ──────────────────────────────────────────────────────

    @Test
    void getSpotlightReturnsNullWhenPoolEmpty() {
        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of());

        assertNull(builder.getSpotlight(null));
    }

    @Test
    void getSpotlightReturnsTitle() {
        Title t = title("ABP-001");
        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(t));

        TitleSummary result = builder.getSpotlight(null);
        assertNotNull(result);
        assertEquals("ABP-001", result.getCode());
    }

    @Test
    void getSpotlightExcludesGivenCode() {
        Title other = title("SSIS-001");
        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), eq(java.util.Set.of("ABP-123"))))
                .thenReturn(List.of(other));

        TitleSummary result = builder.getSpotlight("abp-123");  // lower-case → normalised to upper
        assertNotNull(result);
        assertEquals("SSIS-001", result.getCode());
    }

    // ── buildDashboard — structural ───────────────────────────────────────

    @Test
    void buildDashboardReturnsAllModules() {
        Title bookmark = title("ABP-001");
        Title added    = title("ABP-002");
        Title recent   = title("ABP-003");
        Title attic    = title("ABP-004");

        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findBookmarksPaged(anyInt(), anyInt())).thenReturn(List.of(bookmark));
        when(titleRepo.findAddedSince(any(), anyInt(), any())).thenReturn(List.of(added));
        when(titleRepo.findLastVisited(anyInt())).thenReturn(List.of(recent));
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findForgottenAtticCandidates(anyInt(), any())).thenReturn(List.of(attic));
        when(titleRepo.findForgottenFavoritesCandidates(anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findAnniversary(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(titleRepo.computeLibraryStats()).thenReturn(
                new TitleRepository.LibraryStats(100L, 20L, 50L, 5L, 40L));

        var d = builder.buildDashboard();

        assertNotNull(d);
        assertEquals(1, d.onDeck().size());
        assertEquals("ABP-001", d.onDeck().get(0).getCode());
        assertEquals(1, d.recentlyViewed().size());
        assertNull(d.spotlight());
        assertEquals(1, d.forgottenAttic().size());
        assertEquals(100L, d.libraryStats().totalTitles());
        assertEquals(20L, d.libraryStats().totalLabels());
    }

    @Test
    void buildDashboardNullSpotlightWhenPoolEmpty() {
        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findBookmarksPaged(anyInt(), anyInt())).thenReturn(List.of());
        when(titleRepo.findAddedSince(any(), anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findForgottenAtticCandidates(anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findForgottenFavoritesCandidates(anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findAnniversary(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(titleRepo.computeLibraryStats()).thenReturn(
                new TitleRepository.LibraryStats(0L, 0L, 0L, 0L, 0L));

        var d = builder.buildDashboard();

        assertNull(d.spotlight());
        assertTrue(d.onDeck().isEmpty());
    }

    @Test
    void buildDashboardFallsBackToWideWindowWhenJustAddedEmpty() {
        Title fallback = title("OLD-001");
        when(titleRepo.computeLabelScores(anyInt())).thenReturn(List.of());
        when(actressRepo.findFavorites()).thenReturn(List.of());
        when(titleRepo.findBookmarksPaged(anyInt(), anyInt())).thenReturn(List.of());
        // First call (30-day window) returns empty; second call (5-year window) returns 1.
        when(titleRepo.findAddedSince(any(), anyInt(), any()))
                .thenReturn(List.of())           // 30-day window
                .thenReturn(List.of(fallback));  // 5-year fallback
        when(titleRepo.findLastVisited(anyInt())).thenReturn(List.of());
        when(titleRepo.findSpotlightCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findForgottenAtticCandidates(anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findForgottenFavoritesCandidates(anyInt(), any())).thenReturn(List.of());
        when(titleRepo.findAnniversary(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(titleRepo.computeLibraryStats()).thenReturn(
                new TitleRepository.LibraryStats(0L, 0L, 0L, 0L, 0L));

        var d = builder.buildDashboard();

        assertEquals(1, d.justAdded().size());
        assertEquals("OLD-001", d.justAdded().get(0).getCode());
    }

    // ── applySoftUnseen ───────────────────────────────────────────────────

    @Test
    void applySoftUnseenPrioritisesUnseenTitles() {
        Title seen   = titleWithVisits("SEEN-001", 5);
        Title unseen = titleWithVisits("UNSEEN-001", 0);
        List<Title> ordered = List.of(seen, unseen);

        var result = TitleDashboardBuilder.applySoftUnseen(ordered, 2, 1);

        assertEquals(2, result.size());
        // Unseen should appear first.
        assertEquals("UNSEEN-001", result.get(0).getCode());
    }

    @Test
    void applySoftUnseenReturnsEmptyForEmptyInput() {
        assertTrue(TitleDashboardBuilder.applySoftUnseen(List.of(), 5, 3).isEmpty());
    }

    @Test
    void applySoftUnseenCapsResultAtN() {
        List<Title> pool = List.of(title("A"), title("B"), title("C"), title("D"), title("E"));
        var result = TitleDashboardBuilder.applySoftUnseen(pool, 3, 1);
        assertEquals(3, result.size());
    }

    // ── weightedSample ────────────────────────────────────────────────────

    @Test
    void weightedSampleReturnsNItemsFromLargerPool() {
        var items = List.of("a", "b", "c", "d", "e");
        var result = TitleDashboardBuilder.weightedSample(items, s -> 1.0, 3);
        assertEquals(3, result.size());
    }

    @Test
    void weightedSampleReturnsAllWhenPoolSmallerThanN() {
        var items = List.of("x", "y");
        var result = TitleDashboardBuilder.weightedSample(items, s -> 1.0, 5);
        assertEquals(2, result.size());
    }

    @Test
    void weightedSampleReturnsEmptyForEmptyInput() {
        var result = TitleDashboardBuilder.weightedSample(List.of(), s -> 1.0, 3);
        assertTrue(result.isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Title title(String code) {
        return Title.builder()
                .id((long) code.hashCode()).code(code).baseCode(code).label("ABP").seqNum(1)
                .visitCount(0)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("stars")
                        .path(Path.of("/mnt/vol-a/stars/" + code))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .addedDate(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
    }

    private static Title titleWithVisits(String code, int visitCount) {
        return Title.builder()
                .id((long) code.hashCode()).code(code).baseCode(code).label("ABP").seqNum(1)
                .visitCount(visitCount)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("vol-a").partitionId("stars")
                        .path(Path.of("/mnt/vol-a/stars/" + code))
                        .lastSeenAt(LocalDate.of(2024, 1, 1))
                        .addedDate(LocalDate.of(2024, 1, 1))
                        .build()))
                .build();
    }
}
