package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
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

import static org.junit.jupiter.api.Assertions.*;

class FindLongVowelActressVariantsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private FindLongVowelActressVariantsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        tool = new FindLongVowelActressVariantsTool(actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void findsOoVariant() {
        Actress aOoba = save("Yui Ooba");
        Actress aOba  = save("Yui Oba");
        addTitles(aOoba, 6);
        addTitles(aOba, 3);
        save("Mariko Naka"); // neutral noise

        var r = call(1, 100);
        assertEquals(1, r.count(), "expected exactly one pair");
        var p = r.pairs().get(0);
        assertEquals("Oo/O", p.variantType());
        // Sides are id-ordered; identify by name and check title counts.
        int oobaTc = p.actressA().name().equals("Yui Ooba") ? p.actressA().titleCount() : p.actressB().titleCount();
        int obaTc  = p.actressA().name().equals("Yui Oba")  ? p.actressA().titleCount() : p.actressB().titleCount();
        assertEquals(6, oobaTc);
        assertEquals(3, obaTc);
    }

    @Test
    void findsOuVariant() {
        Actress aSaijou = save("Ruri Saijou");
        Actress aSaijo  = save("Ruri Saijo");
        addTitles(aSaijou, 4);
        addTitles(aSaijo, 1);

        var r = call(1, 100);
        assertEquals(1, r.count());
        assertEquals("Ou/O", r.pairs().get(0).variantType());
    }

    @Test
    void findsMultipleVariantTypes() {
        Actress aOoba   = save("Yui Ooba");   addTitles(aOoba, 2);
        Actress aOba    = save("Yui Oba");    addTitles(aOba, 1);
        Actress aSaijou = save("Ai Saijou");  addTitles(aSaijou, 2);
        Actress aSaijo  = save("Ai Saijo");   addTitles(aSaijo, 1);
        Actress aYuuki  = save("Mio Yuuki");  addTitles(aYuuki, 2);
        Actress aYuki   = save("Mio Yuki");   addTitles(aYuki, 1);

        var r = call(1, 100);
        assertEquals(3, r.count(), "expected three pairs");
        var types = r.pairs().stream().map(FindLongVowelActressVariantsTool.Pair::variantType).toList();
        assertTrue(types.contains("Oo/O"));
        assertTrue(types.contains("Ou/O"));
        assertTrue(types.contains("Uu/U"));
    }

    @Test
    void excludesKnownAliases() {
        Actress aOoba = save("Yui Ooba");
        Actress aOba  = save("Yui Oba");
        addTitles(aOoba, 6);
        addTitles(aOba, 3);
        // Link them via an alias: "Yui Oba" listed as an alias of Ooba
        actressRepo.saveAlias(new ActressAlias(aOoba.getId(), "Yui Oba"));

        var r = call(1, 100);
        assertEquals(0, r.count(), "pair already linked via alias must be excluded");
    }

    @Test
    void excludesSameRow() {
        Actress a = save("Yui Ooba");
        addTitles(a, 3);

        var r = call(1, 100);
        assertEquals(0, r.count(), "a single actress must never pair with herself");
    }

    @Test
    void coCreditCountCorrect() {
        Actress aOoba = save("Yui Ooba");
        Actress aOba  = save("Yui Oba");
        // Two shared titles + one Ooba-only title
        long t1 = addTitle(aOoba, "TST-001");
        long t2 = addTitle(aOoba, "TST-002");
        long t3 = addTitle(aOoba, "TST-003");
        titleActressRepo.link(t1, aOba.getId());
        titleActressRepo.link(t2, aOba.getId());
        // t3 is Ooba-only

        var r = call(1, 100);
        assertEquals(1, r.count());
        assertEquals(2, r.pairs().get(0).coCreditedTitles());
    }

    @Test
    void minTitleCountFilter() {
        save("Yui Ooba");
        save("Yui Oba");
        // Both have zero titles

        var rDefault = call(1, 100);
        assertEquals(0, rDefault.count(), "default min=1 should exclude both-zero-title pair");

        var rZero = call(0, 100);
        assertEquals(1, rZero.count(), "min=0 should include the pair");
    }

    @Test
    void maxResultsRespected() {
        // 5 distinct pairs
        for (int i = 0; i < 5; i++) {
            Actress longForm  = save("Star" + i + " Ooba");
            Actress shortForm = save("Star" + i + " Oba");
            addTitles(longForm, 2);
            addTitles(shortForm, 1);
        }

        var r = call(1, 2);
        assertEquals(2, r.count(), "limit must cap pairs at max_results");
    }

    @Test
    void collapseLongVowelsHandlesAllSubstitutions() {
        // Per-token collapse
        assertEquals("oba", FindLongVowelActressVariantsTool.collapseLongVowels("Ooba"));
        assertEquals("saijo", FindLongVowelActressVariantsTool.collapseLongVowels("Saijou"));
        assertEquals("yuki", FindLongVowelActressVariantsTool.collapseLongVowels("Yuuki"));
        assertEquals("toru", FindLongVowelActressVariantsTool.collapseLongVowels("Tohru"));
        // Per-token, not across tokens
        assertEquals("yui oba", FindLongVowelActressVariantsTool.collapseLongVowels("Yui Ooba"));
        assertEquals("yui oba", FindLongVowelActressVariantsTool.collapseLongVowels("Yui Oba"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Actress save(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build());
    }

    /** Add {@code n} titles owned by the actress, also wiring title_actresses. */
    private void addTitles(Actress a, int n) {
        for (int i = 0; i < n; i++) {
            String code = "T" + a.getId() + "-" + String.format("%03d", i);
            addTitle(a, code);
        }
    }

    private long addTitle(Actress a, String code) {
        Title t = Title.builder()
                .code(code)
                .baseCode(code)
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(a.getId())
                .build();
        long id = titleRepo.save(t).getId();
        titleActressRepo.link(id, a.getId());
        return id;
    }

    private FindLongVowelActressVariantsTool.Result call(int minTitleCount, int maxResults) {
        ObjectNode n = M.createObjectNode();
        n.put("min_title_count", minTitleCount);
        n.put("max_results", maxResults);
        return (FindLongVowelActressVariantsTool.Result) tool.call(n);
    }
}
