package com.organizer3.rating;

import com.organizer3.model.Actress;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class EnrichmentGradeStamperTest {

    private RatingCurveRepository curveRepo;
    private TitleRepository titleRepo;
    private EnrichmentGradeStamper stamper;

    private static final List<RatingCurve.Boundary> BOUNDARIES = List.of(
            new RatingCurve.Boundary(4.50, "SSS"),
            new RatingCurve.Boundary(4.00, "A"),
            new RatingCurve.Boundary(0.00, "F")
    );
    private static final RatingCurve CURVE = new RatingCurve(4.0, 100, 50, BOUNDARIES, Instant.now());

    @BeforeEach
    void setUp() {
        curveRepo = Mockito.mock(RatingCurveRepository.class);
        titleRepo = Mockito.mock(TitleRepository.class);
        stamper = new EnrichmentGradeStamper(curveRepo, new RatingScoreCalculator(), titleRepo);
    }

    @Test
    void stampsGradeWhenCurveExistsAndRatingPresent() {
        when(curveRepo.find()).thenReturn(Optional.of(CURVE));

        // ratingAvg=4.8, count=200 → weighted = (200*4.8 + 50*4.0) / 250 = (960+200)/250 = 4.64 → SSS
        stamper.stampIfRated(1L, 4.8, 200);

        verify(titleRepo).setGradeFromEnrichment(1L, Actress.Grade.SSS);
    }

    @Test
    void skipsWhenNoCurve() {
        when(curveRepo.find()).thenReturn(Optional.empty());

        stamper.stampIfRated(1L, 4.8, 200);

        verify(titleRepo, never()).setGradeFromEnrichment(anyLong(), any());
    }

    @Test
    void skipsWhenRatingAvgNull() {
        stamper.stampIfRated(1L, null, 200);

        verify(curveRepo, never()).find();
        verify(titleRepo, never()).setGradeFromEnrichment(anyLong(), any());
    }

    @Test
    void skipsWhenRatingCountNull() {
        stamper.stampIfRated(1L, 4.5, null);

        verify(curveRepo, never()).find();
        verify(titleRepo, never()).setGradeFromEnrichment(anyLong(), any());
    }

    @Test
    void skipsWhenRatingCountZero() {
        stamper.stampIfRated(1L, 4.5, 0);

        verify(curveRepo, never()).find();
        verify(titleRepo, never()).setGradeFromEnrichment(anyLong(), any());
    }
}
