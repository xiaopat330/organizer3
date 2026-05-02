package com.organizer3.rating;

import com.organizer3.model.Actress;
import com.organizer3.repository.TitleRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Pure stateless service for computing Bayesian-shrunken scores and mapping them to grades.
 *
 * <p>Bayesian shrinkage formula: {@code weighted = (v * R + m * C) / (v + m)}
 * where R = rating_avg, v = rating_count, C = global_mean, m = min_credible_votes.
 */
public class RatingScoreCalculator {

    /** Minimum number of rated titles required before assigning an actress grade. */
    public static final int MIN_ACTRESS_RATED_TITLES = 5;
    /** Minimum fraction of an actress's titles that must be rated. */
    public static final double MIN_ACTRESS_COVERAGE  = 0.25;
    /**
     * Actress-level min-credible-votes multiplier over the title-level value.
     * Higher floor prevents a single breakout title from dominating the pool.
     */
    public static final double ACTRESS_M_MULTIPLIER  = 5.0;

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

    /**
     * Derives a Bayesian-shrunken actress grade by pooling the raw rating data
     * of all her rated titles.
     *
     * <p>Eligibility requires at least {@link #MIN_ACTRESS_RATED_TITLES} rated titles
     * AND at least {@link #MIN_ACTRESS_COVERAGE} of total titles rated.
     * The actress-level shrinkage floor is {@link #ACTRESS_M_MULTIPLIER}× the title-level
     * {@code minCredibleVotes} so that a single high-vote title cannot dominate.
     *
     * @param titleRatings raw rating data for all of this actress's titles
     * @param totalTitles  total title count (for coverage check)
     * @param curve        the rating curve to grade against
     * @return empty if eligibility is not met or the curve has no boundaries
     */
    public Optional<Actress.Grade> actressGradeFor(
            Collection<TitleRepository.RatingData> titleRatings,
            int totalTitles,
            RatingCurve curve) {

        List<TitleRepository.RatingData> rated = titleRatings.stream()
                .filter(r -> r.ratingAvg() != null && r.ratingCount() != null && r.ratingCount() > 0)
                .toList();

        if (rated.size() < MIN_ACTRESS_RATED_TITLES) return Optional.empty();
        if (totalTitles > 0 && (double) rated.size() / totalTitles < MIN_ACTRESS_COVERAGE) {
            return Optional.empty();
        }

        long vTotal = rated.stream().mapToLong(TitleRepository.RatingData::ratingCount).sum();
        if (vTotal == 0) return Optional.empty();

        double rPool = rated.stream()
                .mapToDouble(r -> r.ratingAvg() * r.ratingCount())
                .sum() / vTotal;

        double mActress = curve.minCredibleVotes() * ACTRESS_M_MULTIPLIER;
        double weighted = (vTotal * rPool + mActress * curve.globalMean()) / (vTotal + mActress);

        for (RatingCurve.Boundary boundary : curve.boundaries()) {
            if (weighted >= boundary.minWeighted()) {
                return Optional.of(Actress.Grade.fromDisplay(boundary.grade()));
            }
        }
        return Optional.empty();
    }
}
