package com.organizer3.rating;

import com.organizer3.model.Actress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ActressRatingCalculatorTest {

    private ActressRatingCalculator calc;
    private ActressRatingCurve curve;
    private static final int MIN_TITLES = 5;

    @BeforeEach
    void setUp() {
        calc = new ActressRatingCalculator();
        // Curve with C=4.30, m=200 (representative of the actress population).
        List<ActressRatingCurve.Boundary> boundaries = List.of(
                new ActressRatingCurve.Boundary(4.55, "SSS"),
                new ActressRatingCurve.Boundary(4.50, "SS"),
                new ActressRatingCurve.Boundary(4.45, "S"),
                new ActressRatingCurve.Boundary(4.40, "A+"),
                new ActressRatingCurve.Boundary(4.35, "A"),
                new ActressRatingCurve.Boundary(4.30, "A-"),
                new ActressRatingCurve.Boundary(4.25, "B+"),
                new ActressRatingCurve.Boundary(4.20, "B"),
                new ActressRatingCurve.Boundary(4.15, "B-"),
                new ActressRatingCurve.Boundary(4.10, "C+"),
                new ActressRatingCurve.Boundary(4.05, "C"),
                new ActressRatingCurve.Boundary(4.00, "C-"),
                new ActressRatingCurve.Boundary(3.95, "D"),
                new ActressRatingCurve.Boundary(0.00, "F")
        );
        curve = new ActressRatingCurve(4.30, 61, 200, boundaries, Instant.now());
    }

    private List<ActressRatingCalculator.TitleRatingRow> rows(double avg, int count, int n) {
        List<ActressRatingCalculator.TitleRatingRow> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ActressRatingCalculator.TitleRatingRow(avg, count));
        return out;
    }

    @Test
    void belowMinTitlesFloorReturnsEmpty() {
        // 4 titles — under floor of 5
        var rows = rows(4.6, 1000, 4);
        assertEquals(Optional.empty(), calc.shrunkenScore(rows, curve, MIN_TITLES));
        assertEquals(Optional.empty(), calc.gradeFor(rows, curve, MIN_TITLES));
    }

    @Test
    void atFloorWithUnanimouslyHighRatingsLandsAtTop() {
        // 5 titles all 4.7 with 5000 votes each — total votes 25000, weighted = 4.7
        // shrunken = (25000*4.7 + 200*4.30) / 25200 = 117,500 + 860 = 118,360 / 25,200 ≈ 4.697
        var rows = rows(4.7, 5000, 5);
        double s = calc.shrunkenScore(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals((25000.0 * 4.7 + 200.0 * 4.30) / 25200.0, s, 1e-9);
        Actress.Grade grade = calc.gradeFor(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals(Actress.Grade.SSS, grade, "high-vote, high-avg should land SSS");
    }

    @Test
    void shrinkageDampensSmallSampleHighScore() {
        // 5 titles each 4.9 but only 20 votes each — total 100 votes, m=200 dominates
        // shrunken = (100*4.9 + 200*4.30) / 300 = (490 + 860) / 300 = 1350/300 = 4.50 → SS
        var rows = rows(4.9, 20, 5);
        double s = calc.shrunkenScore(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals(4.50, s, 1e-9);
        Actress.Grade grade = calc.gradeFor(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals(Actress.Grade.SS, grade, "small-sample high-avg shrinks to SS, not SSS");
    }

    @Test
    void allZeroVotesReturnsEmpty() {
        // Even with N rows, if all rating_count = 0 we have no signal.
        var rows = rows(4.5, 0, 5);
        assertEquals(Optional.empty(), calc.shrunkenScore(rows, curve, MIN_TITLES));
    }

    @Test
    void mixedTitlesAggregateByVoteCount() {
        // One blockbuster (R=4.8, v=10000) + four mediocre (R=3.8, v=100 each)
        // weighted_sum = 10000*4.8 + 4*100*3.8 = 48000 + 1520 = 49520
        // total votes = 10400
        // shrunken = (49520 + 200*4.30) / 10600 = (49520 + 860) / 10600 = 50380/10600 ≈ 4.7528
        var rows = new java.util.ArrayList<ActressRatingCalculator.TitleRatingRow>();
        rows.add(new ActressRatingCalculator.TitleRatingRow(4.8, 10000));
        for (int i = 0; i < 4; i++) rows.add(new ActressRatingCalculator.TitleRatingRow(3.8, 100));
        double s = calc.shrunkenScore(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals((49520.0 + 200.0 * 4.30) / 10600.0, s, 1e-9);
        Actress.Grade grade = calc.gradeFor(rows, curve, MIN_TITLES).orElseThrow();
        // 4.7528 is well above the SSS cutoff (4.55) → SSS
        assertEquals(Actress.Grade.SSS, grade);
    }

    @Test
    void exactlyAtCutoffLandsInHigherGrade() {
        // Construct rows so shrunken = 4.45 exactly (S cutoff).
        // weighted = (V*R + 200*4.30) / (V+200) = 4.45
        // Pick V=200 votes total: V*R + 860 = 4.45*(V+200) = 4.45V + 890
        //   V*(R-4.45) = 30 → with V=200, R-4.45=0.15 → R = 4.60
        // Spread across 5 titles each 40 votes, 4.60 avg.
        var rows = rows(4.60, 40, 5);
        double s = calc.shrunkenScore(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals(4.45, s, 1e-9);
        Actress.Grade grade = calc.gradeFor(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals(Actress.Grade.S, grade);
    }

    @Test
    void verLowAvgLandsInF() {
        // 5 titles, R=1.0, V=10000 each → weighted ≈ 1.0 → far below D cutoff (3.95) → F
        var rows = rows(1.0, 10000, 5);
        Actress.Grade grade = calc.gradeFor(rows, curve, MIN_TITLES).orElseThrow();
        assertEquals(Actress.Grade.F, grade);
    }
}
