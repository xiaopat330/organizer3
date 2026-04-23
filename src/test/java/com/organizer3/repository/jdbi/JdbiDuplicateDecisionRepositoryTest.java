package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.DuplicateDecision;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbiDuplicateDecisionRepositoryTest {

    private JdbiDuplicateDecisionRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiDuplicateDecisionRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private DuplicateDecision decision(String titleCode, String volumeId, String nasPath, String decision) {
        return DuplicateDecision.builder()
                .titleCode(titleCode)
                .volumeId(volumeId)
                .nasPath(nasPath)
                .decision(decision)
                .createdAt(Instant.now().toString())
                .build();
    }

    @Test
    void upsertAndListPending() {
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "KEEP"));
        repo.upsert(decision("ABP-001", "vol-b", "/vol/queue/ABP-001", "TRASH"));

        List<DuplicateDecision> pending = repo.listPending();
        assertEquals(2, pending.size());
    }

    @Test
    void listPendingOrdersByKey() {
        repo.upsert(decision("ZZZ-999", "vol-a", "/z", "KEEP"));
        repo.upsert(decision("AAA-001", "vol-a", "/a", "TRASH"));

        List<DuplicateDecision> pending = repo.listPending();
        assertEquals("AAA-001", pending.get(0).getTitleCode());
        assertEquals("ZZZ-999", pending.get(1).getTitleCode());
    }

    @Test
    void upsertUpdatesDecisionPreservesCreatedAt() throws InterruptedException {
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "KEEP"));
        String firstCreatedAt = repo.listPending().get(0).getCreatedAt();

        Thread.sleep(10);
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "TRASH"));

        List<DuplicateDecision> pending = repo.listPending();
        assertEquals(1, pending.size(), "Upsert should not create a duplicate row");
        assertEquals("TRASH", pending.get(0).getDecision());
        assertEquals(firstCreatedAt, pending.get(0).getCreatedAt(), "created_at must not change on update");
    }

    @Test
    void deleteRemovesSpecificRow() {
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "KEEP"));
        repo.upsert(decision("ABP-001", "vol-b", "/vol/queue/ABP-001", "TRASH"));

        repo.delete("ABP-001", "vol-a", "/vol/stars/ABP-001");

        List<DuplicateDecision> pending = repo.listPending();
        assertEquals(1, pending.size());
        assertEquals("vol-b", pending.get(0).getVolumeId());
    }

    @Test
    void deleteIsNoOpWhenNotFound() {
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "KEEP"));

        assertDoesNotThrow(() -> repo.delete("ABP-001", "vol-a", "/nonexistent"));

        assertEquals(1, repo.listPending().size());
    }

    @Test
    void deleteScopedToFullPrimaryKey() {
        repo.upsert(decision("ABP-001", "vol-a", "/path/one", "KEEP"));
        repo.upsert(decision("ABP-001", "vol-a", "/path/two", "TRASH"));

        // Delete by (titleCode, volumeId) but wrong nasPath → nothing removed
        repo.delete("ABP-001", "vol-a", "/path/three");
        assertEquals(2, repo.listPending().size());

        // Delete correct nasPath
        repo.delete("ABP-001", "vol-a", "/path/one");
        assertEquals(1, repo.listPending().size());
        assertEquals("/path/two", repo.listPending().get(0).getNasPath());
    }

    @Test
    void listPendingExcludesExecutedRows() {
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "KEEP"));

        // Manually mark as executed
        Jdbi jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute(
                "UPDATE duplicate_decisions SET executed_at = ? WHERE title_code = ?",
                Instant.now().toString(), "ABP-001"));

        assertTrue(repo.listPending().isEmpty(), "Executed decisions must not appear in listPending");
    }

    @Test
    void executedAtIsNullOnCreate() {
        repo.upsert(decision("ABP-001", "vol-a", "/vol/stars/ABP-001", "KEEP"));
        assertNull(repo.listPending().get(0).getExecutedAt());
    }
}
