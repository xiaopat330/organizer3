package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FindAliasConflictsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository repo;
    private FindAliasConflictsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiActressRepository(jdbi);
        tool = new FindAliasConflictsTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsAliasColliderWithAnotherCanonical() throws Exception {
        Actress a = repo.save(mk("Aya Sazanami"));
        repo.save(mk("Haruka Suzumiya"));
        // Alias of A collides with B's canonical name
        repo.saveAlias(new ActressAlias(a.getId(), "Haruka Suzumiya"));

        var r = (FindAliasConflictsTool.Result) tool.call(args(100));
        assertEquals(1, r.count());
        assertEquals(2, r.conflicts().get(0).owners().size());
    }

    @Test
    void ignoresAliasPointingAtOwnCanonical() throws Exception {
        Actress a = repo.save(mk("Aya Sazanami"));
        // Redundant but not a bug — same actress on both sides
        repo.saveAlias(new ActressAlias(a.getId(), "Aya Sazanami"));

        var r = (FindAliasConflictsTool.Result) tool.call(args(100));
        assertEquals(0, r.count());
    }

    @Test
    void caseAndWhitespaceInsensitive() throws Exception {
        repo.save(mk("Aya Sazanami"));
        repo.save(mk("  AYA   sazanami")); // whitespace/case variant canonicals

        var r = (FindAliasConflictsTool.Result) tool.call(args(100));
        assertEquals(1, r.count());
    }

    @Test
    void flagsAliasOnAliasCollision() throws Exception {
        long a = repo.save(mk("First Actress")).getId();
        long b = repo.save(mk("Second Actress")).getId();
        repo.saveAlias(new ActressAlias(a, "Shared Alias"));
        repo.saveAlias(new ActressAlias(b, "Shared Alias"));

        var r = (FindAliasConflictsTool.Result) tool.call(args(100));
        assertEquals(1, r.count());
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("limit", limit);
        return n;
    }
}
