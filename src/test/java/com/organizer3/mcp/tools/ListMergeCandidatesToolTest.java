package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.jdbi.JdbiMergeCandidateRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ListMergeCandidatesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiMergeCandidateRepository repo;
    private ListMergeCandidatesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiMergeCandidateRepository(jdbi);
        tool = new ListMergeCandidatesTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void emptyWhenNoCandidates() {
        var r = (ListMergeCandidatesTool.Result) tool.call(M.createObjectNode());
        assertEquals(0, r.count());
        assertTrue(r.candidates().isEmpty());
    }

    @Test
    void returnsUndecidedCandidates() {
        repo.insertIfAbsent("SSIS-001", "SSIS-0010", "code-normalization", "2026-04-24T00:00:00Z");
        repo.insertIfAbsent("PRED-001", "PRED-0010", "variant-suffix",      "2026-04-24T00:00:00Z");

        var r = (ListMergeCandidatesTool.Result) tool.call(M.createObjectNode());
        assertEquals(2, r.count());
    }

    @Test
    void rowHasExpectedFields() {
        repo.insertIfAbsent("ABP-001", "ABP-0010", "code-normalization", "2026-04-24T00:00:00Z");

        var r = (ListMergeCandidatesTool.Result) tool.call(M.createObjectNode());
        var row = r.candidates().get(0);
        assertTrue(row.id() > 0);
        assertEquals("ABP-001",            row.titleCodeA());
        assertEquals("ABP-0010",           row.titleCodeB());
        assertEquals("code-normalization", row.confidence());
        assertNotNull(row.detectedAt());
    }

    @Test
    void excludesDecidedCandidates() {
        repo.insertIfAbsent("SSIS-001", "SSIS-0010", "code-normalization", "2026-04-24T00:00:00Z");
        repo.insertIfAbsent("MIDE-100", "MIDE-1000", "variant-suffix",     "2026-04-24T00:00:00Z");

        // Decide one of them
        var all = repo.listPending();
        repo.decide(all.get(0).getId(), "MERGE", all.get(0).getTitleCodeA(), "2026-04-24T00:00:00Z");

        var r = (ListMergeCandidatesTool.Result) tool.call(M.createObjectNode());
        assertEquals(1, r.count());
    }
}
