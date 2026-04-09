package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.WatchHistory;
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
 * Integration tests for JdbiWatchHistoryRepository using an in-memory SQLite database.
 */
class JdbiWatchHistoryRepositoryTest {

    private JdbiWatchHistoryRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiWatchHistoryRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void recordAssignsId() {
        WatchHistory entry = repo.record("ABP-123", LocalDateTime.of(2025, 6, 1, 14, 30));
        assertNotNull(entry.getId());
        assertTrue(entry.getId() > 0);
        assertEquals("ABP-123", entry.getTitleCode());
        assertEquals(LocalDateTime.of(2025, 6, 1, 14, 30), entry.getWatchedAt());
    }

    @Test
    void findAllReturnsEntriesMostRecentFirst() {
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 1, 10, 0));
        repo.record("ABP-002", LocalDateTime.of(2025, 6, 2, 10, 0));
        repo.record("ABP-003", LocalDateTime.of(2025, 6, 3, 10, 0));

        List<WatchHistory> history = repo.findAll(10);
        assertEquals(3, history.size());
        assertEquals("ABP-003", history.get(0).getTitleCode());
        assertEquals("ABP-001", history.get(2).getTitleCode());
    }

    @Test
    void findAllRespectsLimit() {
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 1, 10, 0));
        repo.record("ABP-002", LocalDateTime.of(2025, 6, 2, 10, 0));
        repo.record("ABP-003", LocalDateTime.of(2025, 6, 3, 10, 0));

        List<WatchHistory> history = repo.findAll(2);
        assertEquals(2, history.size());
    }

    @Test
    void findByTitleCodeReturnsMatchingEntries() {
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 1, 10, 0));
        repo.record("ABP-002", LocalDateTime.of(2025, 6, 2, 10, 0));
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 3, 10, 0));

        List<WatchHistory> history = repo.findByTitleCode("ABP-001");
        assertEquals(2, history.size());
        assertTrue(history.stream().allMatch(e -> "ABP-001".equals(e.getTitleCode())));
    }

    @Test
    void findByTitleCodeReturnsEmptyWhenNoneExist() {
        assertTrue(repo.findByTitleCode("NONEXISTENT").isEmpty());
    }

    @Test
    void lastWatchedAtReturnsMostRecentTimestamp() {
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 1, 10, 0));
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 5, 14, 30));
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 3, 10, 0));

        Optional<LocalDateTime> last = repo.lastWatchedAt("ABP-001");
        assertTrue(last.isPresent());
        assertEquals(LocalDateTime.of(2025, 6, 5, 14, 30), last.get());
    }

    @Test
    void lastWatchedAtReturnsEmptyWhenNeverWatched() {
        assertTrue(repo.lastWatchedAt("NONEXISTENT").isEmpty());
    }

    @Test
    void deleteByTitleCodeRemovesAllEntriesForCode() {
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 1, 10, 0));
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 2, 10, 0));
        repo.record("ABP-002", LocalDateTime.of(2025, 6, 3, 10, 0));

        repo.deleteByTitleCode("ABP-001");

        assertTrue(repo.findByTitleCode("ABP-001").isEmpty());
        assertEquals(1, repo.findByTitleCode("ABP-002").size());
    }

    @Test
    void multipleWatchesOfSameTitleCreateSeparateEntries() {
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 1, 10, 0));
        repo.record("ABP-001", LocalDateTime.of(2025, 6, 2, 10, 0));

        List<WatchHistory> history = repo.findByTitleCode("ABP-001");
        assertEquals(2, history.size());
        assertNotEquals(history.get(0).getId(), history.get(1).getId());
    }
}
