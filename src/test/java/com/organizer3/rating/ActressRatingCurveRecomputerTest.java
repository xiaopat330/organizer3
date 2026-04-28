package com.organizer3.rating;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActressRatingCurveRecomputerTest {

    private Jdbi jdbi;
    private Connection connection;
    private JdbiActressRatingCurveRepository curveRepo;
    private ActressRepository actressRepo;
    private ActressRatingCurveRecomputer recomputer;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        curveRepo = new JdbiActressRatingCurveRepository(jdbi);
        actressRepo = new JdbiActressRepository(jdbi);
        recomputer = new ActressRatingCurveRecomputer(jdbi, curveRepo, actressRepo, new ActressRatingCalculator());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private long seedActress(String name, boolean sentinel) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build()).getId();
    }

    private long seedSentinel(String name) {
        long id = seedActress(name, true);
        jdbi.useHandle(h -> h.createUpdate("UPDATE actresses SET is_sentinel = 1 WHERE id = ?")
                .bind(0, id).execute());
        return id;
    }

    /** Insert a title with enrichment row and link it to the given actress. Returns title id. */
    private long seedTitle(String code, double ratingAvg, int ratingCount, long... actressIds) {
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
        for (long aid : actressIds) {
            jdbi.useHandle(h -> h.createUpdate("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                    .bind(0, titleId).bind(1, aid).execute());
        }
        return titleId;
    }

    private Map<String, Object> actressGradeRow(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT computed_grade, computed_grade_score, computed_grade_n FROM actresses WHERE id = ?")
                        .bind(0, id).mapToMap().one());
    }

    @Test
    void belowFiveTitlesDoesNotGetGraded() {
        long aId = seedActress("Alice", false);
        for (int i = 1; i <= 4; i++) seedTitle("AL-" + i, 4.5, 1000, aId);

        // Need a second qualifying actress so the curve can be built.
        long bId = seedActress("Bob", false);
        for (int i = 1; i <= 5; i++) seedTitle("BO-" + i, 4.5, 1000, bId);
        long cId = seedActress("Carol", false);
        for (int i = 1; i <= 5; i++) seedTitle("CA-" + i, 4.0, 1000, cId);

        recomputer.recompute();

        Map<String, Object> alice = actressGradeRow(aId);
        assertNull(alice.get("computed_grade"), "Alice has only 4 titles, must not be graded");
        assertNull(alice.get("computed_grade_score"));
    }

    @Test
    void atLeastFiveTitlesGetsGraded() {
        long aId = seedActress("Alice", false);
        for (int i = 1; i <= 5; i++) seedTitle("AL-" + i, 4.7, 5000, aId);
        long bId = seedActress("Bob", false);
        for (int i = 1; i <= 5; i++) seedTitle("BO-" + i, 3.8, 5000, bId);

        recomputer.recompute();

        Map<String, Object> alice = actressGradeRow(aId);
        assertNotNull(alice.get("computed_grade"));
        assertNotNull(alice.get("computed_grade_score"));
        assertEquals(5, ((Number) alice.get("computed_grade_n")).intValue());
    }

    @Test
    void sentinelActressIsExcluded() {
        long sentinel = seedSentinel("Various");
        for (int i = 1; i <= 10; i++) seedTitle("VAR-" + i, 4.7, 5000, sentinel);
        long aId = seedActress("Alice", false);
        for (int i = 1; i <= 5; i++) seedTitle("AL-" + i, 4.5, 5000, aId);
        long bId = seedActress("Bob", false);
        for (int i = 1; i <= 5; i++) seedTitle("BO-" + i, 4.0, 5000, bId);

        ActressRatingCurveRecomputer.RecomputeResult r = recomputer.recompute();

        assertEquals(2, r.qualifying(), "sentinel must not count toward qualifying population");
        Map<String, Object> sent = actressGradeRow(sentinel);
        assertNull(sent.get("computed_grade"));
    }

    @Test
    void multiActressTitleCreditsEachFully() {
        long aId = seedActress("Alice", false);
        long bId = seedActress("Bob", false);
        // Five collab titles only; both get the same score.
        for (int i = 1; i <= 5; i++) seedTitle("CO-" + i, 4.5, 1000, aId, bId);
        // A third actress so we have a population.
        long cId = seedActress("Carol", false);
        for (int i = 1; i <= 5; i++) seedTitle("CA-" + i, 3.8, 1000, cId);

        recomputer.recompute();

        Map<String, Object> alice = actressGradeRow(aId);
        Map<String, Object> bob = actressGradeRow(bId);
        assertEquals(alice.get("computed_grade_score"), bob.get("computed_grade_score"),
                "collab actresses must score identically when their only titles overlap");
    }

    @Test
    void rerunClearsGradeWhenActressNoLongerQualifies() {
        long aId = seedActress("Alice", false);
        for (int i = 1; i <= 5; i++) seedTitle("AL-" + i, 4.5, 1000, aId);
        long bId = seedActress("Bob", false);
        for (int i = 1; i <= 5; i++) seedTitle("BO-" + i, 4.0, 1000, bId);

        recomputer.recompute();
        assertNotNull(actressGradeRow(aId).get("computed_grade"));

        // Drop Alice's titles → she falls below the floor.
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM title_actresses WHERE actress_id = ?")
                .bind(0, aId).execute());

        // Need at least 2 qualifying actresses for the curve to be built — add Carol.
        long cId = seedActress("Carol", false);
        for (int i = 1; i <= 5; i++) seedTitle("CA-" + i, 4.2, 1000, cId);

        recomputer.recompute();
        assertNull(actressGradeRow(aId).get("computed_grade"),
                "Alice's grade must clear once she no longer qualifies");
    }

    @Test
    void cutoffsAreNonDecreasing() {
        // 20 actresses with varied scores
        for (int a = 1; a <= 20; a++) {
            long aid = seedActress("A" + a, false);
            for (int t = 1; t <= 5; t++) {
                seedTitle("A" + a + "-T" + t, 3.5 + (a * 0.05), 1000, aid);
            }
        }
        recomputer.recompute();

        ActressRatingCurve curve = curveRepo.find().orElseThrow();
        // Boundaries are listed descending by minWeighted (highest grade first).
        for (int i = 1; i < curve.boundaries().size(); i++) {
            double prev = curve.boundaries().get(i - 1).minWeighted();
            double curr = curve.boundaries().get(i).minWeighted();
            assertTrue(prev >= curr,
                    "boundaries must descend: " + prev + " >= " + curr + " at index " + i);
        }
    }
}
