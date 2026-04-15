package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FindNameOrderVariantsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository repo;
    private FindNameOrderVariantsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiActressRepository(jdbi);
        tool = new FindNameOrderVariantsTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void groupsTokenInversionsAcrossActresses() throws Exception {
        repo.save(mk("Yua Mikami"));
        repo.save(mk("Mikami Yua"));

        var r = (FindNameOrderVariantsTool.Result) tool.call(args(2, 100));
        assertEquals(1, r.count());
        assertEquals(2, r.groups().get(0).members().size());
    }

    @Test
    void ignoresSingleTokenNames() throws Exception {
        repo.save(mk("Yua"));
        repo.save(mk("Mikami"));

        var r = (FindNameOrderVariantsTool.Result) tool.call(args(2, 100));
        assertEquals(0, r.count());
    }

    @Test
    void ignoresGroupWithSameActressOnly() throws Exception {
        // Same actress, alias and canonical both tokenize the same set —
        // this is an already-resolved alias mapping, not an anomaly.
        Actress a = repo.save(mk("Yua Mikami"));
        repo.saveAlias(new com.organizer3.model.ActressAlias(a.getId(), "Mikami Yua"));

        var r = (FindNameOrderVariantsTool.Result) tool.call(args(2, 100));
        assertEquals(0, r.count(), "same actress should not be flagged as variant");
    }

    @Test
    void caseInsensitiveMatch() throws Exception {
        repo.save(mk("Yua Mikami"));
        repo.save(mk("MIKAMI yua"));

        var r = (FindNameOrderVariantsTool.Result) tool.call(args(2, 100));
        assertEquals(1, r.count());
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(int minTokens, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("min_tokens", minTokens);
        n.put("limit", limit);
        return n;
    }
}
