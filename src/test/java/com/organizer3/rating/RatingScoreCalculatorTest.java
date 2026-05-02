package com.organizer3.rating;

import com.organizer3.model.Actress;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RatingScoreCalculatorTest {

    private RatingScoreCalculator calc;
    private RatingCurve curve;

    @BeforeEach
    void setUp() {
        calc = new RatingScoreCalculator();
        // Representative curve: boundaries sorted descending by minWeighted
        List<RatingCurve.Boundary> boundaries = List.of(
                new RatingCurve.Boundary(4.70, "SSS"),
                new RatingCurve.Boundary(4.55, "SS"),
                new RatingCurve.Boundary(4.40, "S"),
                new RatingCurve.Boundary(4.20, "A+"),
                new RatingCurve.Boundary(4.00, "A"),
                new RatingCurve.Boundary(3.80, "A-"),
                new RatingCurve.Boundary(3.60, "B+"),
                new RatingCurve.Boundary(3.40, "B"),
                new RatingCurve.Boundary(3.20, "B-"),
                new RatingCurve.Boundary(3.00, "C+"),
                new RatingCurve.Boundary(2.80, "C"),
                new RatingCurve.Boundary(2.60, "C-"),
                new RatingCurve.Boundary(2.40, "D"),
                new RatingCurve.Boundary(0.00, "F")
        );
        // globalMean=4.0, minCredibleVotes=50
        curve = new RatingCurve(4.0, 300, 50, boundaries, Instant.now());
    }

    // --- Null/zero inputs ---

    @Test
    void nullRatingAvgReturnsEmpty() {
        assertEquals(Optional.empty(), calc.gradeFor(null, 100, curve));
    }

    @Test
    void nullRatingCountReturnsEmpty() {
        assertEquals(Optional.empty(), calc.gradeFor(4.5, null, curve));
    }

    @Test
    void zeroRatingCountReturnsEmpty() {
        assertEquals(Optional.empty(), calc.gradeFor(4.5, 0, curve));
    }

    @Test
    void nullRatingAvgWeightedScoreEmpty() {
        assertEquals(Optional.empty(), calc.weightedScore(null, 100, curve));
    }

    // --- Bayesian shrinkage ---

    @Test
    void shrinkagePullsLowVoteCountTowardGlobalMean() {
        // v=1, R=5.0, m=50, C=4.0 → weighted = (1*5.0 + 50*4.0) / (1+50) = 205/51 ≈ 4.02
        double weighted = calc.weightedScore(5.0, 1, curve).orElseThrow();
        assertEquals(205.0 / 51.0, weighted, 1e-9);
    }

    @Test
    void highVoteCountUsesOwnScore() {
        // v=1000, R=4.8, m=50, C=4.0 → weighted = (1000*4.8 + 50*4.0) / 1050 = 5000/1050 ≈ 4.762
        double weighted = calc.weightedScore(4.8, 1000, curve).orElseThrow();
        assertEquals((1000.0 * 4.8 + 50.0 * 4.0) / 1050.0, weighted, 1e-9);
    }

    // --- Boundary cases ---

    @Test
    void exactlyAtCutoffLandsInHigherGrade() {
        // Weighted = 4.40 exactly → should be S (first boundary with minWeighted <= 4.40)
        // Use v large enough that weighted ≈ R: v=10000, R≈4.40, m=50, C=4.0
        // Actually easier: manipulate to get exact 4.40
        // weighted = (v*R + 50*4.0) / (v+50) = 4.40 → v*R + 200 = 4.40*(v+50) = 4.40v + 220
        // v*(R-4.40) = 20 → with R=4.60, v*(0.20)=20 → v=100
        // Check: (100*4.60 + 50*4.0) / 150 = (460+200)/150 = 660/150 = 4.40 ✓
        double weighted = calc.weightedScore(4.60, 100, curve).orElseThrow();
        assertEquals(4.40, weighted, 1e-9);
        Optional<Actress.Grade> grade = calc.gradeFor(4.60, 100, curve);
        assertTrue(grade.isPresent());
        assertEquals(Actress.Grade.S, grade.get(), "exactly at S cutoff should be S");
    }

    // --- Stable mapping for typical values ---

    @Test
    void highRatingWithManyVotesLandsInATier() {
        // 4.5 with 200 votes, globalMean=4.0, m=50 → weighted=(200*4.5+50*4.0)/250=1100/250=4.40
        // 4.40 is exactly S boundary → S
        Optional<Actress.Grade> grade = calc.gradeFor(4.5, 200, curve);
        assertTrue(grade.isPresent());
        // weighted = (200*4.5 + 50*4.0) / 250 = 4.40 → S
        assertEquals(Actress.Grade.S, grade.get());
    }

    @Test
    void lowRatingLandsInLowerTier() {
        // 3.0 with 100 votes → weighted=(100*3.0+50*4.0)/150 = (300+200)/150 = 500/150 ≈ 3.33
        // 3.33 is between B (3.40) and B- (3.20) → B-
        Optional<Actress.Grade> grade = calc.gradeFor(3.0, 100, curve);
        assertTrue(grade.isPresent());
        assertEquals(Actress.Grade.B_MINUS, grade.get());
    }

    @Test
    void veryLowRatingLandsInF() {
        // 1.0 with 200 votes → weighted=(200*1.0+50*4.0)/250 = 400/250 = 1.60 → below 2.40 → F
        Optional<Actress.Grade> grade = calc.gradeFor(1.0, 200, curve);
        assertTrue(grade.isPresent());
        assertEquals(Actress.Grade.F, grade.get());
    }

    // ── actressGradeFor ───────────────────────────────────────────────────

    private static TitleRepository.RatingData rd(double avg, int count) {
        return new TitleRepository.RatingData(avg, count);
    }

    @Test
    void tooFewRatedTitlesReturnsEmpty() {
        // 4 rated titles < MIN_ACTRESS_RATED_TITLES (5) → empty
        List<TitleRepository.RatingData> data = List.of(
                rd(4.5, 100), rd(4.3, 80), rd(4.6, 90), rd(4.4, 70));
        assertEquals(Optional.empty(), calc.actressGradeFor(data, 4, curve));
    }

    @Test
    void coverageTooLowReturnsEmpty() {
        // 5 rated out of 30 total → 16.7% < MIN_ACTRESS_COVERAGE (25%) → empty
        List<TitleRepository.RatingData> data = List.of(
                rd(4.5, 100), rd(4.3, 80), rd(4.6, 90), rd(4.4, 70), rd(4.7, 110));
        assertEquals(Optional.empty(), calc.actressGradeFor(data, 30, curve));
    }

    @Test
    void nullRatingEntriesAreSkipped() {
        // 3 valid + 2 nulls → only 3 rated → below threshold
        List<TitleRepository.RatingData> data = List.of(
                rd(4.5, 100), new TitleRepository.RatingData(null, null),
                rd(4.3, 80), new TitleRepository.RatingData(4.0, 0), rd(4.6, 90));
        assertEquals(Optional.empty(), calc.actressGradeFor(data, 5, curve));
    }

    @Test
    void highQualityActressGetsHighGrade() {
        // 10 titles, all rated 4.8 with 200 votes each
        // V=2000, R_pool=4.8, M_actress=5*50=250, C=4.0
        // weighted = (2000*4.8 + 250*4.0) / 2250 = (9600+1000)/2250 = 10600/2250 ≈ 4.711 → SSS (≥4.70)
        List<TitleRepository.RatingData> data = Collections.nCopies(10, rd(4.8, 200));
        Optional<Actress.Grade> grade = calc.actressGradeFor(data, 10, curve);
        assertTrue(grade.isPresent());
        assertEquals(Actress.Grade.SSS, grade.get());
    }

    @Test
    void averageQualityActressGetsMiddleGrade() {
        // 10 titles, all rated 4.0 with 100 votes each
        // V=1000, R_pool=4.0, M=250, C=4.0
        // weighted = (1000*4.0 + 250*4.0) / 1250 = 5000/1250 = 4.0 → A (≥4.00)
        List<TitleRepository.RatingData> data = Collections.nCopies(10, rd(4.0, 100));
        Optional<Actress.Grade> grade = calc.actressGradeFor(data, 10, curve);
        assertTrue(grade.isPresent());
        assertEquals(Actress.Grade.A, grade.get());
    }

    @Test
    void shrinkagePullsSmallPoolTowardGlobalMean() {
        // 5 titles rated 5.0 with only 10 votes each (low confidence)
        // V=50, R_pool=5.0, M=250, C=4.0
        // weighted = (50*5.0 + 250*4.0) / 300 = (250+1000)/300 = 1250/300 ≈ 4.167 → A+ (≥4.20)? no, 4.167 < 4.20 → A (≥4.00)
        List<TitleRepository.RatingData> data = Collections.nCopies(5, rd(5.0, 10));
        Optional<Actress.Grade> grade = calc.actressGradeFor(data, 5, curve);
        assertTrue(grade.isPresent());
        assertEquals(Actress.Grade.A, grade.get());
    }
}
