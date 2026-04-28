package com.organizer3.rating;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.util.Optional;

@RequiredArgsConstructor
public class JdbiActressRatingCurveRepository implements ActressRatingCurveRepository {

    private final Jdbi jdbi;

    @Override
    public Optional<ActressRatingCurve> find() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT global_mean, global_count, min_credible_votes, cutoffs_json, computed_at FROM actress_rating_curve WHERE id = 1")
                        .map((rs, ctx) -> ActressRatingCurve.fromRow(
                                rs.getDouble("global_mean"),
                                rs.getInt("global_count"),
                                rs.getInt("min_credible_votes"),
                                rs.getString("cutoffs_json"),
                                rs.getString("computed_at")))
                        .findOne()
        );
    }

    @Override
    public void save(ActressRatingCurve curve) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO actress_rating_curve (id, global_mean, global_count, min_credible_votes, cutoffs_json, computed_at)
                        VALUES (1, :globalMean, :globalCount, :minCredibleVotes, :cutoffsJson, :computedAt)
                        ON CONFLICT(id) DO UPDATE SET
                            global_mean         = excluded.global_mean,
                            global_count        = excluded.global_count,
                            min_credible_votes  = excluded.min_credible_votes,
                            cutoffs_json        = excluded.cutoffs_json,
                            computed_at         = excluded.computed_at
                        """)
                        .bind("globalMean", curve.globalMean())
                        .bind("globalCount", curve.globalCount())
                        .bind("minCredibleVotes", curve.minCredibleVotes())
                        .bind("cutoffsJson", curve.toCutoffsJson())
                        .bind("computedAt", curve.computedAt().toString())
                        .execute()
        );
    }
}
