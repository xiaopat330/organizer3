package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FindSuspectCreditsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private FindSuspectCreditsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        tool = new FindSuspectCreditsTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsOutlierCastMember() throws Exception {
        // A, B, C are a real group — they appear together on multiple titles.
        // D is a typo — appears only on one title, with A/B/C, and nowhere else.
        long a = actressRepo.save(actress("Alpha Real")).getId();
        long b = actressRepo.save(actress("Bravo Real")).getId();
        long c = actressRepo.save(actress("Charlie Real")).getId();
        long d = actressRepo.save(actress("Delta Typo")).getId();

        long t1 = saveTitle("ONED-001");
        long t2 = saveTitle("ONED-002");
        long t3 = saveTitle("ONED-003");

        titleActressRepo.linkAll(t1, List.of(a, b, c, d));
        titleActressRepo.linkAll(t2, List.of(a, b, c));
        titleActressRepo.linkAll(t3, List.of(a, b));

        var r = (FindSuspectCreditsTool.Result) tool.call(args(3, 50));
        assertEquals(1, r.count());
        var s = r.suspects().get(0);
        assertEquals("ONED-001", s.titleCode());
        assertEquals(d, s.suspect().actressId());
        assertEquals(3, s.otherCast().size());
    }

    @Test
    void noFlagsWhenCastMembersCoOccurElsewhere() throws Exception {
        long a = actressRepo.save(actress("A")).getId();
        long b = actressRepo.save(actress("B")).getId();
        long c = actressRepo.save(actress("C")).getId();

        long t1 = saveTitle("ABP-001");
        long t2 = saveTitle("ABP-002");
        long t3 = saveTitle("ABP-003");

        titleActressRepo.linkAll(t1, List.of(a, b, c));
        titleActressRepo.linkAll(t2, List.of(a, b));
        titleActressRepo.linkAll(t3, List.of(b, c));

        var r = (FindSuspectCreditsTool.Result) tool.call(args(3, 50));
        assertEquals(0, r.count());
    }

    @Test
    void respectsMinCastSize() throws Exception {
        long a = actressRepo.save(actress("A")).getId();
        long b = actressRepo.save(actress("B")).getId();
        long t1 = saveTitle("X-001");
        titleActressRepo.linkAll(t1, List.of(a, b));

        // min_cast=3 skips the 2-actress title
        var r = (FindSuspectCreditsTool.Result) tool.call(args(3, 50));
        assertEquals(0, r.count());

        // min_cast=2 inspects it — neither actress co-occurs elsewhere, so both are suspect
        var r2 = (FindSuspectCreditsTool.Result) tool.call(args(2, 50));
        assertEquals(2, r2.count());
    }

    private long saveTitle(String code) {
        Title t = Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .build();
        return titleRepo.save(t).getId();
    }

    private static Actress actress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(int minCast, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("min_cast_size", minCast);
        n.put("limit", limit);
        return n;
    }
}
