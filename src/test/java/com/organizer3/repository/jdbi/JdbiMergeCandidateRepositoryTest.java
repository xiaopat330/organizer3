package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.MergeCandidate;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbiMergeCandidateRepositoryTest {

    private JdbiMergeCandidateRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiMergeCandidateRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private String now() { return Instant.now().toString(); }

    @Test
    void insertAndListPending() {
        repo.insertIfAbsent("ONED-001", "ONED-01", "code-normalization", now());
        List<MergeCandidate> pending = repo.listPending();
        assertEquals(1, pending.size());
        MergeCandidate c = pending.get(0);
        // Canonical order: lexicographically smaller first
        assertEquals("ONED-001", c.getTitleCodeA());
        assertEquals("ONED-01", c.getTitleCodeB());
        assertEquals("code-normalization", c.getConfidence());
        assertNull(c.getDecision());
    }

    @Test
    void insertCanonicalOrderRegardlessOfArgOrder() {
        repo.insertIfAbsent("ZZZ-999", "AAA-001", "code-normalization", now());
        MergeCandidate c = repo.listPending().get(0);
        assertEquals("AAA-001", c.getTitleCodeA());
        assertEquals("ZZZ-999", c.getTitleCodeB());
    }

    @Test
    void insertIfAbsentIsIdempotent() {
        String t = now();
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", t);
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());
        assertEquals(1, repo.listPending().size());
    }

    @Test
    void insertIfAbsentDoesNotOverwriteDismissedPair() {
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());
        long id = repo.listPending().get(0).getId();
        repo.decide(id, "DISMISS", null, now());

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());

        // Still dismissed, not back in pending
        assertEquals(0, repo.listPending().size());
    }

    @Test
    void decideMergeSetsBothDecisionAndWinnerCode() {
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());
        long id = repo.listPending().get(0).getId();
        repo.decide(id, "MERGE", "ABP-001", now());

        assertEquals(0, repo.listPending().size());
        List<MergeCandidate> merge = repo.listPendingMerge();
        assertEquals(1, merge.size());
        assertEquals("MERGE", merge.get(0).getDecision());
        assertEquals("ABP-001", merge.get(0).getWinnerCode());
    }

    @Test
    void decideDismissRemovesFromPendingAndNotInMergeQueue() {
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());
        long id = repo.listPending().get(0).getId();
        repo.decide(id, "DISMISS", null, now());

        assertEquals(0, repo.listPending().size());
        assertEquals(0, repo.listPendingMerge().size());
    }

    @Test
    void markExecutedExcludesFromListPendingMerge() {
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());
        long id = repo.listPending().get(0).getId();
        repo.decide(id, "MERGE", "ABP-001", now());
        repo.markExecuted(id, now());

        assertEquals(0, repo.listPendingMerge().size());
    }

    @Test
    void findByBothCodeOrderings() {
        repo.insertIfAbsent("ONED-001", "ONED-01", "code-normalization", now());
        assertTrue(repo.find("ONED-001", "ONED-01").isPresent());
        assertTrue(repo.find("ONED-01", "ONED-001").isPresent());
        assertFalse(repo.find("ONED-001", "ONED-99").isPresent());
    }

    @Test
    void deleteUndecidedPreservesDecidedRows() {
        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", now());
        repo.insertIfAbsent("ABP-002", "ABP-02", "variant-suffix", now());
        long id = repo.listPending().stream()
                .filter(c -> "ABP-001".equals(c.getTitleCodeA())).findFirst().orElseThrow().getId();
        repo.decide(id, "MERGE", "ABP-001", now());

        repo.deleteUndecided();

        assertEquals(0, repo.listPending().size());
        // The MERGE-decided row survives
        assertEquals(1, repo.listPendingMerge().size());
    }

    @Test
    void listPendingOrdersByConfidenceThenCode() {
        repo.insertIfAbsent("ZZZ-001", "ZZZ-01", "variant-suffix", now());
        repo.insertIfAbsent("AAA-001", "AAA-01", "code-normalization", now());
        repo.insertIfAbsent("BBB-001", "BBB-01", "code-normalization", now());

        List<MergeCandidate> pending = repo.listPending();
        assertEquals(3, pending.size());
        assertEquals("code-normalization", pending.get(0).getConfidence());
        assertEquals("AAA-001", pending.get(0).getTitleCodeA());
        assertEquals("code-normalization", pending.get(1).getConfidence());
        assertEquals("BBB-001", pending.get(1).getTitleCodeA());
        assertEquals("variant-suffix", pending.get(2).getConfidence());
    }
}
