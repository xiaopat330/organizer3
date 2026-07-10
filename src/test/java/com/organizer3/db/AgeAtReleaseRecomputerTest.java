package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgeAtReleaseRecomputer} using a real in-memory SQLite database
 * bootstrapped with the current schema.
 */
class AgeAtReleaseRecomputerTest {

    private Jdbi jdbi;
    private Connection connection;
    private AgeAtReleaseRecomputer recomputer;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        recomputer = new AgeAtReleaseRecomputer(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Insert a minimal actress row; returns the generated id. */
    private long insertActress(String name, String dob) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at, date_of_birth) VALUES (:n, 'regular', '2020-01-01', :d)")
                        .bind("n", name)
                        .bind("d", dob)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }

    /** Insert a minimal title row; returns the generated id. */
    private long insertTitle(String code, String releaseDate) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num, release_date) VALUES (:c, :c, 'TST', 1, :r)")
                        .bind("c", code)
                        .bind("r", releaseDate)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }

    /** Insert an enrichment row overriding the release date. */
    private void insertEnrichment(long titleId, String releaseDate) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, release_date) VALUES (?, 'slug', '2020-01-01T00:00:00Z', ?)", titleId, releaseDate));
    }

    /** Link actress to title. */
    private void credit(long titleId, long actressId) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", titleId, actressId));
    }

    /** Read age_at_release for a single credit row; returns null when stored as SQL NULL. */
    private Integer readAge(long titleId, long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT age_at_release FROM title_actresses WHERE title_id = ? AND actress_id = ?")
                        .bind(0, titleId)
                        .bind(1, actressId)
                        .mapTo(Integer.class)
                        .findOne()
                        .orElse(null));
    }

    // -------------------------------------------------------------------------
    // Age formula: boundary cases
    // -------------------------------------------------------------------------

    @Test
    void ageOneDayBeforeBirthday_isYearMinus1() {
        // DOB 2000-03-15, release 2020-03-14 → 19 (not yet turned 20)
        long actress = insertActress("A", "2000-03-15");
        long title   = insertTitle("TST-001", "2020-03-14");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(19, readAge(title, actress));
    }

    @Test
    void ageOnBirthday_isExactYear() {
        // DOB 2000-03-15, release 2020-03-15 → 20 (birthday reached)
        long actress = insertActress("B", "2000-03-15");
        long title   = insertTitle("TST-002", "2020-03-15");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(20, readAge(title, actress));
    }

    @Test
    void leapYearDob_releaseOnFeb28_is20() {
        // DOB 2000-02-29, release 2021-02-28 → 20 (day before where birthday would land)
        long actress = insertActress("C", "2000-02-29");
        long title   = insertTitle("TST-003", "2021-02-28");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(20, readAge(title, actress));
    }

    @Test
    void leapYearDob_releaseOnMar01_is21() {
        // DOB 2000-02-29, release 2021-03-01 → 21
        long actress = insertActress("D", "2000-02-29");
        long title   = insertTitle("TST-004", "2021-03-01");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(21, readAge(title, actress));
    }

    // -------------------------------------------------------------------------
    // Release-date precedence
    // -------------------------------------------------------------------------

    @Test
    void enrichmentReleaseDateTakesPrecedenceOverTitles() {
        // titles.release_date = 2015-01-01 but enrichment.release_date = 2020-01-01
        long actress = insertActress("E", "2000-01-01");
        long title   = insertTitle("TST-005", "2015-01-01");
        insertEnrichment(title, "2020-01-01");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(20, readAge(title, actress));  // uses enrichment year 2020
    }

    @Test
    void enrichmentReleaseDateEmptyFallsBackToTitles() {
        // enrichment row exists but release_date is '' → fall back to titles.release_date
        long actress = insertActress("F", "2000-01-01");
        long title   = insertTitle("TST-006", "2020-01-01");
        insertEnrichment(title, "");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(20, readAge(title, actress));  // uses titles year 2020
    }

    @Test
    void noEnrichmentRow_usesTitle() {
        // No enrichment row at all → uses titles.release_date
        long actress = insertActress("G", "2000-06-01");
        long title   = insertTitle("TST-007", "2022-06-01");
        credit(title, actress);

        recomputer.recomputeAll();

        assertEquals(22, readAge(title, actress));
    }

    // -------------------------------------------------------------------------
    // NULL handling
    // -------------------------------------------------------------------------

    @Test
    void emptyDob_yieldsNull() {
        long actress = insertActress("H", "");
        long title   = insertTitle("TST-008", "2020-01-01");
        credit(title, actress);

        recomputer.recomputeAll();

        assertNull(readAge(title, actress));
    }

    @Test
    void nullDob_yieldsNull() {
        long actress = insertActress("I", null);
        long title   = insertTitle("TST-009", "2020-01-01");
        credit(title, actress);

        recomputer.recomputeAll();

        assertNull(readAge(title, actress));
    }

    @Test
    void bothReleaseDatesNullOrEmpty_yieldsNull() {
        long actress = insertActress("J", "2000-01-01");
        long title   = insertTitle("TST-010", null);
        insertEnrichment(title, "");
        credit(title, actress);

        recomputer.recomputeAll();

        assertNull(readAge(title, actress));
    }

    // -------------------------------------------------------------------------
    // Self-healing / idempotency
    // -------------------------------------------------------------------------

    @Test
    void deletesDobAfterFirstCompute_clearsValue() {
        long actress = insertActress("K", "2000-01-01");
        long title   = insertTitle("TST-011", "2020-01-01");
        credit(title, actress);

        recomputer.recomputeAll();
        assertEquals(20, readAge(title, actress));

        // Now remove the DOB
        jdbi.useHandle(h -> h.execute("UPDATE actresses SET date_of_birth = NULL WHERE id = ?", actress));

        recomputer.recomputeAll();

        assertNull(readAge(title, actress));
    }

    @Test
    void multiCastTitle_eachCreditGetsOwnAge() {
        long a1 = insertActress("L1", "1990-01-01");
        long a2 = insertActress("L2", "2000-01-01");
        long title = insertTitle("TST-012", "2020-01-01");
        credit(title, a1);
        credit(title, a2);

        recomputer.recomputeAll();

        assertEquals(30, readAge(title, a1));
        assertEquals(20, readAge(title, a2));
    }

    @Test
    void secondRecomputeWithNoChange_reportsZeroChanged() {
        long actress = insertActress("M", "2000-01-01");
        long title   = insertTitle("TST-013", "2020-01-01");
        credit(title, actress);

        recomputer.recomputeAll();           // first pass — computes value
        int changed = recomputer.recomputeAll();  // second pass — nothing new

        assertEquals(0, changed);
    }

    // -------------------------------------------------------------------------
    // Schema: fresh DB via SchemaInitializer
    // -------------------------------------------------------------------------

    @Test
    void freshDb_columnExistsAndVersionIsCurrent() {
        boolean hasCol = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('title_actresses') WHERE name='age_at_release'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(hasCol, "age_at_release column should exist on a fresh install");

        int version = jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
        assertEquals(72, version);
    }

    // -------------------------------------------------------------------------
    // Migration: pre-V69 fixture
    // -------------------------------------------------------------------------

    @Test
    void migrationOnPreV69Fixture_addsColumnAndSeedsData() throws Exception {
        // Build a fresh connection where we simulate a pre-V69 state:
        // 1. Full schema initialization (gives us a V69 DB)
        // 2. Drop the column (SQLite doesn't support DROP COLUMN before 3.35; use a workaround)
        // 3. Reset version to 68
        // 4. Run SchemaUpgrader — should add column and seed data

        // Use a fresh separate connection for isolation
        try (Connection conn2 = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Jdbi jdbi2 = Jdbi.create(conn2);
            new SchemaInitializer(jdbi2).initialize();

            // Insert test data before the migration
            long actress2 = jdbi2.withHandle(h ->
                    h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at, date_of_birth) VALUES ('Mig', 'regular', '2020-01-01', '2000-01-01')")
                            .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
            long title2 = jdbi2.withHandle(h ->
                    h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num, release_date) VALUES ('MIG-001','MIG-001','MIG',1,'2020-01-01')")
                            .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
            jdbi2.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", title2, actress2));

            // Also insert an actress with no DOB (should remain NULL after seed)
            long actressNoDob = jdbi2.withHandle(h ->
                    h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('NoDob', 'regular', '2020-01-01')")
                            .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
            long titleNoDob = jdbi2.withHandle(h ->
                    h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num, release_date) VALUES ('MIG-002','MIG-002','MIG',2,'2020-01-01')")
                            .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
            jdbi2.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", titleNoDob, actressNoDob));

            // Reset to V68 so the upgrader applies V69
            jdbi2.useHandle(h -> h.execute("PRAGMA user_version = 68"));

            // Drop the column by recreating the table without it (SQLite constraint)
            jdbi2.useHandle(h -> {
                h.execute("ALTER TABLE title_actresses RENAME TO title_actresses_old");
                h.execute("CREATE TABLE title_actresses (title_id INTEGER NOT NULL REFERENCES titles(id), actress_id INTEGER NOT NULL REFERENCES actresses(id), PRIMARY KEY (title_id, actress_id))");
                h.execute("INSERT INTO title_actresses SELECT title_id, actress_id FROM title_actresses_old");
                h.execute("DROP TABLE title_actresses_old");
            });

            // Confirm column absent before migration
            boolean colAbsent = jdbi2.withHandle(h ->
                    h.createQuery("SELECT COUNT(*) FROM pragma_table_info('title_actresses') WHERE name='age_at_release'")
                            .mapTo(Integer.class).one() == 0);
            assertTrue(colAbsent, "column should be absent before migration");

            // Run upgrader
            new SchemaUpgrader(jdbi2).upgrade();

            // Column should now exist
            boolean colPresent = jdbi2.withHandle(h ->
                    h.createQuery("SELECT COUNT(*) FROM pragma_table_info('title_actresses') WHERE name='age_at_release'")
                            .mapTo(Integer.class).one() > 0);
            assertTrue(colPresent, "column should exist after migration");

            // Computable row should be seeded
            Integer age = jdbi2.withHandle(h ->
                    h.createQuery("SELECT age_at_release FROM title_actresses WHERE title_id = ? AND actress_id = ?")
                            .bind(0, title2).bind(1, actress2)
                            .mapTo(Integer.class).findOne().orElse(null));
            assertEquals(20, age, "seeded age should be 20 (DOB 2000, release 2020)");

            // Non-computable row should be NULL
            Integer ageNoDob = jdbi2.withHandle(h ->
                    h.createQuery("SELECT age_at_release FROM title_actresses WHERE title_id = ? AND actress_id = ?")
                            .bind(0, titleNoDob).bind(1, actressNoDob)
                            .mapTo(Integer.class).findOne().orElse(null));
            assertNull(ageNoDob, "row with no DOB should remain NULL after seed");

            // Re-run upgrade is a no-op (version already at current)
            int versionAfter = jdbi2.withHandle(h ->
                    h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
            assertEquals(72, versionAfter);

            new SchemaUpgrader(jdbi2).upgrade();  // should not throw
        }
    }

    // -------------------------------------------------------------------------
    // findImplausible
    // -------------------------------------------------------------------------

    @Test
    void findImplausible_returnsRowsOutsideRange() {
        // Age 17 → implausible
        long young  = insertActress("Young",  "2003-06-01");
        long title1 = insertTitle("IMP-001", "2020-06-01");   // age = 17
        credit(title1, young);

        // Age 71 → implausible
        long old    = insertActress("Old",    "1949-06-01");
        long title2 = insertTitle("IMP-002", "2020-06-01");   // age = 71
        credit(title2, old);

        // Age 25 → plausible
        long normal = insertActress("Normal", "1995-06-01");
        long title3 = insertTitle("IMP-003", "2020-06-01");   // age = 25
        credit(title3, normal);

        recomputer.recomputeAll();

        List<AgeAtReleaseRecomputer.ImplausibleRow> rows = recomputer.findImplausible();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.age() == 17));
        assertTrue(rows.stream().anyMatch(r -> r.age() == 71));
        assertTrue(rows.stream().noneMatch(r -> r.age() == 25));
    }
}
