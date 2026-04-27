package com.organizer3.rating;

import java.util.Optional;

public interface RatingCurveRepository {

    Optional<RatingCurve> find();

    /** Upserts the single rating_curve row (id=1). */
    void save(RatingCurve curve);
}
