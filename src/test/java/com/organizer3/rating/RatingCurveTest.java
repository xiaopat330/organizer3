package com.organizer3.rating;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RatingCurveTest {

    private static final List<RatingCurve.Boundary> SAMPLE_BOUNDARIES = List.of(
            new RatingCurve.Boundary(4.70, "SSS"),
            new RatingCurve.Boundary(4.55, "SS"),
            new RatingCurve.Boundary(4.40, "S"),
            new RatingCurve.Boundary(4.20, "A+"),
            new RatingCurve.Boundary(4.00, "A"),
            new RatingCurve.Boundary(3.80, "A-"),
            new RatingCurve.Boundary(3.60, "B+"),
            new RatingCurve.Boundary(3.40, "B"),
            new RatingCurve.Boundary(3.20, "B-"),
            new RatingCurve.Boundary(3.00, "C+"),
            new RatingCurve.Boundary(2.80, "C"),
            new RatingCurve.Boundary(2.60, "C-"),
            new RatingCurve.Boundary(2.40, "D"),
            new RatingCurve.Boundary(0.00, "F")
    );

    @Test
    void roundTripsThroughJson() {
        Instant now = Instant.parse("2026-04-27T00:00:00Z");
        RatingCurve original = new RatingCurve(4.1, 500, 50, SAMPLE_BOUNDARIES, now);

        String json = original.toCutoffsJson();
        assertNotNull(json);
        assertTrue(json.contains("SSS"), "JSON should contain grade labels");

        RatingCurve restored = RatingCurve.fromRow(4.1, 500, 50, json, now.toString());

        assertEquals(original.globalMean(), restored.globalMean());
        assertEquals(original.globalCount(), restored.globalCount());
        assertEquals(original.minCredibleVotes(), restored.minCredibleVotes());
        assertEquals(original.computedAt(), restored.computedAt());
        assertEquals(original.boundaries().size(), restored.boundaries().size());
        for (int i = 0; i < original.boundaries().size(); i++) {
            assertEquals(original.boundaries().get(i).grade(), restored.boundaries().get(i).grade());
            assertEquals(original.boundaries().get(i).minWeighted(), restored.boundaries().get(i).minWeighted(), 1e-9);
        }
    }

    @Test
    void cutoffsJsonContainsVersionField() {
        RatingCurve curve = new RatingCurve(4.0, 100, 50, SAMPLE_BOUNDARIES, Instant.now());
        String json = curve.toCutoffsJson();
        assertTrue(json.contains("\"version\":1"), "JSON must include version:1");
    }

    @Test
    void boundariesPreservedInOrder() {
        RatingCurve curve = new RatingCurve(4.0, 100, 50, SAMPLE_BOUNDARIES, Instant.now());
        String json = curve.toCutoffsJson();
        RatingCurve restored = RatingCurve.fromRow(4.0, 100, 50, json, Instant.now().toString());

        List<RatingCurve.Boundary> b = restored.boundaries();
        assertEquals("SSS", b.get(0).grade());
        assertEquals("F", b.get(b.size() - 1).grade());
    }
}
