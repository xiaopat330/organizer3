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
 * Tests for TagSeeder using an in-memory SQLite database.
 */
class TagSeederTest {

    private TagSeeder seeder;
    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        seeder = new TagSeeder(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- seedIfEmpty ---

    @Test
    void seedIfEmptyPopulatesEmptyTable() {
        seeder.seedIfEmpty();
        long count = countTags();
        assertTrue(count > 50, "Expected 50+ tag rows, got " + count);
    }

    @Test
    void seedIfEmptyIsIdempotent() {
        seeder.seedIfEmpty();
        long first = countTags();

        seeder.seedIfEmpty();
        long second = countTags();

        assertEquals(first, second, "seedIfEmpty should not insert duplicates");
    }

    @Test
    void seedIfEmptyStoresCorrectFields() {
        seeder.seedIfEmpty();

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM tags WHERE name = 'creampie'")
                        .mapToMap()
                        .findOne()
                        .orElse(null)
        );

        assertNotNull(row, "Expected tag row for 'creampie'");
        assertEquals("act", row.get("category"));
        assertNotNull(row.get("description"));
    }

    @Test
    void seedIfEmptyAssignsCorrectCategories() {
        seeder.seedIfEmpty();

        // spot-check category assignments across the taxonomy
        assertCategory("compilation",   "format");
        assertCategory("drama",         "production_style");
        assertCategory("soapland",      "setting");
        assertCategory("nurse",         "role");
        assertCategory("affair",        "theme");
        assertCategory("creampie",      "act");
        assertCategory("busty",         "body");
    }

    @Test
    void seedIfEmptySkipsWhenAlreadyPopulated() {
        seeder.seedIfEmpty();
        long first = countTags();

        // Insert a dummy extra tag to detect if a second seed runs
        jdbi.useHandle(h -> h.execute("INSERT INTO tags (name, category) VALUES ('dummy-test-tag', 'test')"));

        seeder.seedIfEmpty();
        long second = countTags();

        // seedIfEmpty should skip (table was not empty), so dummy tag persists
        assertEquals(first + 1, second, "seedIfEmpty should not clear the table when already populated");
    }

    // --- reimport ---

    @Test
    void reimportClearsAndRepopulates() {
        seeder.seedIfEmpty();
        long original = countTags();

        jdbi.useHandle(h -> h.execute("UPDATE tags SET description = 'WRONG' WHERE name = 'creampie'"));

        seeder.reimport();

        assertEquals(original, countTags(), "Row count should be the same after reimport");

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM tags WHERE name = 'creampie'")
                        .mapToMap()
                        .findOne()
                        .orElseThrow()
        );
        assertNotEquals("WRONG", row.get("description"), "reimport should restore correct description");
    }

    @Test
    void reimportOnEmptyTableWorks() {
        seeder.reimport();
        assertTrue(countTags() > 0);
    }

    // --- helpers ---

    private long countTags() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM tags")
                        .mapTo(Long.class)
                        .one()
        );
    }

    private void assertCategory(String tagName, String expectedCategory) {
        String actual = jdbi.withHandle(h ->
                h.createQuery("SELECT category FROM tags WHERE name = :name")
                        .bind("name", tagName)
                        .mapTo(String.class)
                        .findOne()
                        .orElseThrow(() -> new AssertionError("Tag not found: " + tagName))
        );
        assertEquals(expectedCategory, actual,
                "Tag '" + tagName + "' should be in category '" + expectedCategory + "'");
    }
}
