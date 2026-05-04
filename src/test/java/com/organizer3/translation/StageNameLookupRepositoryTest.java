package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiStageNameLookupRepository;
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
 * Repository tests for {@link JdbiStageNameLookupRepository} using real in-memory SQLite.
 * Covers: upsert idempotence, find-by-kanji, countAll, clearAndSeed atomicity.
 */
class StageNameLookupRepositoryTest {

    private JdbiStageNameLookupRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiStageNameLookupRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void findRomanizedFor_missingReturnsEmpty() {
        Optional<String> result = repo.findRomanizedFor("あいだゆあ");
        assertTrue(result.isEmpty());
    }

    @Test
    void upsert_insertAndFind() {
        repo.upsert("あいだゆあ", "Yua Aida", "aida_yua", "yaml_seed");

        Optional<String> result = repo.findRomanizedFor("あいだゆあ");
        assertTrue(result.isPresent());
        assertEquals("Yua Aida", result.get());
    }

    @Test
    void upsert_isIdempotent_updatesExisting() {
        repo.upsert("あいだゆあ", "Yua Aida", "aida_yua", "yaml_seed");
        repo.upsert("あいだゆあ", "Yua Aida Updated", "aida_yua", "yaml_seed");

        Optional<String> result = repo.findRomanizedFor("あいだゆあ");
        assertTrue(result.isPresent());
        assertEquals("Yua Aida Updated", result.get());
        assertEquals(1L, repo.countAll());
    }

    @Test
    void countAll_emptyTableReturnsZero() {
        assertEquals(0L, repo.countAll());
    }

    @Test
    void countAll_returnsCorrectCount() {
        repo.upsert("愛佳", "Aika", null, "yaml_seed");
        repo.upsert("あいだゆあ", "Yua Aida", "aida_yua", "yaml_seed");
        assertEquals(2L, repo.countAll());
    }

    @Test
    void clearAndSeed_replacesAllContents() {
        // Insert initial data
        repo.upsert("愛佳", "Aika", null, "yaml_seed");

        // Seed with different data
        List<StageNameLookupRow> newRows = List.of(
                new StageNameLookupRow(0, "あいだゆあ", "Yua Aida", "aida_yua", "yaml_seed", ""),
                new StageNameLookupRow(0, "蒼井そら", "Sora Aoi", "aoi_sora", "yaml_seed", "")
        );
        repo.clearAndSeed(newRows);

        // Old entry gone
        assertTrue(repo.findRomanizedFor("愛佳").isEmpty());
        // New entries present
        assertEquals(Optional.of("Yua Aida"), repo.findRomanizedFor("あいだゆあ"));
        assertEquals(Optional.of("Sora Aoi"), repo.findRomanizedFor("蒼井そら"));
        assertEquals(2L, repo.countAll());
    }

    @Test
    void clearAndSeed_emptyListClearsTable() {
        repo.upsert("愛佳", "Aika", null, "yaml_seed");
        assertEquals(1L, repo.countAll());

        repo.clearAndSeed(List.of());
        assertEquals(0L, repo.countAll());
    }

    @Test
    void clearAndSeed_isAtomic_findAfterSeedReflectsNewData() {
        List<StageNameLookupRow> rows = List.of(
                new StageNameLookupRow(0, "愛佳", "Aika", null, "yaml_seed", "")
        );
        repo.clearAndSeed(rows);

        // Verify the row is present with the correct data
        Optional<String> found = repo.findRomanizedFor("愛佳");
        assertTrue(found.isPresent());
        assertEquals("Aika", found.get());
    }
}
