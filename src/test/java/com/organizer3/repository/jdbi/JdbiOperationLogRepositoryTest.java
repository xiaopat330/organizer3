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
        OperationLogEntry entry = OperationLogEntry.builder()
                .timestamp(LocalDateTime.of(2025, 6, 1, 10, 0))
                .type(OperationType.MOVE)
                .sourcePath(Path.of("/queue/ABP-001"))
                .destPath(Path.of("/stars/library/Aya Sazanami/ABP-001"))
                .wasArmed(true)
                .build();

        repo.log(entry);

        List<OperationLogEntry> all = repo.findAll();
        assertEquals(1, all.size());
        OperationLogEntry found = all.get(0);
        assertNotNull(found.getId());
        assertEquals(OperationType.MOVE, found.getType());
        assertEquals(Path.of("/queue/ABP-001"), found.getSourcePath());
        assertEquals(Path.of("/stars/library/Aya Sazanami/ABP-001"), found.getDestPath());
        assertTrue(found.isWasArmed());
    }

    @Test
    void logWithNullDestPath() {
        OperationLogEntry entry = OperationLogEntry.builder()
                .timestamp(LocalDateTime.of(2025, 6, 1, 10, 0))
                .type(OperationType.CREATE_DIRECTORY)
                .sourcePath(Path.of("/stars/library/New Actress"))
                .wasArmed(false)
                .build();

        repo.log(entry);

        List<OperationLogEntry> all = repo.findAll();
        assertEquals(1, all.size());
        assertNull(all.get(0).getDestPath());
        assertFalse(all.get(0).isWasArmed());
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
        assertEquals(Path.of("/a"), all.get(0).getSourcePath());
        assertEquals(Path.of("/b"), all.get(1).getSourcePath());
        assertEquals(Path.of("/c"), all.get(2).getSourcePath());
    }

    // --- findSince ---

    @Test
    void findSinceReturnsOnlyEntriesAfterTimestamp() {
        repo.log(entry(LocalDateTime.of(2025, 6, 1, 0, 0), "/old"));
        repo.log(entry(LocalDateTime.of(2025, 6, 5, 0, 0), "/new1"));
        repo.log(entry(LocalDateTime.of(2025, 6, 10, 0, 0), "/new2"));

        List<OperationLogEntry> result = repo.findSince(LocalDateTime.of(2025, 6, 5, 0, 0));
        assertEquals(2, result.size());
        assertEquals(Path.of("/new1"), result.get(0).getSourcePath());
        assertEquals(Path.of("/new2"), result.get(1).getSourcePath());
    }

    @Test
    void findSinceReturnsEmptyWhenAllOlder() {
        repo.log(entry(LocalDateTime.of(2025, 1, 1, 0, 0), "/old"));

        assertTrue(repo.findSince(LocalDateTime.of(2025, 12, 1, 0, 0)).isEmpty());
    }

    // --- Helpers ---

    private OperationLogEntry entry(LocalDateTime timestamp, String sourcePath) {
        return OperationLogEntry.builder()
                .timestamp(timestamp).type(OperationType.MOVE)
                .sourcePath(Path.of(sourcePath)).wasArmed(true).build();
    }
}
