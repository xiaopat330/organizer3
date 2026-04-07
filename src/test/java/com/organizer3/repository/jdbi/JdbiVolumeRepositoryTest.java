package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Volume;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiVolumeRepository using an in-memory SQLite database.
 */
class JdbiVolumeRepositoryTest {

    private JdbiVolumeRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiVolumeRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- save / findById ---

    @Test
    void saveAndFindById() {
        repo.save(new Volume("a", "conventional"));

        Optional<Volume> found = repo.findById("a");
        assertTrue(found.isPresent());
        assertEquals("a", found.get().getId());
        assertEquals("conventional", found.get().getStructureType());
        assertNull(found.get().getLastSyncedAt());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        assertTrue(repo.findById("nonexistent").isEmpty());
    }

    @Test
    void saveIsUpsert() {
        repo.save(new Volume("a", "conventional"));
        repo.save(new Volume("a", "queue"));

        Optional<Volume> found = repo.findById("a");
        assertTrue(found.isPresent());
        assertEquals("queue", found.get().getStructureType());
    }

    @Test
    void savePreservesLastSyncedAt() {
        Volume vol = new Volume("a", "conventional");
        vol.setLastSyncedAt(LocalDateTime.of(2025, 3, 15, 10, 30));
        repo.save(vol);

        Optional<Volume> found = repo.findById("a");
        assertTrue(found.isPresent());
        assertEquals(LocalDateTime.of(2025, 3, 15, 10, 30), found.get().getLastSyncedAt());
    }

    // --- findAll ---

    @Test
    void findAllReturnsAllVolumesOrderedById() {
        repo.save(new Volume("c", "queue"));
        repo.save(new Volume("a", "conventional"));
        repo.save(new Volume("b", "exhibition"));

        List<Volume> all = repo.findAll();
        assertEquals(3, all.size());
        assertEquals("a", all.get(0).getId());
        assertEquals("b", all.get(1).getId());
        assertEquals("c", all.get(2).getId());
    }

    @Test
    void findAllReturnsEmptyWhenNoVolumes() {
        assertTrue(repo.findAll().isEmpty());
    }

    // --- updateLastSyncedAt ---

    @Test
    void updateLastSyncedAtSetsTimestamp() {
        repo.save(new Volume("a", "conventional"));
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);

        repo.updateLastSyncedAt("a", now);

        Optional<Volume> found = repo.findById("a");
        assertTrue(found.isPresent());
        assertEquals(now, found.get().getLastSyncedAt());
    }

    @Test
    void updateLastSyncedAtOverwritesPreviousTimestamp() {
        repo.save(new Volume("a", "conventional"));
        repo.updateLastSyncedAt("a", LocalDateTime.of(2025, 1, 1, 0, 0));
        repo.updateLastSyncedAt("a", LocalDateTime.of(2025, 6, 1, 12, 0));

        Optional<Volume> found = repo.findById("a");
        assertEquals(LocalDateTime.of(2025, 6, 1, 12, 0), found.get().getLastSyncedAt());
    }
}
