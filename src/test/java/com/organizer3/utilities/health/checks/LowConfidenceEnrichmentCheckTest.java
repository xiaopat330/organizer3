package com.organizer3.utilities.health.checks;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class LowConfidenceEnrichmentCheckTest {

    private Connection connection;
    private Jdbi jdbi;
    private LowConfidenceEnrichmentCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        // Seed a title row so FK holds when inserting enrichment rows.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (1, 'ABP-001', 'ABP-001', 'ABP', 1)"));
        check = new LowConfidenceEnrichmentCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void reportsZeroWhenNoEnrichmentRows() {
        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void reportsZeroWhenOnlyHighConfidence() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence) " +
                "VALUES (1, 'abc123', '2024-01-01', 'HIGH')"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
    }

    @Test
    void reportsZeroWhenOnlyUnknownConfidence() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence) " +
                "VALUES (1, 'abc123', '2024-01-01', 'UNKNOWN')"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total(), "UNKNOWN is steady-state and must not be flagged");
    }

    @Test
    void detectsLowConfidenceRow() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence, resolver_source) " +
                "VALUES (1, 'xyz999', '2024-01-01', 'LOW', 'actress_filmography')"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        LibraryHealthCheck.Finding f = result.rows().get(0);
        assertEquals("ABP-001", f.label());
        assertTrue(f.detail().contains("slug=xyz999"));
        assertTrue(f.detail().contains("src=actress_filmography"));
    }

    @Test
    void detailOmitsSrcWhenResolverSourceIsNull() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence) " +
                "VALUES (1, 'xyz999', '2024-01-01', 'LOW')"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertFalse(result.rows().get(0).detail().contains("src="), "src= must be omitted when resolver_source is null");
    }

    @Test
    void capsAtFiftyRows() {
        // Insert a seed title for each enrichment row (FKs).
        jdbi.useHandle(h -> {
            for (int i = 2; i <= 101; i++) {
                h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (?, ?, ?, 'ABP', 1)",
                        i, "ABP-" + String.format("%03d", i), "ABP-" + String.format("%03d", i));
                h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence) " +
                          "VALUES (?, 'slug" + i + "', '2024-01-01', 'LOW')", i);
            }
        });

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(100, result.total());
        assertEquals(50, result.rows().size());
    }

    /**
     * Rule 3 false-positive guard: LOW is flagged; HIGH is not. Mixed table must only surface
     * the LOW row.
     */
    @Test
    void highConfidenceRowIsNotFlaggedAlongsideLow() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (2, 'ABP-002', 'ABP-002', 'ABP', 2)");
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence) " +
                      "VALUES (1, 'low-slug', '2024-01-01', 'LOW')");
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, confidence) " +
                      "VALUES (2, 'high-slug', '2024-01-01', 'HIGH')");
        });

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total(), "only the LOW row should be counted");
        assertEquals("ABP-001", result.rows().get(0).label());
    }
}
