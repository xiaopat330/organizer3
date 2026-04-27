package com.organizer3.rating;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbiRatingCurveRepositoryTest {

    private JdbiRatingCurveRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiRatingCurveRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private static RatingCurve sampleCurve(double globalMean, int count) {
        List<RatingCurve.Boundary> boundaries = List.of(
                new RatingCurve.Boundary(4.70, "SSS"),
                new RatingCurve.Boundary(4.50, "SS"),
                new RatingCurve.Boundary(4.30, "S"),
                new RatingCurve.Boundary(4.10, "A+"),
                new RatingCurve.Boundary(3.90, "A"),
                new RatingCurve.Boundary(3.70, "A-"),
                new RatingCurve.Boundary(3.50, "B+"),
                new RatingCurve.Boundary(3.30, "B"),
                new RatingCurve.Boundary(3.10, "B-"),
                new RatingCurve.Boundary(2.90, "C+"),
                new RatingCurve.Boundary(2.70, "C"),
                new RatingCurve.Boundary(2.50, "C-"),
                new RatingCurve.Boundary(2.30, "D"),
                new RatingCurve.Boundary(0.00, "F")
        );
        return new RatingCurve(globalMean, count, 50, boundaries, Instant.parse("2026-04-27T00:00:00Z"));
    }

    @Test
    void emptyWhenNoRowExists() {
        assertEquals(Optional.empty(), repo.find());
    }

    @Test
    void saveAndRetrieve() {
        RatingCurve curve = sampleCurve(4.1, 300);
        repo.save(curve);

        RatingCurve found = repo.find().orElseThrow();
        assertEquals(4.1, found.globalMean(), 1e-9);
        assertEquals(300, found.globalCount());
        assertEquals(50, found.minCredibleVotes());
        assertEquals(Instant.parse("2026-04-27T00:00:00Z"), found.computedAt());
        assertEquals(14, found.boundaries().size());
        assertEquals("SSS", found.boundaries().get(0).grade());
        assertEquals("F", found.boundaries().get(13).grade());
    }

    @Test
    void upsertOverwritesPreviousRow() {
        repo.save(sampleCurve(4.0, 100));
        repo.save(sampleCurve(4.2, 500));

        RatingCurve found = repo.find().orElseThrow();
        assertEquals(4.2, found.globalMean(), 1e-9);
        assertEquals(500, found.globalCount());
    }

    @Test
    void jsonColumnRoundTrips() {
        RatingCurve original = sampleCurve(3.9, 250);
        repo.save(original);
        RatingCurve found = repo.find().orElseThrow();

        for (int i = 0; i < original.boundaries().size(); i++) {
            assertEquals(original.boundaries().get(i).grade(), found.boundaries().get(i).grade());
            assertEquals(original.boundaries().get(i).minWeighted(), found.boundaries().get(i).minWeighted(), 1e-9);
        }
    }
}
