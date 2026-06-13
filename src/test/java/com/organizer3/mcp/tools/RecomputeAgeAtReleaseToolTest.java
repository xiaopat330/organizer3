package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.AgeAtReleaseRecomputer;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link RecomputeAgeAtReleaseTool}.
 *
 * <ul>
 *   <li>Dry-run scenario: uses a real in-memory SQLite DB to verify no writes occur and
 *       prospective count is correct.</li>
 *   <li>Live-run scenario: Mockito mock for the recomputer (verifies {@code recomputeAll()} is
 *       called); real SQLite for the implausible/totalComputable queries.</li>
 * </ul>
 */
class RecomputeAgeAtReleaseToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private AgeAtReleaseRecomputer recomputer;
    private RecomputeAgeAtReleaseTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        // Volume row required by title FK (not all schemas require it but insert to be safe).
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO volumes (id, structure_type) VALUES ('v', 'conventional')"));

        // Real recomputer wired to the same jdbi.
        recomputer = new AgeAtReleaseRecomputer(jdbi);
        tool = new RecomputeAgeAtReleaseTool(recomputer, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertActress(String name, String dob) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at, date_of_birth) "
                             + "VALUES (:n, 'regular', '2020-01-01', :d)")
                        .bind("n", name)
                        .bind("d", dob)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }

    private long insertTitle(String code, String releaseDate) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num, release_date) "
                             + "VALUES (:c, :c, 'TST', 1, :r)")
                        .bind("c", code)
                        .bind("r", releaseDate)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }

    private void credit(long titleId, long actressId) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)",
                        titleId, actressId));
    }

    private void setAge(long titleId, long actressId, Integer age) {
        jdbi.useHandle(h ->
                h.execute("UPDATE title_actresses SET age_at_release = ? WHERE title_id = ? AND actress_id = ?",
                        age, titleId, actressId));
    }

    private Integer readAge(long titleId, long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT age_at_release FROM title_actresses WHERE title_id = ? AND actress_id = ?")
                        .bind(0, titleId)
                        .bind(1, actressId)
                        .mapTo(Integer.class)
                        .one());
    }

    private static ObjectNode args(boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("dry_run", dryRun);
        return n;
    }

    private static ObjectNode emptyArgs() {
        return M.createObjectNode();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * dry_run=true must not write any rows and must return the correct prospective changed count.
     */
    @Test
    void dryRunWritesNothingAndReportsPotentialChange() {
        // Actress born 1990-01-01, title released 2010-01-01 → age should be 20.
        long a = insertActress("Test Actress", "1990-01-01");
        long t = insertTitle("TST-001", "2010-01-01");
        credit(t, a);
        // Leave age_at_release NULL — differs from derivable value of 20.

        RecomputeAgeAtReleaseTool.Result result =
                (RecomputeAgeAtReleaseTool.Result) tool.call(args(true));

        // 1 row would change (NULL → 20).
        assertEquals(1, result.changedRows());
        // totalComputable on dry-run reflects current state (still 0, nothing written).
        assertEquals(0, result.totalComputable());
        // DB row still NULL — nothing was written.
        assertNull(readAge(t, a), "dry_run must not write age_at_release");
    }

    /**
     * Live run must invoke the real recomputer and report the actual changed count.
     */
    @Test
    void liveRunInvokesRecomputeAllAndReportsCount() {
        // Use a Mockito mock to verify recomputeAll() is called and control its return value.
        AgeAtReleaseRecomputer mockRecomputer = mock(AgeAtReleaseRecomputer.class);
        when(mockRecomputer.recomputeAll()).thenReturn(7);
        // findImplausible() is NOT called by the tool (it uses its own enriched query).
        RecomputeAgeAtReleaseTool toolWithMock = new RecomputeAgeAtReleaseTool(mockRecomputer, jdbi);

        RecomputeAgeAtReleaseTool.Result result =
                (RecomputeAgeAtReleaseTool.Result) toolWithMock.call(emptyArgs());

        verify(mockRecomputer, times(1)).recomputeAll();
        assertEquals(7, result.changedRows());
    }

    /**
     * Implausible rows (age < 18) surface with enriched code and actress name on a live run.
     */
    @Test
    void implausibleRowsSurfaceWithCodeAndName() {
        // Actress born 2000-01-01, title released 2010-06-01 → age = 10 (< 18 → implausible).
        long a = insertActress("Young Actress", "2000-01-01");
        long t = insertTitle("IMP-001", "2010-06-01");
        credit(t, a);

        // Run the real recomputer first (live run path).
        RecomputeAgeAtReleaseTool.Result result =
                (RecomputeAgeAtReleaseTool.Result) tool.call(emptyArgs());

        assertEquals(1, result.changedRows());
        assertEquals(1, result.implausible().size());
        RecomputeAgeAtReleaseTool.ImplausibleRow row = result.implausible().get(0);
        assertEquals("IMP-001", row.code());
        assertEquals("Young Actress", row.actressName());
        assertEquals(10, row.age());
        assertEquals(t, row.titleId());
        assertEquals(a, row.actressId());
    }

    /**
     * Empty implausible list when all computed ages are in the plausible range.
     */
    @Test
    void emptyImplausibleWhenAllAgesPlausible() {
        // Actress born 1985-01-01, title released 2015-01-01 → age = 30 (plausible).
        long a = insertActress("Normal Actress", "1985-01-01");
        long t = insertTitle("NORM-001", "2015-01-01");
        credit(t, a);

        RecomputeAgeAtReleaseTool.Result result =
                (RecomputeAgeAtReleaseTool.Result) tool.call(emptyArgs());

        assertEquals(0, result.implausible().size());
        assertEquals(1, result.totalComputable());
    }

    /**
     * totalComputable counts rows with age_at_release IS NOT NULL after a live run.
     */
    @Test
    void totalComputableReflectsRowsWithAge() {
        long a1 = insertActress("A1", "1985-01-01");
        long a2 = insertActress("A2", null); // No DOB → age not computable.
        long t1 = insertTitle("TC-001", "2010-01-01");
        long t2 = insertTitle("TC-002", "2010-01-01");
        credit(t1, a1);
        credit(t2, a2);

        RecomputeAgeAtReleaseTool.Result result =
                (RecomputeAgeAtReleaseTool.Result) tool.call(emptyArgs());

        // Only a1/t1 pair is computable (a2 has no DOB).
        assertEquals(1, result.totalComputable());
        assertEquals(1, result.changedRows());
    }

    /**
     * dry_run with pre-existing stale value reports the row as would-change but does not touch DB.
     */
    @Test
    void dryRunDetectsStaleStoredValue() {
        long a = insertActress("Stale Actress", "1985-01-01");
        long t = insertTitle("ST-001", "2010-01-01");
        credit(t, a);
        // Pre-set a wrong age.
        setAge(t, a, 99);

        RecomputeAgeAtReleaseTool.Result result =
                (RecomputeAgeAtReleaseTool.Result) tool.call(args(true));

        assertEquals(1, result.changedRows(), "stale value 99 differs from derived 25");
        assertEquals(99, readAge(t, a), "dry_run must not overwrite the stored value");
    }
}
