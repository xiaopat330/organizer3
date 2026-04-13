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
        // Tags must exist before label_tags rows can be inserted (FK reference)
        new TagSeeder(jdbi).seedIfEmpty();
        seeder = new LabelSeeder(jdbi, new TitleEffectiveTagsService(jdbi));
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
        long firstTagCount = countLabelTags();

        seeder.seedIfEmpty();
        long secondCount = countLabels();
        long secondTagCount = countLabelTags();

        assertEquals(firstCount, secondCount, "seedIfEmpty should not insert duplicate labels");
        assertEquals(firstTagCount, secondTagCount, "seedIfEmpty should not insert duplicate label_tags");
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
    void seedIfEmptyStoresCompanyProfileFields() {
        seeder.seedIfEmpty();

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM labels WHERE code = 'MIDA'")
                        .mapToMap()
                        .findOne()
                        .orElseThrow()
        );

        // Moodyz has a profile with founded, status, parent, specialty
        assertNotNull(row.get("company_specialty"), "Expected company_specialty to be populated");
        assertNotNull(row.get("company_founded"),   "Expected company_founded to be populated");
        assertNotNull(row.get("company_status"),    "Expected company_status to be populated");
        assertNotNull(row.get("company_parent"),    "Expected company_parent to be populated");
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

    @Test
    void seedIfEmptyPopulatesLabelTags() {
        seeder.seedIfEmpty();

        long tagCount = countLabelTags();
        assertTrue(tagCount > 1000, "Expected 1000+ label_tag rows, got " + tagCount);
    }

    @Test
    void seedIfEmptyStoresCorrectTagsForKnownLabel() {
        seeder.seedIfEmpty();

        // MIFD is Moody's Fresh — debut/newcomer label
        List<String> tags = jdbi.withHandle(h ->
                h.createQuery("SELECT tag FROM label_tags WHERE label_code = 'MIFD' ORDER BY tag")
                        .mapTo(String.class)
                        .list()
        );

        assertTrue(tags.contains("debut"),      "MIFD should have 'debut' tag");
        assertTrue(tags.contains("newcomer"),   "MIFD should have 'newcomer' tag");
        assertTrue(tags.contains("premium-production"), "MIFD should have 'premium-production' tag");
    }

    @Test
    void seedIfEmptyTriggersReimportWhenLabelTagsEmpty() {
        // Seed labels normally, then clear label_tags
        seeder.seedIfEmpty();
        jdbi.useHandle(h -> h.execute("DELETE FROM label_tags"));
        assertEquals(0, countLabelTags());

        // seedIfEmpty should detect empty label_tags and reimport
        seeder.seedIfEmpty();
        assertTrue(countLabelTags() > 0, "seedIfEmpty should repopulate label_tags");
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
    void reimportClearsAndRepopulatesLabelTags() {
        seeder.seedIfEmpty();
        long originalTagCount = countLabelTags();

        // Corrupt label_tags
        jdbi.useHandle(h -> h.execute("DELETE FROM label_tags"));

        seeder.reimport();

        assertEquals(originalTagCount, countLabelTags(), "reimport should restore label_tags");
    }

    @Test
    void reimportOnEmptyTableWorks() {
        seeder.reimport();
        assertTrue(countLabels() > 0);
        assertTrue(countLabelTags() > 0);
    }

    // --- helpers ---

    private long countLabels() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM labels")
                        .mapTo(Long.class)
                        .one()
        );
    }

    private long countLabelTags() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM label_tags")
                        .mapTo(Long.class)
                        .one()
        );
    }
}
