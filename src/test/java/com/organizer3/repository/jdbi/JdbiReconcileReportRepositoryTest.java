package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.ReconcileReportRepository.PersistedReport;
import com.organizer3.sync.ReconcileReport;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link JdbiReconcileReportRepository} (schema v55).
 */
class JdbiReconcileReportRepositoryTest {

    private Connection connection;
    private JdbiReconcileReportRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiReconcileReportRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void save_thenFindById_returnsAllFields() {
        ReconcileReport report = sampleReport(/*dup=*/3, /*pending=*/2, /*oldest=*/14, /*past=*/1, /*mismatch=*/7);
        long id = repo.save(report, "manual", "{\"detail\":\"json\"}");
        assertTrue(id > 0);

        Optional<PersistedReport> loaded = repo.findById(id);
        assertTrue(loaded.isPresent());
        PersistedReport p = loaded.get();
        assertEquals(id, p.id());
        assertEquals(3, p.duplicateLiveLocations());
        assertEquals(2, p.pendingGrace());
        assertEquals(14, p.oldestPendingGraceDays());
        assertEquals(1, p.pastGraceStragglers());
        assertEquals(7, p.actressFolderMismatches());
        assertEquals("manual", p.triggeredBy());
        assertEquals("{\"detail\":\"json\"}", p.detailJson());
        assertNotNull(p.generatedAt());
    }

    @Test
    void findRecent_returnsNewestFirst() throws Exception {
        repo.save(sampleReport(1, 0, 0, 0, 0), "manual", null);
        Thread.sleep(10);
        repo.save(sampleReport(2, 0, 0, 0, 0), "coherent_sync", null);
        Thread.sleep(10);
        repo.save(sampleReport(3, 0, 0, 0, 0), "manual", null);

        List<PersistedReport> recent = repo.findRecent(10);
        assertEquals(3, recent.size());
        // Newest first
        assertEquals(3, recent.get(0).duplicateLiveLocations());
        assertEquals(2, recent.get(1).duplicateLiveLocations());
        assertEquals(1, recent.get(2).duplicateLiveLocations());
    }

    @Test
    void findRecent_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            repo.save(sampleReport(i, 0, 0, 0, 0), "manual", null);
        }
        List<PersistedReport> three = repo.findRecent(3);
        assertEquals(3, three.size());
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertTrue(repo.findById(99999L).isEmpty());
    }

    @Test
    void save_preservesNullDetailJson() {
        long id = repo.save(sampleReport(0, 0, 0, 0, 0), "coherent_sync", null);
        PersistedReport p = repo.findById(id).orElseThrow();
        assertNull(p.detailJson());
        assertEquals("coherent_sync", p.triggeredBy());
    }

    @Test
    void findLastByTrigger_returnsOnlyMatchingTrigger() throws Exception {
        // Insert coherent_sync older row
        repo.save(sampleReport(1, 0, 0, 0, 0), "coherent_sync", null);
        Thread.sleep(10);
        // Insert a more-recent manual row — should NOT be returned for coherent_sync
        repo.save(sampleReport(99, 0, 0, 0, 0), "manual", null);
        Thread.sleep(10);
        // Insert a newer coherent_sync row — this is the one we expect
        repo.save(sampleReport(2, 0, 0, 0, 0), "coherent_sync", null);

        Optional<PersistedReport> result = repo.findLastByTrigger("coherent_sync");
        assertTrue(result.isPresent(), "Expected a coherent_sync report to be found");
        assertEquals(2, result.get().duplicateLiveLocations(),
                "Expected the most-recent coherent_sync row, not the manual row");
        assertEquals("coherent_sync", result.get().triggeredBy());
    }

    @Test
    void findLastByTrigger_emptyWhenNoMatchingTrigger() {
        repo.save(sampleReport(5, 0, 0, 0, 0), "manual", null);

        Optional<PersistedReport> result = repo.findLastByTrigger("coherent_sync");
        assertTrue(result.isEmpty(), "Expected empty Optional when no coherent_sync rows exist");
    }

    private static ReconcileReport sampleReport(int dup, int pending, int oldest, int past, int mismatch) {
        return new ReconcileReport(
                Instant.now(),
                dup, pending, oldest, past, mismatch,
                List.of(), List.of(), List.of(), List.of()
        );
    }
}
