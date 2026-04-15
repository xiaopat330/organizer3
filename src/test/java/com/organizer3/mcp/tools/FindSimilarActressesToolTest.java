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

class FindSimilarActressesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository repo;
    private FindSimilarActressesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiActressRepository(jdbi);
        tool = new FindSimilarActressesTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsNearDuplicateCanonicalNames() throws Exception {
        repo.save(mk("Yua Mikami"));
        repo.save(mk("Yua Mikarni")); // one char off — likely typo

        var r = (FindSimilarActressesTool.Result) tool.call(args(2, 4, 100));
        assertTrue(r.count() >= 1, "expected at least one similar pair");
        var p = r.pairs().get(0);
        assertNotEquals(p.a().actressId(), p.b().actressId());
        assertTrue(p.distance() <= 2);
    }

    @Test
    void doesNotFlagIdenticalNamesOfSameActress() throws Exception {
        Actress a = repo.save(mk("Aya Sazanami"));
        repo.saveAlias(new ActressAlias(a.getId(), "Aya Sazanami")); // redundant alias — same id

        var r = (FindSimilarActressesTool.Result) tool.call(args(2, 4, 100));
        for (var p : r.pairs()) {
            assertNotEquals(p.a().actressId(), p.b().actressId(),
                    "pairs must span distinct actress ids");
        }
    }

    @Test
    void ignoresShortNames() throws Exception {
        repo.save(mk("Aya")); // below default min_length=4
        repo.save(mk("Ay"));

        var r = (FindSimilarActressesTool.Result) tool.call(args(2, 4, 100));
        assertEquals(0, r.count());
    }

    @Test
    void respectsMaxDistanceThreshold() throws Exception {
        repo.save(mk("abcdefgh"));
        repo.save(mk("abcdxxxx")); // distance = 4

        // with max_distance=2, should not match
        var r2 = (FindSimilarActressesTool.Result) tool.call(args(2, 4, 100));
        assertEquals(0, r2.count());

        // with max_distance=4, should match
        var r4 = (FindSimilarActressesTool.Result) tool.call(args(4, 4, 100));
        assertEquals(1, r4.count());
    }

    @Test
    void respectsLimit() throws Exception {
        repo.save(mk("aaaabbbb"));
        repo.save(mk("aaaabbbc")); // pair 1
        repo.save(mk("ccccdddd"));
        repo.save(mk("ccccdddc")); // pair 2

        var r = (FindSimilarActressesTool.Result) tool.call(args(2, 4, 1));
        assertEquals(1, r.count());
    }

    @Test
    void levenshteinEarlyExitReturnsMinusOneAboveThreshold() {
        assertEquals(-1, FindSimilarActressesTool.levenshtein("cat", "dogs", 1));
        assertEquals(1,  FindSimilarActressesTool.levenshtein("cat", "bat", 2));
        assertEquals(0,  FindSimilarActressesTool.levenshtein("same", "same", 2));
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(int maxDist, int minLen, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("max_distance", maxDist);
        n.put("min_length", minLen);
        n.put("limit", limit);
        return n;
    }
}
