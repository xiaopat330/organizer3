package com.organizer3.rating;

import com.organizer3.model.Actress;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Single-title path: stamps (grade, grade_source='enrichment') after a title's enrichment row
 * is written, using the cached RatingCurve. Skips if no curve exists yet — the first batch
 * recompute will stamp it.
 */
@Slf4j
@RequiredArgsConstructor
public class EnrichmentGradeStamper {

    private final RatingCurveRepository curveRepo;
    private final RatingScoreCalculator calculator;
    private final TitleRepository titleRepo;

    /**
     * Stamps the grade for a single title if a curve is available and rating data is present.
     * No-op if ratingAvg is null, ratingCount is null/zero, or no curve exists.
     */
    public void stampIfRated(long titleId, Double ratingAvg, Integer ratingCount) {
        if (ratingAvg == null || ratingCount == null || ratingCount == 0) return;

        Optional<RatingCurve> maybeCurve = curveRepo.find();
        if (maybeCurve.isEmpty()) {
            log.debug("rating-curve: no curve yet — skipping per-title stamp for title {}", titleId);
            return;
        }

        Optional<Actress.Grade> grade = calculator.gradeFor(ratingAvg, ratingCount, maybeCurve.get());
        if (grade.isEmpty()) return;

        titleRepo.setGradeFromEnrichment(titleId, grade.get());
        log.debug("rating-curve: stamped title {} → {} (ratingAvg={}, count={})",
                titleId, grade.get().display, ratingAvg, ratingCount);
    }
}
