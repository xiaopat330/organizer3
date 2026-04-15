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

class FindDuplicateBaseCodesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private FindDuplicateBaseCodesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        tool = new FindDuplicateBaseCodesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void groupsTitlesSharingBaseCode() throws Exception {
        titleRepo.save(titleBase("ABP-001",  "ABP-00001"));
        titleRepo.save(titleBase("ABP-0001", "ABP-00001")); // same base_code, different code
        titleRepo.save(titleBase("SNIS-001", "SNIS-00001"));

        var r = (FindDuplicateBaseCodesTool.Result) tool.call(args(100));
        assertEquals(1, r.count());
        assertEquals("ABP-00001", r.groups().get(0).baseCode());
        assertEquals(2, r.groups().get(0).titles().size());
    }

    @Test
    void ignoresNullBaseCode() throws Exception {
        titleRepo.save(Title.builder().code("NULL-001").label("NULL").seqNum(1).build());
        titleRepo.save(Title.builder().code("NULL-002").label("NULL").seqNum(2).build());

        var r = (FindDuplicateBaseCodesTool.Result) tool.call(args(100));
        assertEquals(0, r.count());
    }

    private static Title titleBase(String code, String baseCode) {
        return Title.builder()
                .code(code)
                .baseCode(baseCode)
                .label(code.split("-")[0])
                .seqNum(1)
                .build();
    }

    private static ObjectNode args(int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("limit", limit);
        return n;
    }
}
