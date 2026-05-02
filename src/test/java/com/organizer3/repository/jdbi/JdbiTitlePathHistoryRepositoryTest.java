package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.TitlePathHistoryEntry;
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
 * Tests for {@link JdbiTitlePathHistoryRepository}.
 *
 * <p>Uses real in-memory SQLite via {@link SchemaInitializer}.
 * Covers upsert semantics, path lookup (with title-existence guard), list ordering,
 * and survival of rows after title deletion (no FK constraint).
 */
class JdbiTitlePathHistoryRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitlePathHistoryRepository repo;

    private static final String VOL = "vol-a";
    private static final String PART = "queue";
    private static final String PATH1 = "/queue/ABC-001";
    private static final String PATH2 = "/queue/ABC-002";
    private static final String T1 = "2026-01-01T00:00:00Z";
    private static final String T2 = "2026-01-02T00:00:00Z";
    private static final String T3 = "2026-01-03T00:00:00Z";

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiTitlePathHistoryRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── recordPath: insert on first call ──────────────────────────────────────

    @Test
    void recordPath_insertsRowOnFirstCall() {
        repo.recordPath(42L, VOL, PART, PATH1, T1);

        List<TitlePathHistoryEntry> rows = repo.listForTitle(42L);
        assertEquals(1, rows.size());
        TitlePathHistoryEntry row = rows.get(0);
        assertEquals(42L, row.getTitleId());
        assertEquals(VOL, row.getVolumeId());
        assertEquals(PART, row.getPartitionId());
        assertEquals(PATH1, row.getPath());
        assertEquals(T1, row.getFirstSeenAt());
        assertEquals(T1, row.getLastSeenAt());
    }

    // ── recordPath: upsert updates last_seen_at, preserves first_seen_at ──────

    @Test
    void recordPath_updatesLastSeenAtOnConflict_preservesFirstSeenAt() {
        repo.recordPath(42L, VOL, PART, PATH1, T1);
        repo.recordPath(42L, VOL, PART, PATH1, T2);

        List<TitlePathHistoryEntry> rows = repo.listForTitle(42L);
        assertEquals(1, rows.size(), "upsert must not create a second row");
        assertEquals(T1, rows.get(0).getFirstSeenAt(), "first_seen_at must be preserved");
        assertEquals(T2, rows.get(0).getLastSeenAt(),  "last_seen_at must be bumped");
    }

    @Test
    void recordPath_differentPathsCreateSeparateRows() {
        repo.recordPath(42L, VOL, PART, PATH1, T1);
        repo.recordPath(42L, VOL, PART, PATH2, T2);

        List<TitlePathHistoryEntry> rows = repo.listForTitle(42L);
        assertEquals(2, rows.size(), "distinct paths must produce distinct rows");
    }

    @Test
    void recordPath_differentVolumesSamePathCreateSeparateRows() {
        repo.recordPath(42L, "vol-a", PART, PATH1, T1);
        repo.recordPath(42L, "vol-b", PART, PATH1, T1);

        List<TitlePathHistoryEntry> rows = repo.listForTitle(42L);
        assertEquals(2, rows.size(), "same path on different volumes must be separate rows");
    }

    // ── findByPath: returns title_id; respects title-existence guard ──────────

    @Test
    void findByPath_returnsEmptyWhenNoHistoryRow() {
        Optional<Long> result = repo.findByPath(VOL, PART, PATH1);
        assertTrue(result.isEmpty(), "must return empty when no history row exists");
    }

    @Test
    void findByPath_returnsEmptyWhenTitleIdNotInTitles() {
        // Record path for a title_id that has no titles row — stale row after deletion
        repo.recordPath(999L, VOL, PART, PATH1, T1);

        Optional<Long> result = repo.findByPath(VOL, PART, PATH1);
        assertTrue(result.isEmpty(),
                "must return empty when title_id no longer exists in titles table");
    }

    @Test
    void findByPath_returnsTitleIdWhenTitleExists() {
        long titleId = insertTitle("ABC-001");
        repo.recordPath(titleId, VOL, PART, PATH1, T1);

        Optional<Long> result = repo.findByPath(VOL, PART, PATH1);
        assertTrue(result.isPresent());
        assertEquals(titleId, result.get());
    }

    @Test
    void findByPath_returnsEmptyForWrongVolume() {
        long titleId = insertTitle("ABC-001");
        repo.recordPath(titleId, "vol-a", PART, PATH1, T1);

        Optional<Long> result = repo.findByPath("vol-b", PART, PATH1);
        assertTrue(result.isEmpty(), "path on a different volume must not match");
    }

    @Test
    void findByPath_returnsEmptyForWrongPartition() {
        long titleId = insertTitle("ABC-001");
        repo.recordPath(titleId, VOL, "queue", PATH1, T1);

        Optional<Long> result = repo.findByPath(VOL, "attention", PATH1);
        assertTrue(result.isEmpty(), "path in a different partition must not match");
    }

    // ── listForTitle: ordering by last_seen_at DESC ───────────────────────────

    @Test
    void listForTitle_returnsRowsInLastSeenAtDescOrder() {
        long titleId = insertTitle("ABC-001");
        repo.recordPath(titleId, VOL, PART, PATH1, T1);
        repo.recordPath(titleId, VOL, PART, PATH2, T3);

        List<TitlePathHistoryEntry> rows = repo.listForTitle(titleId);
        assertEquals(2, rows.size());
        // Most recently seen first
        assertEquals(PATH2, rows.get(0).getPath(), "row with later last_seen_at must come first");
        assertEquals(PATH1, rows.get(1).getPath());
    }

    @Test
    void listForTitle_returnsEmptyForUnknownTitleId() {
        List<TitlePathHistoryEntry> rows = repo.listForTitle(9999L);
        assertTrue(rows.isEmpty());
    }

    // ── survives title deletion: no FK ────────────────────────────────────────

    @Test
    void recordedRow_survivesAfterTitleDeletion() {
        long titleId = insertTitle("ABC-001");
        repo.recordPath(titleId, VOL, PART, PATH1, T1);

        // Delete the title — no FK, so the path_history row must survive.
        jdbi.useHandle(h -> h.execute("DELETE FROM titles WHERE id = ?", titleId));

        // listForTitle still returns the row (direct table query, no title existence filter).
        List<TitlePathHistoryEntry> rows = repo.listForTitle(titleId);
        assertEquals(1, rows.size(), "path_history row must survive title deletion");
        assertEquals(titleId, rows.get(0).getTitleId());

        // findByPath returns empty because the title no longer exists.
        Optional<Long> byPath = repo.findByPath(VOL, PART, PATH1);
        assertTrue(byPath.isEmpty(), "findByPath must return empty for a deleted title_id");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (:code, :code, 'ABC', 1)")
                        .bind("code", code)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }
}
