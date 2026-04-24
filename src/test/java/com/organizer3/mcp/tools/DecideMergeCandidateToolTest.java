package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.MergeCandidate;
import com.organizer3.repository.jdbi.JdbiMergeCandidateRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DecideMergeCandidateToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiMergeCandidateRepository repo;
    private DecideMergeCandidateTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiMergeCandidateRepository(jdbi);
        tool = new DecideMergeCandidateTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void recordsMergeWithWinnerCode() {
        long id = insertCandidate("SSIS-001", "SSIS-0010");

        var r = (DecideMergeCandidateTool.Result) tool.call(args(id, "MERGE", "SSIS-001"));
        assertEquals("recorded", r.outcome());
        assertEquals(id,         r.id());
        assertEquals("MERGE",    r.decision());
        assertEquals("SSIS-001", r.winnerCode());

        Optional<MergeCandidate> found = repo.find("SSIS-001", "SSIS-0010");
        assertTrue(found.isPresent());
        assertEquals("MERGE",    found.get().getDecision());
        assertEquals("SSIS-001", found.get().getWinnerCode());
    }

    @Test
    void recordsDismissWithoutWinnerCode() {
        long id = insertCandidate("PRED-001", "PRED-0010");

        var r = (DecideMergeCandidateTool.Result) tool.call(args(id, "DISMISS", null));
        assertEquals("recorded", r.outcome());
        assertEquals("DISMISS",  r.decision());
        assertNull(r.winnerCode());

        Optional<MergeCandidate> found = repo.find("PRED-001", "PRED-0010");
        assertEquals("DISMISS", found.get().getDecision());
        assertNull(found.get().getWinnerCode());
    }

    @Test
    void rejectsMergeWithoutWinnerCode() {
        long id = insertCandidate("ABP-001", "ABP-0010");
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(id, "MERGE", null)));
    }

    @Test
    void rejectsMergeWithBlankWinnerCode() {
        long id = insertCandidate("ABP-001", "ABP-0010");
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(id, "MERGE", "   ")));
    }

    @Test
    void rejectsInvalidDecision() {
        long id = insertCandidate("MIDE-100", "MIDE-1000");
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(id, "BOGUS", null)));
    }

    @Test
    void acceptsLowercaseDecision() {
        long id = insertCandidate("SKY-001", "SKY-0010");
        var r = (DecideMergeCandidateTool.Result) tool.call(args(id, "dismiss", null));
        assertEquals("DISMISS", r.decision());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long insertCandidate(String codeA, String codeB) {
        repo.insertIfAbsent(codeA, codeB, "code-normalization", "2026-04-24T00:00:00Z");
        return repo.find(codeA, codeB).orElseThrow().getId();
    }

    private static ObjectNode args(long id, String decision, String winnerCode) {
        ObjectNode n = M.createObjectNode();
        n.put("id", id);
        n.put("decision", decision);
        if (winnerCode != null) n.put("winnerCode", winnerCode);
        return n;
    }
}
