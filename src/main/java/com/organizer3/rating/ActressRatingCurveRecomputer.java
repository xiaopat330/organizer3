package com.organizer3.rating;

import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates a full recompute of the actress rating curve and re-stamps every qualifying
 * actress's {@code computed_grade}. Mirrors {@link RatingCurveRecomputer} on the actress side.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Aggregate per-actress rating data from {@code title_javdb_enrichment} via
 *       {@code title_actresses}, excluding sentinel actresses.</li>
 *   <li>For actresses with at least {@link #DEFAULT_MIN_TITLES} enriched-and-rated titles,
 *       compute the Bayesian-shrunken vote-weighted-mean.</li>
 *   <li>Compute the population mean ({@code C}) as the average shrunken score (note:
 *       in this recompute the prior C and the sample to fit cutoffs are the same population
 *       — actresses who qualify).</li>
 *   <li>Build percentile cutoffs over the qualifying-actress shrunken-score distribution.</li>
 *   <li>Persist the curve and stamp {@code computed_grade}, {@code computed_grade_score},
 *       {@code computed_grade_n} on every actress; clears the columns for those who no
 *       longer qualify.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class ActressRatingCurveRecomputer {

    public static final int DEFAULT_MIN_TITLES = 5;
    public static final int DEFAULT_MIN_CREDIBLE_VOTES = 200;
    private static final int MIN_POPULATION_SIZE = 2;

    // Same percentile shape as the title curve so the grade meaning stays consistent.
    private static final double[] PERCENTILE_THRESHOLDS = {0.99, 0.97, 0.92, 0.82, 0.70, 0.55, 0.40, 0.25, 0.15, 0.08, 0.04, 0.02, 0.01};
    private static final String[] PERCENTILE_GRADES    = {"SSS","SS","S","A+","A","A-","B+","B","B-","C+","C","C-","D"};

    private final Jdbi jdbi;
    private final ActressRatingCurveRepository curveRepo;
    private final ActressRepository actressRepo;
    private final ActressRatingCalculator calculator;

    public record RecomputeResult(int qualifying, int stamped, int cleared, int totalActresses) {}

    public RecomputeResult recompute() {
        Map<Long, List<ActressRatingCalculator.TitleRatingRow>> rowsByActress = loadRowsByActress();

        // Determine qualifying actresses (N≥MIN_TITLES with non-zero votes).
        Map<Long, List<ActressRatingCalculator.TitleRatingRow>> qualifying = new HashMap<>();
        for (var entry : rowsByActress.entrySet()) {
            List<ActressRatingCalculator.TitleRatingRow> rows = entry.getValue();
            int countWithVotes = 0;
            for (var r : rows) if (r.ratingCount() > 0) countWithVotes++;
            if (countWithVotes >= DEFAULT_MIN_TITLES) {
                qualifying.put(entry.getKey(), rows);
            }
        }

        if (qualifying.size() < MIN_POPULATION_SIZE) {
            log.info("actress-rating-curve: only {} qualifying actresses — skipping recompute (need >= {})",
                    qualifying.size(), MIN_POPULATION_SIZE);
            return new RecomputeResult(qualifying.size(), 0, 0, rowsByActress.size());
        }

        // Population mean C: vote-weighted mean across the union of qualifying titles.
        long popVotes = 0;
        double popWeightedSum = 0;
        for (List<ActressRatingCalculator.TitleRatingRow> rows : qualifying.values()) {
            for (var r : rows) {
                if (r.ratingCount() <= 0) continue;
                popVotes += r.ratingCount();
                popWeightedSum += r.ratingCount() * r.ratingAvg();
            }
        }
        double globalMean = popWeightedSum / popVotes;
        int m = DEFAULT_MIN_CREDIBLE_VOTES;

        // Build a placeholder curve so the calculator can compute scores; cutoffs will be
        // overwritten with real percentile boundaries below.
        ActressRatingCurve placeholder = new ActressRatingCurve(globalMean, qualifying.size(), m,
                List.of(new ActressRatingCurve.Boundary(0.0, "F")), Instant.now());

        // Compute shrunken score for every qualifying actress.
        Map<Long, Double> scoreByActress = new HashMap<>();
        List<Double> sortedScores = new ArrayList<>(qualifying.size());
        for (var entry : qualifying.entrySet()) {
            Optional<Double> s = calculator.shrunkenScore(entry.getValue(), placeholder, DEFAULT_MIN_TITLES);
            s.ifPresent(score -> {
                scoreByActress.put(entry.getKey(), score);
                sortedScores.add(score);
            });
        }
        Collections.sort(sortedScores);

        List<ActressRatingCurve.Boundary> boundaries = buildBoundaries(sortedScores);
        ActressRatingCurve curve = new ActressRatingCurve(globalMean, qualifying.size(), m, boundaries, Instant.now());
        curveRepo.save(curve);
        log.info("actress-rating-curve: recomputed — globalMean={}, qualifying={}, cutoffs={}",
                globalMean, qualifying.size(), boundaries.size());

        // Stamp grades on qualifying actresses; clear non-qualifying.
        int stamped = 0;
        int cleared = 0;
        for (var entry : rowsByActress.entrySet()) {
            long actressId = entry.getKey();
            Double score = scoreByActress.get(actressId);
            if (score == null) {
                actressRepo.setComputedGrade(actressId, null, null, null);
                cleared++;
                continue;
            }
            Actress.Grade grade = mapToGrade(score, boundaries);
            int n = (int) entry.getValue().stream().filter(r -> r.ratingCount() > 0).count();
            actressRepo.setComputedGrade(actressId, grade, score, n);
            stamped++;
        }

        // Also clear computed_grade for any actress not in rowsByActress at all (e.g. lost
        // her last rated title since the previous recompute). Cheap blanket UPDATE.
        int blanket = jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE actresses
                        SET computed_grade = NULL, computed_grade_score = NULL, computed_grade_n = NULL
                        WHERE id NOT IN (<ids>) AND computed_grade IS NOT NULL
                        """)
                        .bindList("ids", rowsByActress.isEmpty() ? List.of(-1L) : new ArrayList<>(rowsByActress.keySet()))
                        .execute());
        cleared += blanket;

        log.info("actress-rating-curve: stamped {} actresses, cleared {} (incl. {} blanket)",
                stamped, cleared, blanket);
        return new RecomputeResult(qualifying.size(), stamped, cleared, rowsByActress.size());
    }

    private Actress.Grade mapToGrade(double score, List<ActressRatingCurve.Boundary> boundaries) {
        for (ActressRatingCurve.Boundary b : boundaries) {
            if (score >= b.minWeighted()) {
                return Actress.Grade.fromDisplay(b.grade());
            }
        }
        return Actress.Grade.F;
    }

    private List<ActressRatingCurve.Boundary> buildBoundaries(List<Double> sortedScores) {
        int n = sortedScores.size();
        List<ActressRatingCurve.Boundary> boundaries = new ArrayList<>();
        for (int i = 0; i < PERCENTILE_THRESHOLDS.length; i++) {
            double pct = PERCENTILE_THRESHOLDS[i];
            int idx = Math.min((int) Math.ceil(pct * n) - 1, n - 1);
            idx = Math.max(idx, 0);
            boundaries.add(new ActressRatingCurve.Boundary(sortedScores.get(idx), PERCENTILE_GRADES[i]));
        }
        boundaries.add(new ActressRatingCurve.Boundary(0.0, "F"));
        return boundaries;
    }

    /**
     * Load per-actress rated-title rows. Excludes sentinel actresses. One row per
     * (title, actress) pairing — multi-actress titles credit each fully.
     */
    private Map<Long, List<ActressRatingCalculator.TitleRatingRow>> loadRowsByActress() {
        return jdbi.withHandle(h -> {
            Map<Long, List<ActressRatingCalculator.TitleRatingRow>> out = new HashMap<>();
            h.createQuery("""
                    SELECT ta.actress_id AS actress_id,
                           e.rating_avg  AS rating_avg,
                           e.rating_count AS rating_count
                    FROM title_actresses ta
                    JOIN title_javdb_enrichment e ON e.title_id = ta.title_id
                    JOIN actresses a ON a.id = ta.actress_id
                    WHERE e.rating_avg IS NOT NULL
                      AND e.rating_count IS NOT NULL
                      AND e.rating_count > 0
                      AND COALESCE(a.is_sentinel, 0) = 0
                    """)
                    .map((rs, ctx) -> {
                        long aid = rs.getLong("actress_id");
                        double avg = rs.getDouble("rating_avg");
                        int count = rs.getInt("rating_count");
                        return Map.entry(aid, new ActressRatingCalculator.TitleRatingRow(avg, count));
                    })
                    .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
            return out;
        });
    }
}
