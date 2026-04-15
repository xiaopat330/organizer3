package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FindLoneTitlesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private FindLoneTitlesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        tool = new FindLoneTitlesTool(actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void returnsActressesWithSingleTitle() throws Exception {
        long loneId  = actressRepo.save(mk("Lone Star")).getId();
        long popId   = actressRepo.save(mk("Pop Star")).getId();

        saveTitle("LONE-001", loneId);
        saveTitle("POP-001",  popId);
        saveTitle("POP-002",  popId);
        saveTitle("POP-003",  popId);

        var r = (FindLoneTitlesTool.Result) tool.call(args(1, 100));
        assertEquals(1, r.count());
        assertEquals(loneId, r.actresses().get(0).actressId());
        assertEquals(1, r.actresses().get(0).titleCount());
    }

    @Test
    void includesZeroTitleActressesByDefault() throws Exception {
        actressRepo.save(mk("Ghost Actress")); // no titles

        var r = (FindLoneTitlesTool.Result) tool.call(args(1, 100));
        assertEquals(1, r.count());
        assertEquals(0, r.actresses().get(0).titleCount());
    }

    @Test
    void excludesRejectedActresses() throws Exception {
        long id = actressRepo.save(mk("Rejected Person")).getId();
        actressRepo.toggleRejected(id, true);

        var r = (FindLoneTitlesTool.Result) tool.call(args(1, 100));
        assertEquals(0, r.count());
    }

    @Test
    void respectsMaxTitlesThreshold() throws Exception {
        long a = actressRepo.save(mk("Two Title Actress")).getId();
        saveTitle("TT-001", a);
        saveTitle("TT-002", a);

        // max_titles=1 excludes 2-title actress
        var r1 = (FindLoneTitlesTool.Result) tool.call(args(1, 100));
        assertEquals(0, r1.count());

        // max_titles=2 includes her
        var r2 = (FindLoneTitlesTool.Result) tool.call(args(2, 100));
        assertEquals(1, r2.count());
    }

    private void saveTitle(String code, long actressId) {
        titleRepo.save(Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build());
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(int maxTitles, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("max_titles", maxTitles);
        n.put("limit", limit);
        return n;
    }
}
