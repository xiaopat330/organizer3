package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Label;
import com.organizer3.rating.RatingCurveRepository;
import com.organizer3.rating.RatingScoreCalculator;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ActressDashboardBuilder}.
 *
 * <p>The builder is constructed with mock repositories plus a real
 * {@link ActressBrowseService} backed by the same mocks, so that
 * {@code buildContext}/{@code toSummary} work without a database.
 */
@ExtendWith(MockitoExtension.class)
class ActressDashboardBuilderTest {

    @Mock ActressRepository actressRepo;
    @Mock TitleRepository titleRepo;
    @Mock CoverPath coverPath;
    @Mock LabelRepository labelRepo;
    @Mock Jdbi jdbi;
    @Mock RatingCurveRepository curveRepo;

    ActressDashboardBuilder builder;

    @BeforeEach
    void setUp() {
        ActressBrowseService service = new ActressBrowseService(
                actressRepo, titleRepo, coverPath,
                Map.of(), labelRepo, null, null, jdbi,
                curveRepo, new RatingScoreCalculator());

        // Default stubs required by buildContext so tests that don't care can omit them.
        lenient().when(titleRepo.findByActressIds(any())).thenReturn(Map.of());
        lenient().when(titleRepo.findRatingDataByTitleIds(any())).thenReturn(Map.of());
        lenient().when(curveRepo.find()).thenReturn(Optional.empty());
        lenient().when(actressRepo.findAliasesForActresses(any())).thenReturn(Map.of());
        lenient().when(actressRepo.findCanonicalNameIds(any())).thenReturn(Map.of());
        lenient().when(actressRepo.findPrimaryForAliases(any())).thenReturn(Map.of());
        lenient().when(labelRepo.findAllAsMap()).thenReturn(Map.of());
        lenient().when(coverPath.find(any())).thenReturn(Optional.empty());
        lenient().when(jdbi.withHandle(any())).thenReturn(List.of());

        builder = new ActressDashboardBuilder(actressRepo, labelRepo, service);
    }

    // ── getSpotlight ──────────────────────────────────────────────────────

    @Test
    void getSpotlightReturnsNullWhenPoolEmpty() {
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of());
        assertNull(builder.getSpotlight(null));
    }

    @Test
    void getSpotlightReturnsActressFromPool() {
        Actress a = elite(1L, "Ai Uehara");
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of(a));
        ActressSummary result = builder.getSpotlight(null);
        assertNotNull(result);
        assertEquals("Ai Uehara", result.getCanonicalName());
    }

    @Test
    void getSpotlightExcludesGivenId() {
        Actress other = elite(20L, "Other");
        when(actressRepo.findSpotlightCandidates(any(), anyInt(), eq(java.util.Set.of(99L))))
                .thenReturn(List.of(other));
        ActressSummary result = builder.getSpotlight(99L);
        assertNotNull(result);
        assertEquals("Other", result.getCanonicalName());
    }

    // ── buildDashboard — structural ───────────────────────────────────────

    @Test
    void buildDashboardReturnsAllModules() {
        Actress spot   = elite(10L, "Spotlight");
        Actress fresh  = actress(11L, "Fresh");
        Actress book   = actress(12L, "Bookmarked");
        Actress lastV  = actress(13L, "LastVisited");
        Actress undisc = elite(14L, "Undiscovered");
        Actress gem    = elite(15L, "Gem");

        when(actressRepo.findSpotlightCandidates(any(), anyInt(), any())).thenReturn(List.of(spot));
        when(actressRepo.findBirthdaysToday(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(actressRepo.findNewFaces(any(), anyInt(), any())).thenReturn(List.of(fresh));
        when(actressRepo.findBookmarksOrderedByBookmarkedAt(anyInt(), any())).thenReturn(List.of(book));
        when(actressRepo.findLastVisited(anyInt())).thenReturn(List.of(lastV));
        when(actressRepo.findUndiscoveredElites(any(), anyInt(), anyInt(), any())).thenReturn(List.of(undisc));
        when(actressRepo.findForgottenGemsCandidates(any(), any(), any(), anyInt(), any())).thenReturn(List.of(gem));
        when(actressRepo.findResearchGapCandidates(any(), anyInt())).thenReturn(List.of());
        when(actressRepo.findActressLabelEngagements()).thenReturn(List.of());
        when(actressRepo.computeActressLibraryStats()).thenReturn(stats(50, 5));

        var d = builder.buildDashboard();

        assertNotNull(d.spotlight());
        assertEquals("Spotlight", d.spotlight().getCanonicalName());
        assertEquals(1, d.newFaces().size());
        assertEquals(1, d.bookmarks().size());
        assertEquals(1, d.recentlyViewed().size());
        assertEquals(1, d.undiscoveredElites().size());
        assertEquals(1, d.forgottenGems().size());
        assertEquals(50L, d.libraryStats().totalActresses());
        assertEquals(5L, d.libraryStats().favorites());
    }

    @Test
    void buildDashboardNullSpotlightWhenPoolEmpty() {
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
        when(actressRepo.computeActressLibraryStats()).thenReturn(stats(0, 0));

        var d = builder.buildDashboard();

        assertNull(d.spotlight());
        assertTrue(d.birthdaysToday().isEmpty());
    }

    @Test
    void buildDashboardFallsBackWhenNewFacesWindowEmpty() {
        Actress fallback = actress(20L, "Fallback");
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
        when(actressRepo.computeActressLibraryStats()).thenReturn(stats(0, 0));

        var d = builder.buildDashboard();

        assertEquals(1, d.newFaces().size());
        assertEquals("Fallback", d.newFaces().get(0).getCanonicalName());
    }

    // ── Completeness flag helpers ─────────────────────────────────────────

    @Test
    void isProfileFilledRequiresStageDobBirthplace() {
        var full = summaryWith(b -> b.stageName("S").dateOfBirth("1990-01-01").birthplace("Tokyo"));
        var partial = summaryWith(b -> b.stageName("S"));

        assertTrue(ActressDashboardBuilder.isProfileFilled(full));
        assertFalse(ActressDashboardBuilder.isProfileFilled(partial));
    }

    @Test
    void isPhysicalFilledRequiresAllMeasurements() {
        var full = summaryWith(b -> b.heightCm(160).bust(85).waist(58).hip(86));
        var partial = summaryWith(b -> b.heightCm(160));

        assertTrue(ActressDashboardBuilder.isPhysicalFilled(full));
        assertFalse(ActressDashboardBuilder.isPhysicalFilled(partial));
    }

    @Test
    void isBiographyFilledRejectsBlank() {
        var withBio = summaryWith(b -> b.biography("a real bio"));
        var blank   = summaryWith(b -> b.biography("   "));
        var absent  = summaryWith(b -> {});

        assertTrue(ActressDashboardBuilder.isBiographyFilled(withBio));
        assertFalse(ActressDashboardBuilder.isBiographyFilled(blank));
        assertFalse(ActressDashboardBuilder.isBiographyFilled(absent));
    }

    @Test
    void isPortfolioCoveredRequiresAtLeastOneTitle() {
        var withTitles = summaryWith(b -> {});
        // Force titleCount to be >0 via a real build via the service (simpler: test the static method directly).
        // portfolioCovered uses s.getTitleCount() > 0, which is 0 by default on a stub summary.
        ActressSummary zeroTitle = ActressSummary.builder().id(1L).canonicalName("X").build();
        assertFalse(ActressDashboardBuilder.isPortfolioCovered(zeroTitle));
    }

    // ── weightedSample ────────────────────────────────────────────────────

    @Test
    void weightedSampleReturnsAllItemsWhenPoolSmallerThanN() {
        var items = List.of("a", "b", "c");
        var result = ActressDashboardBuilder.weightedSample(items, s -> 1.0, 10);
        assertEquals(3, result.size());
        assertTrue(result.containsAll(items));
    }

    @Test
    void weightedSampleReturnsNItemsWhenPoolLarger() {
        var items = List.of("a", "b", "c", "d", "e");
        var result = ActressDashboardBuilder.weightedSample(items, s -> 1.0, 3);
        assertEquals(3, result.size());
    }

    @Test
    void weightedSampleReturnsEmptyForEmptyInput() {
        var result = ActressDashboardBuilder.weightedSample(List.of(), s -> 1.0, 5);
        assertTrue(result.isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Actress actress(long id, String name) {
        return Actress.builder().id(id).canonicalName(name)
                .tier(Actress.Tier.LIBRARY).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
    }

    private static Actress elite(long id, String name) {
        return Actress.builder().id(id).canonicalName(name)
                .tier(Actress.Tier.SUPERSTAR).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
    }

    private static ActressRepository.ActressLibraryStats stats(long total, long favs) {
        return new ActressRepository.ActressLibraryStats(total, favs, 0, 0, 0, 0, 0);
    }

    /** Build a minimal ActressSummary and apply a customization lambda. */
    private static ActressSummary summaryWith(java.util.function.Consumer<ActressSummary.ActressSummaryBuilder> customizer) {
        ActressSummary.ActressSummaryBuilder b = ActressSummary.builder()
                .id(1L).canonicalName("TestActress");
        customizer.accept(b);
        return b.build();
    }
}
