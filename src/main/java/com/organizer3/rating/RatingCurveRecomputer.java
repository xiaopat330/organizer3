package com.organizer3.rating;

import com.organizer3.model.Actress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates a full recompute of the rating curve and re-stamps all enriched titles.
 *
 * <p>Reads all (title_id, rating_avg, rating_count) from title_javdb_enrichment, computes
 * Bayesian-shrunken scores, derives percentile cutoffs, persists the new RatingCurve, and
 * re-stamps every enriched title's (grade, grade_source) where grade_source != 'manual'.
 *
 * <p>Skips recompute when fewer than 2 enriched titles have rating data — the percentile
 * math is degenerate with fewer samples.
 */
@Slf4j
@RequiredArgsConstructor
public class RatingCurveRecomputer {

    private static final int DEFAULT_MIN_CREDIBLE_VOTES = 50;
    private static final int MIN_POPULATION_SIZE = 2;

    // Percentile thresholds (ascending) mapped to grades (descending quality).
    // The lookup is: if weightedScore >= boundary.minWeighted, that's the grade.
    private static final double[] PERCENTILE_THRESHOLDS = {0.99, 0.97, 0.92, 0.82, 0.70, 0.55, 0.40, 0.25, 0.15, 0.08, 0.04, 0.02, 0.01};
    private static final String[] PERCENTILE_GRADES    = {"SSS","SS","S","A+","A","A-","B+","B","B-","C+","C","C-","D"};

    private final Jdbi jdbi;
    private final RatingCurveRepository curveRepo;
    private final RatingScoreCalculator calculator;

    public record RecomputeResult(int updatedCount, int skippedManualCount, int noGradeCount) {}

    public RecomputeResult recompute() {
        List<EnrichmentRow> rows = loadEnrichedRows();

        List<EnrichmentRow> ratable = rows.stream()
                .filter(r -> r.ratingAvg() != null && r.ratingCount() != null && r.ratingCount() > 0)
                .toList();

        if (ratable.size() < MIN_POPULATION_SIZE) {
            log.info("rating-curve: only {} ratable titles — skipping recompute (need >= {})",
                    ratable.size(), MIN_POPULATION_SIZE);
            return new RecomputeResult(0, 0, rows.size());
        }

        double globalMean = ratable.stream()
                .mapToDouble(EnrichmentRow::ratingAvg)
                .average()
                .orElseThrow();

        int m = DEFAULT_MIN_CREDIBLE_VOTES;

        // Compute weighted scores for the full ratable population
        List<Double> weightedScores = new ArrayList<>(ratable.size());
        for (EnrichmentRow row : ratable) {
            double w = (row.ratingCount() * row.ratingAvg() + m * globalMean) / (row.ratingCount() + m);
            weightedScores.add(w);
        }
        Collections.sort(weightedScores);

        List<RatingCurve.Boundary> boundaries = buildBoundaries(weightedScores);

        RatingCurve curve = new RatingCurve(globalMean, ratable.size(), m, boundaries, Instant.now());
        curveRepo.save(curve);
        log.info("rating-curve: recomputed — globalMean={}, population={}, cutoffs={}",
                globalMean, ratable.size(), boundaries.size());

        // Re-stamp all enriched titles
        int updated = 0;
        int skippedManual = 0;
        int noGrade = 0;

        for (EnrichmentRow row : rows) {
            Optional<Actress.Grade> grade = calculator.gradeFor(row.ratingAvg(), row.ratingCount(), curve);
            if (grade.isEmpty()) {
                noGrade++;
                continue;
            }
            // Check existing grade_source
            String source = loadGradeSource(row.titleId());
            if ("manual".equals(source)) {
                skippedManual++;
                continue;
            }
            // Only write if the grade changed (idempotency: avoid spurious updates)
            String currentGrade = loadGrade(row.titleId());
            if (!grade.get().display.equals(currentGrade)) {
                jdbi.useHandle(h ->
                        h.createUpdate("""
                                UPDATE titles SET grade = :grade, grade_source = 'enrichment'
                                WHERE id = :id AND (grade_source IS NULL OR grade_source != 'manual')
                                """)
                                .bind("grade", grade.get().display)
                                .bind("id", row.titleId())
                                .execute());
            }
            updated++;
        }

        log.info("rating-curve: stamped {} titles, skipped {} manual, {} without grade", updated, skippedManual, noGrade);
        return new RecomputeResult(updated, skippedManual, noGrade);
    }

    private List<RatingCurve.Boundary> buildBoundaries(List<Double> sorted) {
        int n = sorted.size();
        List<RatingCurve.Boundary> boundaries = new ArrayList<>();

        for (int i = 0; i < PERCENTILE_THRESHOLDS.length; i++) {
            double pct = PERCENTILE_THRESHOLDS[i];
            // Percentile index: first element at or above this percentile
            int idx = Math.min((int) Math.ceil(pct * n) - 1, n - 1);
            idx = Math.max(idx, 0);
            boundaries.add(new RatingCurve.Boundary(sorted.get(idx), PERCENTILE_GRADES[i]));
        }
        // F grade is everything below the lowest boundary
        boundaries.add(new RatingCurve.Boundary(0.0, "F"));
        return boundaries;
    }

    private List<EnrichmentRow> loadEnrichedRows() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT title_id, rating_avg, rating_count FROM title_javdb_enrichment")
                        .map((rs, ctx) -> {
                            long titleId = rs.getLong("title_id");
                            double rawAvg = rs.getDouble("rating_avg");
                            Double ratingAvg = rs.wasNull() ? null : rawAvg;
                            int rawCount = rs.getInt("rating_count");
                            Integer ratingCount = rs.wasNull() ? null : rawCount;
                            return new EnrichmentRow(titleId, ratingAvg, ratingCount);
                        })
                        .list()
        );
    }

    private String loadGradeSource(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT grade_source FROM titles WHERE id = ?")
                        .bind(0, titleId)
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null));
    }

    private String loadGrade(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT grade FROM titles WHERE id = ?")
                        .bind(0, titleId)
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null));
    }

    record EnrichmentRow(long titleId, Double ratingAvg, Integer ratingCount) {}
}
