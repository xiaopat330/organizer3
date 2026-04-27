package com.organizer3.rating;

import com.organizer3.model.Actress;

import java.util.List;
import java.util.Optional;

/**
 * Pure stateless service for computing Bayesian-shrunken scores and mapping them to grades.
 *
 * <p>Bayesian shrinkage formula: {@code weighted = (v * R + m * C) / (v + m)}
 * where R = rating_avg, v = rating_count, C = global_mean, m = min_credible_votes.
 */
public class RatingScoreCalculator {

    /**
     * Returns the Bayesian-shrunken weighted score for a title.
     *
     * @return empty if ratingAvg is null, ratingCount is null, or ratingCount is zero
     */
    public Optional<Double> weightedScore(Double ratingAvg, Integer ratingCount, RatingCurve curve) {
        if (ratingAvg == null || ratingCount == null || ratingCount == 0) return Optional.empty();
        double v = ratingCount;
        double m = curve.minCredibleVotes();
        double weighted = (v * ratingAvg + m * curve.globalMean()) / (v + m);
        return Optional.of(weighted);
    }

    /**
     * Maps a title's rating data to a grade using the given curve.
     *
     * <p>Boundary lookup: first boundary whose {@code minWeighted <= weighted} (boundaries
     * must be sorted descending by minWeighted).
     *
     * @return empty if ratingAvg or ratingCount is null/zero, or if the curve has no boundaries
     */
    public Optional<Actress.Grade> gradeFor(Double ratingAvg, Integer ratingCount, RatingCurve curve) {
        Optional<Double> maybeWeighted = weightedScore(ratingAvg, ratingCount, curve);
        if (maybeWeighted.isEmpty()) return Optional.empty();
        double weighted = maybeWeighted.get();

        List<RatingCurve.Boundary> boundaries = curve.boundaries();
        for (RatingCurve.Boundary boundary : boundaries) {
            if (weighted >= boundary.minWeighted()) {
                return Optional.of(Actress.Grade.fromDisplay(boundary.grade()));
            }
        }
        return Optional.empty();
    }
}
