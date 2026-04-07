package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Label;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
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

    // --- Helpers ---

    private void insertLabel(String code, String labelName, String company) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO labels (code, label_name, company) VALUES (:code, :name, :company)")
                .bind("code", code)
                .bind("name", labelName)
                .bind("company", company)
                .execute());
    }
}
