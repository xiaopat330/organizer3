package com.organizer3.rating;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RatingCurveRecomputerTest {

    private Jdbi jdbi;
    private Connection connection;
    private JdbiRatingCurveRepository curveRepo;
    private RatingCurveRecomputer recomputer;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        // Insert a volume row (needed for title FK via title_locations, but titles alone don't need it)
        curveRepo = new JdbiRatingCurveRepository(jdbi);
        recomputer = new RatingCurveRecomputer(jdbi, curveRepo, new RatingScoreCalculator());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    /** Insert a title and matching enrichment row. Returns the title_id. */
    private long seedTitle(String code, double ratingAvg, int ratingCount) {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (?,?,?,?)")
                        .bind(0, code).bind(1, code).bind(2, "T").bind(3, 1)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, rating_avg, rating_count)
                        VALUES (?, ?, '2026-01-01T00:00:00Z', ?, ?)
                        """)
                        .bind(0, titleId).bind(1, "slug-" + code)
                        .bind(2, ratingAvg).bind(3, ratingCount)
                        .execute());
        return titleId;
    }

    /** Insert a title with no rating data. */
    private long seedTitleNoRating(String code) {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (?,?,?,?)")
                        .bind(0, code).bind(1, code).bind(2, "T").bind(3, 1)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at)
                        VALUES (?, ?, '2026-01-01T00:00:00Z')
                        """)
                        .bind(0, titleId).bind(1, "slug-" + code)
                        .execute());
        return titleId;
    }

    private Map<String, Object> titleRow(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, id).mapToMap().one());
    }

    @Test
    void recomputeComputesGlobalMeanAndCurvePersisted() {
        // 10 titles with varying ratings
        for (int i = 1; i <= 10; i++) {
            seedTitle("T-" + i, 3.0 + i * 0.2, 100);
        }

        recomputer.recompute();

        RatingCurve curve = curveRepo.find().orElseThrow();
        assertEquals(10, curve.globalCount());
        // globalMean ≈ average of [3.2, 3.4, 3.6, 3.8, 4.0, 4.2, 4.4, 4.6, 4.8, 5.0] = 4.1
        assertEquals(4.1, curve.globalMean(), 1e-9);
    }

    @Test
    void cutoffsAreNonDecreasing() {
        for (int i = 1; i <= 20; i++) {
            seedTitle("C-" + i, 3.0 + i * 0.1, 100);
        }
        recomputer.recompute();

        RatingCurve curve = curveRepo.find().orElseThrow();
        List<RatingCurve.Boundary> b = curve.boundaries();
        // Boundaries are sorted descending; minWeighted values should be non-increasing
        for (int i = 1; i < b.size(); i++) {
            assertTrue(b.get(i).minWeighted() <= b.get(i - 1).minWeighted(),
                    "boundary[" + i + "].minWeighted=" + b.get(i).minWeighted() +
                    " must be <= boundary[" + (i-1) + "].minWeighted=" + b.get(i-1).minWeighted());
        }
    }

    @Test
    void allNonManualEnrichedTitlesGetGradeStamped() {
        long id1 = seedTitle("S-001", 4.8, 300);
        long id2 = seedTitle("S-002", 3.5, 100);
        long id3 = seedTitle("S-003", 4.2, 200);

        recomputer.recompute();

        assertNotNull(titleRow(id1).get("grade"), "title 1 should have a grade");
        assertNotNull(titleRow(id2).get("grade"), "title 2 should have a grade");
        assertNotNull(titleRow(id3).get("grade"), "title 3 should have a grade");
        assertEquals("enrichment", titleRow(id1).get("grade_source"));
        assertEquals("enrichment", titleRow(id2).get("grade_source"));
        assertEquals("enrichment", titleRow(id3).get("grade_source"));
    }

    @Test
    void manualGradeTitleIsNeverOverwritten() {
        long manualId = seedTitle("M-001", 4.8, 300);
        // Seed some peers so recompute actually runs
        seedTitle("S-001", 3.5, 100);
        seedTitle("S-002", 4.0, 200);

        // Set the title to manual grade
        jdbi.useHandle(h ->
                h.execute("UPDATE titles SET grade = 'SSS', grade_source = 'manual' WHERE id = ?", manualId));

        RatingCurveRecomputer.RecomputeResult result = recomputer.recompute();

        Map<String, Object> row = titleRow(manualId);
        assertEquals("SSS", row.get("grade"), "manual grade must not be overwritten");
        assertEquals("manual", row.get("grade_source"));
        assertEquals(1, result.skippedManualCount(), "recompute should report 1 skipped-manual");
    }

    @Test
    void recomputeIsIdempotent() {
        for (int i = 1; i <= 5; i++) {
            seedTitle("I-" + i, 3.0 + i * 0.3, 100);
        }
        recomputer.recompute();

        // Capture grades after first recompute
        List<String> gradesBefore = jdbi.withHandle(h ->
                h.createQuery("SELECT grade FROM titles ORDER BY id").mapTo(String.class).list());
        RatingCurve curveBefore = curveRepo.find().orElseThrow();

        // Second recompute on unchanged population
        recomputer.recompute();

        List<String> gradesAfter = jdbi.withHandle(h ->
                h.createQuery("SELECT grade FROM titles ORDER BY id").mapTo(String.class).list());
        RatingCurve curveAfter = curveRepo.find().orElseThrow();

        assertEquals(gradesBefore, gradesAfter, "grades must not change on identical population");
        assertEquals(curveBefore.globalMean(), curveAfter.globalMean(), 1e-9);
    }

    @Test
    void titlesWithNoRatingDataReceiveNoGrade() {
        long id = seedTitleNoRating("NR-001");
        seedTitle("R-001", 4.0, 100);
        seedTitle("R-002", 3.5, 100);

        recomputer.recompute();

        assertNull(titleRow(id).get("grade"), "title with no rating data should remain ungraded");
    }

    @Test
    void smallPopulationSkipsRecompute() {
        // Population of 1 — below MIN_POPULATION_SIZE threshold
        seedTitle("LONE-1", 4.5, 200);

        RatingCurveRecomputer.RecomputeResult result = recomputer.recompute();

        assertTrue(curveRepo.find().isEmpty(), "curve should not be persisted for tiny population");
        assertEquals(0, result.updatedCount());
    }
}
