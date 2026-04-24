package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.jdbi.JdbiDuplicateDecisionRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetDuplicateDecisionToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiDuplicateDecisionRepository repo;
    private SetDuplicateDecisionTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        repo = new JdbiDuplicateDecisionRepository(jdbi);
        tool = new SetDuplicateDecisionTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void upsertsKeepDecision() {
        var r = (SetDuplicateDecisionTool.Result) tool.call(args("SSIS-001", "vol-a", "/jav/SSIS-001", "KEEP"));
        assertEquals("upserted", r.outcome());
        assertEquals("KEEP",     r.decision());

        List<DuplicateDecision> pending = repo.listPending();
        assertEquals(1, pending.size());
        assertEquals("KEEP", pending.get(0).getDecision());
    }

    @Test
    void upsertsTrashDecision() {
        tool.call(args("MIDE-100", "vol-a", "/jav/MIDE-100", "TRASH"));
        assertEquals("TRASH", repo.listPending().get(0).getDecision());
    }

    @Test
    void upsertsVariantDecision() {
        tool.call(args("ABP-001", "vol-a", "/jav/ABP-001", "VARIANT"));
        assertEquals("VARIANT", repo.listPending().get(0).getDecision());
    }

    @Test
    void clearsExistingDecision() {
        tool.call(args("PRED-001", "vol-a", "/jav/PRED-001", "TRASH"));
        assertEquals(1, repo.listPending().size());

        var r = (SetDuplicateDecisionTool.Result) tool.call(args("PRED-001", "vol-a", "/jav/PRED-001", "CLEAR"));
        assertEquals("cleared", r.outcome());
        assertNull(r.decision());
        assertTrue(repo.listPending().isEmpty(), "decision removed after CLEAR");
    }

    @Test
    void clearIsNoOpWhenAbsent() {
        // Should not throw
        var r = (SetDuplicateDecisionTool.Result) tool.call(args("NOTEXIST-001", "vol-a", "/jav/x", "CLEAR"));
        assertEquals("cleared", r.outcome());
    }

    @Test
    void upsertIsIdempotentAndUpdates() {
        tool.call(args("SSIS-001", "vol-a", "/jav/SSIS-001", "KEEP"));
        tool.call(args("SSIS-001", "vol-a", "/jav/SSIS-001", "TRASH"));

        List<DuplicateDecision> pending = repo.listPending();
        assertEquals(1, pending.size());
        assertEquals("TRASH", pending.get(0).getDecision());
    }

    @Test
    void rejectsInvalidDecision() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("SSIS-001", "vol-a", "/jav/SSIS-001", "WRONG")));
    }

    @Test
    void acceptsLowercaseInput() {
        var r = (SetDuplicateDecisionTool.Result) tool.call(args("SSIS-001", "vol-a", "/jav/x", "trash"));
        assertEquals("TRASH", r.decision());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ObjectNode args(String code, String vol, String path, String decision) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode", code);
        n.put("volumeId",  vol);
        n.put("nasPath",   path);
        n.put("decision",  decision);
        return n;
    }
}
