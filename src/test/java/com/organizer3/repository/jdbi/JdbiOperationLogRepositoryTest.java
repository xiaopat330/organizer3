package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.OperationLogEntry;
import com.organizer3.model.OperationLogEntry.OperationType;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiOperationLogRepository using an in-memory SQLite database.
 */
class JdbiOperationLogRepositoryTest {

    private JdbiOperationLogRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiOperationLogRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- log / findAll ---

    @Test
    void logAndFindAll() {
        OperationLogEntry entry = new OperationLogEntry(
                null,
                LocalDateTime.of(2025, 6, 1, 10, 0),
                OperationType.MOVE,
                Path.of("/queue/ABP-001"),
                Path.of("/stars/library/Aya Sazanami/ABP-001"),
                true
        );

        repo.log(entry);

        List<OperationLogEntry> all = repo.findAll();
        assertEquals(1, all.size());
        OperationLogEntry found = all.get(0);
        assertNotNull(found.id());
        assertEquals(OperationType.MOVE, found.type());
        assertEquals(Path.of("/queue/ABP-001"), found.sourcePath());
        assertEquals(Path.of("/stars/library/Aya Sazanami/ABP-001"), found.destPath());
        assertTrue(found.wasArmed());
    }

    @Test
    void logWithNullDestPath() {
        OperationLogEntry entry = new OperationLogEntry(
                null,
                LocalDateTime.of(2025, 6, 1, 10, 0),
                OperationType.CREATE_DIRECTORY,
                Path.of("/stars/library/New Actress"),
                null,
                false
        );

        repo.log(entry);

        List<OperationLogEntry> all = repo.findAll();
        assertEquals(1, all.size());
        assertNull(all.get(0).destPath());
        assertFalse(all.get(0).wasArmed());
    }

    @Test
    void findAllReturnsEmptyWhenNoEntries() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void findAllOrderedByTimestamp() {
        repo.log(entry(LocalDateTime.of(2025, 6, 3, 0, 0), "/c"));
        repo.log(entry(LocalDateTime.of(2025, 6, 1, 0, 0), "/a"));
        repo.log(entry(LocalDateTime.of(2025, 6, 2, 0, 0), "/b"));

        List<OperationLogEntry> all = repo.findAll();
        assertEquals(3, all.size());
        assertEquals(Path.of("/a"), all.get(0).sourcePath());
        assertEquals(Path.of("/b"), all.get(1).sourcePath());
        assertEquals(Path.of("/c"), all.get(2).sourcePath());
    }

    // --- findSince ---

    @Test
    void findSinceReturnsOnlyEntriesAfterTimestamp() {
        repo.log(entry(LocalDateTime.of(2025, 6, 1, 0, 0), "/old"));
        repo.log(entry(LocalDateTime.of(2025, 6, 5, 0, 0), "/new1"));
        repo.log(entry(LocalDateTime.of(2025, 6, 10, 0, 0), "/new2"));

        List<OperationLogEntry> result = repo.findSince(LocalDateTime.of(2025, 6, 5, 0, 0));
        assertEquals(2, result.size());
        assertEquals(Path.of("/new1"), result.get(0).sourcePath());
        assertEquals(Path.of("/new2"), result.get(1).sourcePath());
    }

    @Test
    void findSinceReturnsEmptyWhenAllOlder() {
        repo.log(entry(LocalDateTime.of(2025, 1, 1, 0, 0), "/old"));

        assertTrue(repo.findSince(LocalDateTime.of(2025, 12, 1, 0, 0)).isEmpty());
    }

    // --- Helpers ---

    private OperationLogEntry entry(LocalDateTime timestamp, String sourcePath) {
        return new OperationLogEntry(null, timestamp, OperationType.MOVE,
                Path.of(sourcePath), null, true);
    }
}
