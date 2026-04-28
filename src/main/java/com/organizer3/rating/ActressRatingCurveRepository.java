package com.organizer3.rating;

import java.util.Optional;

public interface ActressRatingCurveRepository {

    Optional<ActressRatingCurve> find();

    /** Upserts the single actress_rating_curve row (id=1). */
    void save(ActressRatingCurve curve);
}
