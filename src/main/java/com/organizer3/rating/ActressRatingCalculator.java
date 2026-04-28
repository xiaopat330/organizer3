package com.organizer3.rating;

import com.organizer3.model.Actress;

import java.util.List;
import java.util.Optional;

/**
 * Pure stateless service for actress-level rating aggregation.
 *
 * <p>Aggregation: for an actress's enriched-and-rated titles, the actress's raw
 * vote-weighted-mean is {@code Σ(rating_count_i × rating_avg_i) / Σ(rating_count_i)}.
 * Bayesian shrinkage then pulls this toward the population mean C with a credibility
 * threshold m: {@code shrunk = (Σvotes×R + m×C) / (Σvotes + m)}.
 *
 * <p>Below the hard floor on enriched-title count ({@code minTitles}), the calculator
 * returns empty — actresses with too few rated titles do not get a computed grade
 * regardless of how their few titles scored.
 */
public class ActressRatingCalculator {

    /**
     * One title's rating data as input to the actress aggregator. Both fields must be
     * non-null and {@code ratingCount > 0} for the row to contribute.
     */
    public record TitleRatingRow(double ratingAvg, int ratingCount) {}

    /**
     * Compute the Bayesian-shrunken actress score from her rated titles.
     *
     * @return empty if {@code rows.size() < minTitles} or all rows have zero votes
     */
    public Optional<Double> shrunkenScore(List<TitleRatingRow> rows, ActressRatingCurve curve, int minTitles) {
        if (rows == null || rows.size() < minTitles) return Optional.empty();

        long totalVotes = 0;
        double weightedSum = 0;
        for (TitleRatingRow r : rows) {
            if (r.ratingCount() <= 0) continue;
            totalVotes += r.ratingCount();
            weightedSum += r.ratingCount() * r.ratingAvg();
        }
        if (totalVotes == 0) return Optional.empty();

        double m = curve.minCredibleVotes();
        double shrunk = (weightedSum + m * curve.globalMean()) / (totalVotes + m);
        return Optional.of(shrunk);
    }

    /**
     * Map an actress's shrunken score to a grade using the given curve. Returns empty if
     * the score is empty or the curve has no boundaries that match.
     */
    public Optional<Actress.Grade> gradeFor(List<TitleRatingRow> rows, ActressRatingCurve curve, int minTitles) {
        Optional<Double> maybeScore = shrunkenScore(rows, curve, minTitles);
        if (maybeScore.isEmpty()) return Optional.empty();
        double score = maybeScore.get();

        List<ActressRatingCurve.Boundary> boundaries = curve.boundaries();
        for (ActressRatingCurve.Boundary b : boundaries) {
            if (score >= b.minWeighted()) {
                return Optional.of(Actress.Grade.fromDisplay(b.grade()));
            }
        }
        return Optional.empty();
    }
}
