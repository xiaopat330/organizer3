package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class FindLabelMismatchesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private FindLabelMismatchesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        tool = new FindLabelMismatchesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsLabelNotMatchingCodePrefix() throws Exception {
        titleRepo.save(mk("ABP-001", "SNIS"));  // mismatch
        titleRepo.save(mk("ABP-002", "ABP"));   // fine

        var r = (FindLabelMismatchesTool.Result) tool.call(args(100));
        assertEquals(1, r.count());
        assertEquals("ABP-001", r.titles().get(0).code());
        assertEquals("SNIS", r.titles().get(0).label());
        assertEquals("ABP", r.titles().get(0).codePrefix());
    }

    @Test
    void caseInsensitiveComparison() throws Exception {
        titleRepo.save(mk("abp-001", "ABP"));

        var r = (FindLabelMismatchesTool.Result) tool.call(args(100));
        assertEquals(0, r.count());
    }

    @Test
    void ignoresTitlesWithoutLabel() throws Exception {
        titleRepo.save(Title.builder().code("NOLABEL-001").baseCode("NOLABEL-00001").seqNum(1).build());

        var r = (FindLabelMismatchesTool.Result) tool.call(args(100));
        assertEquals(0, r.count());
    }

    private static Title mk(String code, String label) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(label)
                .seqNum(1)
                .build();
    }

    private static ObjectNode args(int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("limit", limit);
        return n;
    }
}
