package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Label;
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
 * Integration tests for JdbiLabelRepository using an in-memory SQLite database.
 */
class JdbiLabelRepositoryTest {

    private JdbiLabelRepository repo;
    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiLabelRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void findAllAsMapReturnsEmptyWhenNoLabels() {
        assertTrue(repo.findAllAsMap().isEmpty());
    }

    @Test
    void findAllAsMapReturnsLabelsKeyedByUppercaseCode() {
        insertLabel("abp", "Prestige", "Prestige International");
        insertLabel("MIDE", "Moody's Diva", "Moodyz");

        Map<String, Label> labels = repo.findAllAsMap();
        assertEquals(2, labels.size());
        assertTrue(labels.containsKey("ABP"));
        assertTrue(labels.containsKey("MIDE"));
        assertEquals("Prestige", labels.get("ABP").labelName());
        assertEquals("Moodyz", labels.get("MIDE").company());
    }

    @Test
    void findAllAsMapKeepsFirstOnDuplicateCode() {
        insertLabel("ABP", "First", "Company A");
        insertLabel("abp", "Second", "Company B");

        Map<String, Label> labels = repo.findAllAsMap();
        assertEquals(1, labels.size());
        assertEquals("First", labels.get("ABP").labelName());
    }

    @Test
    void findAllAsMapReturnsEmptyTagListWhenNoTagsPresent() {
        insertLabel("ABP", "Prestige", "Prestige International");

        Label label = repo.findAllAsMap().get("ABP");
        assertNotNull(label);
        assertNotNull(label.tags());
        assertTrue(label.tags().isEmpty(), "Tags should be empty when no label_tags rows exist");
    }

    @Test
    void findAllAsMapReturnsTagsForLabel() {
        insertLabelWithTags("ABP", "Prestige", "Prestige International",
                List.of("exclusive-actress", "solo-actress"));

        Label label = repo.findAllAsMap().get("ABP");
        assertNotNull(label);
        assertEquals(2, label.tags().size());
        assertTrue(label.tags().contains("exclusive-actress"));
        assertTrue(label.tags().contains("solo-actress"));
    }

    @Test
    void findAllAsMapHandlesMultipleLabelsWithDifferentTags() {
        insertLabelWithTags("MIDE", "Moody's Diva", "Moodyz",
                List.of("premium-production", "exclusive-actress"));
        insertLabelWithTags("MIFD", "Moody's Fresh", "Moodyz",
                List.of("premium-production", "debut", "newcomer"));

        Map<String, Label> labels = repo.findAllAsMap();
        assertEquals(2, labels.size());

        Label mide = labels.get("MIDE");
        assertTrue(mide.tags().contains("premium-production"));
        assertTrue(mide.tags().contains("exclusive-actress"));
        assertFalse(mide.tags().contains("debut"));

        Label mifd = labels.get("MIFD");
        assertTrue(mifd.tags().contains("debut"));
        assertTrue(mifd.tags().contains("newcomer"));
    }

    @Test
    void findAllAsMapReturnsProfileFields() {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO labels (code, label_name, company, description,
                                            company_specialty, company_founded, company_status, company_parent)
                        VALUES ('MIDA', 'Moody''s Diva', 'Moodyz', 'regular',
                                'flagship specialty', '2000', 'active', 'WILL Co., Ltd.')
                        """).execute());

        Label label = repo.findAllAsMap().get("MIDA");
        assertNotNull(label);
        assertEquals("flagship specialty", label.companySpecialty());
        assertEquals("2000", label.companyFounded());
        assertEquals("active", label.companyStatus());
        assertEquals("WILL Co., Ltd.", label.companyParent());
    }

    // --- Helpers ---

    private void insertLabel(String code, String labelName, String company) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO labels (code, label_name, company) VALUES (:code, :name, :company)")
                .bind("code", code)
                .bind("name", labelName)
                .bind("company", company)
                .execute());
    }

    private void insertLabelWithTags(String code, String labelName, String company, List<String> tags) {
        insertLabel(code, labelName, company);
        for (String tag : tags) {
            // Insert tag reference first (FK constraint)
            jdbi.useHandle(h -> h.createUpdate(
                            "INSERT OR IGNORE INTO tags (name, category) VALUES (:name, 'test')")
                    .bind("name", tag)
                    .execute());
            jdbi.useHandle(h -> h.createUpdate(
                            "INSERT INTO label_tags (label_code, tag) VALUES (:code, :tag)")
                    .bind("code", code)
                    .bind("tag", tag)
                    .execute());
        }
    }
}
