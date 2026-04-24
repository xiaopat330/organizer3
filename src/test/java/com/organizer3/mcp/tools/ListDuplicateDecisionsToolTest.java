package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.jdbi.JdbiDuplicateDecisionRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ListDuplicateDecisionsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiDuplicateDecisionRepository repo;
    private ListDuplicateDecisionsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        repo = new JdbiDuplicateDecisionRepository(jdbi);
        tool = new ListDuplicateDecisionsTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void emptyWhenNoDecisions() {
        var r = (ListDuplicateDecisionsTool.Result) tool.call(M.createObjectNode());
        assertEquals(0, r.count());
        assertTrue(r.decisions().isEmpty());
    }

    @Test
    void returnsAllPendingDecisions() {
        repo.upsert(decision("SSIS-001", "vol-a", "/jav/SSIS-001", "KEEP"));
        repo.upsert(decision("SSIS-001", "vol-a", "/jav/dup/SSIS-001", "TRASH"));
        repo.upsert(decision("PRED-002", "vol-a", "/jav/PRED-002", "VARIANT"));

        var r = (ListDuplicateDecisionsTool.Result) tool.call(M.createObjectNode());
        assertEquals(3, r.count());
        assertEquals(3, r.decisions().size());
    }

    @Test
    void rowHasExpectedFields() {
        repo.upsert(decision("MIDE-100", "vol-a", "/jav/MIDE-100", "TRASH"));

        var r = (ListDuplicateDecisionsTool.Result) tool.call(M.createObjectNode());
        var row = r.decisions().get(0);
        assertEquals("MIDE-100", row.titleCode());
        assertEquals("vol-a",    row.volumeId());
        assertEquals("/jav/MIDE-100", row.nasPath());
        assertEquals("TRASH",    row.decision());
        assertNotNull(row.createdAt());
    }

    @Test
    void doesNotReturnExecutedDecisions() {
        repo.upsert(decision("ABP-001", "vol-a", "/jav/ABP-001", "TRASH"));
        repo.markExecuted("ABP-001", "vol-a", "/jav/ABP-001", "2026-04-24T00:00:00Z");
        repo.upsert(decision("ABP-002", "vol-a", "/jav/ABP-002", "KEEP"));

        var r = (ListDuplicateDecisionsTool.Result) tool.call(M.createObjectNode());
        assertEquals(1, r.count());
        assertEquals("ABP-002", r.decisions().get(0).titleCode());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static DuplicateDecision decision(String code, String vol, String path, String dec) {
        return DuplicateDecision.builder()
                .titleCode(code).volumeId(vol).nasPath(path)
                .decision(dec).createdAt("2026-04-24T00:00:00Z")
                .build();
    }
}
