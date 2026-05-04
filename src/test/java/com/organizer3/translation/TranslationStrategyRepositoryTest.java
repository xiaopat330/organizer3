package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository tests for {@link JdbiTranslationStrategyRepository} using real in-memory SQLite.
 */
class TranslationStrategyRepositoryTest {

    private JdbiTranslationStrategyRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiTranslationStrategyRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void findAllActive_emptyOnFreshSchema() {
        List<TranslationStrategy> active = repo.findAllActive();
        assertTrue(active.isEmpty());
    }

    @Test
    void insertAndFindByName() {
        TranslationStrategy s = new TranslationStrategy(0, "label_basic", "gemma4:e4b",
                "Translate: {jp}", "{\"temperature\":0.2}", true, null);
        long id = repo.insert(s);
        assertTrue(id > 0);

        Optional<TranslationStrategy> found = repo.findByName("label_basic");
        assertTrue(found.isPresent());
        assertEquals("label_basic", found.get().name());
        assertEquals("gemma4:e4b", found.get().modelId());
        assertEquals(id, found.get().id());
        assertTrue(found.get().isActive());
    }

    @Test
    void findAllActive_returnsOnlyActiveStrategies() {
        repo.insert(new TranslationStrategy(0, "active_one", "gemma4:e4b", "{jp}", null, true, null));
        repo.insert(new TranslationStrategy(0, "inactive_one", "qwen2.5:14b", "{jp}", null, false, null));

        List<TranslationStrategy> active = repo.findAllActive();
        assertEquals(1, active.size());
        assertEquals("active_one", active.get(0).name());
    }

    @Test
    void findById_returnsStrategy() {
        long id = repo.insert(new TranslationStrategy(0, "prose", "gemma4:e4b", "Translate {jp}", null, true, null));

        Optional<TranslationStrategy> found = repo.findById(id);
        assertTrue(found.isPresent());
        assertEquals("prose", found.get().name());
    }

    @Test
    void findByName_missingReturnsEmpty() {
        Optional<TranslationStrategy> found = repo.findByName("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    void insertPreservesOptionsJson() {
        String opts = "{\"temperature\":0.2,\"num_predict\":2048}";
        repo.insert(new TranslationStrategy(0, "s1", "gemma4:e4b", "{jp}", opts, true, null));

        TranslationStrategy found = repo.findByName("s1").orElseThrow();
        assertEquals(opts, found.optionsJson());
    }

    @Test
    void insertNullOptionsJson() {
        repo.insert(new TranslationStrategy(0, "s2", "gemma4:e4b", "{jp}", null, true, null));

        TranslationStrategy found = repo.findByName("s2").orElseThrow();
        assertNull(found.optionsJson());
    }
}
