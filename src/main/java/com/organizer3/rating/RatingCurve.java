package com.organizer3.rating;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Immutable value object holding the Bayesian rating curve used to grade titles.
 *
 * <p>Boundaries are sorted descending by {@code minWeighted}; the grade for a weighted
 * score is the first boundary whose {@code minWeighted <= weighted}.
 */
public record RatingCurve(
        double globalMean,
        int globalCount,
        int minCredibleVotes,
        List<Boundary> boundaries,
        Instant computedAt
) {

    public record Boundary(double minWeighted, String grade) {}

    // --- JSON round-trip for the cutoffs_json column ---

    private static final ObjectMapper JSON = new ObjectMapper();

    public String toCutoffsJson() {
        try {
            return JSON.writeValueAsString(new CutoffsJson(1, boundaries));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize cutoffs_json", e);
        }
    }

    public static RatingCurve fromRow(double globalMean, int globalCount, int minCredibleVotes,
                                      String cutoffsJson, String computedAt) {
        try {
            CutoffsJson parsed = JSON.readValue(cutoffsJson, CutoffsJson.class);
            return new RatingCurve(globalMean, globalCount, minCredibleVotes,
                    List.copyOf(parsed.boundaries()), Instant.parse(computedAt));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize cutoffs_json", e);
        }
    }

    private record CutoffsJson(
            @JsonProperty("version") int version,
            @JsonProperty("boundaries") List<Boundary> boundaries
    ) {}
}
