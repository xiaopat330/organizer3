package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LabelSeeder using an in-memory SQLite database.
 */
class LabelSeederTest {

    private LabelSeeder seeder;
    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        seeder = new LabelSeeder(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- seedIfEmpty ---

    @Test
    void seedIfEmptyPopulatesEmptyTable() {
        seeder.seedIfEmpty();
        long count = countLabels();
        assertTrue(count > 800, "Expected 800+ label rows, got " + count);
    }

    @Test
    void seedIfEmptyIsIdempotent() {
        seeder.seedIfEmpty();
        long firstCount = countLabels();

        seeder.seedIfEmpty();
        long secondCount = countLabels();

        assertEquals(firstCount, secondCount, "seedIfEmpty should not insert duplicates");
    }

    @Test
    void seedIfEmptyStoresCorrectFields() {
        seeder.seedIfEmpty();

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM labels WHERE code = 'MIDA'")
                        .mapToMap()
                        .findOne()
                        .orElse(null)
        );

        assertNotNull(row, "Expected row for code MIDA");
        assertEquals("Moody's Diva", row.get("label_name"));
        assertEquals("Moodyz", row.get("company"));
        assertNotNull(row.get("description"));
    }

    @Test
    void seedIfEmptyAssignsCorrectCompany() {
        seeder.seedIfEmpty();

        // PGD (glamourous) should be under PREMIUM, not WILL
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM labels WHERE code = 'PGD'")
                        .mapToMap()
                        .findOne()
                        .orElse(null)
        );

        assertNotNull(row, "Expected row for code PGD");
        assertEquals("PREMIUM", row.get("company"));
    }

    // --- reimport ---

    @Test
    void reimportClearsAndRepopulates() {
        seeder.seedIfEmpty();
        long originalCount = countLabels();

        // Manually corrupt a row
        jdbi.useHandle(h -> h.execute("UPDATE labels SET label_name = 'WRONG' WHERE code = 'MIDA'"));

        seeder.reimport();

        long afterCount = countLabels();
        assertEquals(originalCount, afterCount, "Row count should be the same after reimport");

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM labels WHERE code = 'MIDA'")
                        .mapToMap()
                        .findOne()
                        .orElseThrow()
        );
        assertEquals("Moody's Diva", row.get("label_name"), "reimport should restore correct data");
    }

    @Test
    void reimportOnEmptyTableWorks() {
        seeder.reimport();
        assertTrue(countLabels() > 0);
    }

    // --- helper ---

    private long countLabels() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM labels")
                        .mapTo(Long.class)
                        .one()
        );
    }
}
