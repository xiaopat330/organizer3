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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MergeActressesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private MergeActressesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        tool = new MergeActressesTool(jdbi, actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── dry-run ─────────────────────────────────────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutMutating() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        titleRepo.save(title("ABP-001", from));
        titleRepo.save(title("ABP-002", from));

        var r = (MergeActressesTool.Result) tool.call(args(into, from, true));
        assertTrue(r.dryRun());
        assertTrue(r.plan().summary().contains("Aino Nami"));
        assertTrue(r.plan().summary().contains("Nami Aino"));

        // Mutation side-effects must not have happened
        assertTrue(actressRepo.findById(from).isPresent());
        assertEquals(2, titleRepo.findByActress(from).size());
        assertEquals(0, titleRepo.findByActress(into).size());
    }

    // ── execution ───────────────────────────────────────────────────────────

    @Test
    void executeReassignsTitleActressId() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        titleRepo.save(title("ABP-001", from));
        titleRepo.save(title("ABP-002", from));

        var r = (MergeActressesTool.Result) tool.call(args(into, from, false));
        assertFalse(r.dryRun());

        assertTrue(actressRepo.findById(from).isEmpty(), "from actress should be deleted");
        assertEquals(2, titleRepo.findByActress(into).size(), "titles should now belong to into");
    }

    @Test
    void executeAddsFromCanonicalNameAsAlias() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();

        tool.call(args(into, from, false));

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliases.contains("Aino Nami"), "from's canonical should become an alias");
    }

    @Test
    void executeMigratesAliasesDedupingCollisions() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        actressRepo.saveAlias(new ActressAlias(from, "Nami"));
        actressRepo.saveAlias(new ActressAlias(from, "Sweet Girl"));
        actressRepo.saveAlias(new ActressAlias(into, "Nami")); // pre-existing on into

        tool.call(args(into, from, false));

        List<String> aliases = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).sorted().toList();
        assertTrue(aliases.contains("Sweet Girl"));
        assertTrue(aliases.contains("Nami"));
        assertEquals(1, aliases.stream().filter("Nami"::equals).count(), "duplicate alias collapsed");
    }

    @Test
    void executeRewritesJunctionRowsDedupingCollisions() throws Exception {
        long into = actressRepo.save(mk("Nami Aino")).getId();
        long from = actressRepo.save(mk("Aino Nami")).getId();
        long otherA = actressRepo.save(mk("Co-star")).getId();

        long t1 = titleRepo.save(title("GROUP-001", null)).getId();
        long t2 = titleRepo.save(title("GROUP-002", null)).getId();
        // t1: both into and from credited — should dedup to a single row under into
        titleActressRepo.linkAll(t1, List.of(into, from, otherA));
        // t2: only from credited — should migrate to into
        titleActressRepo.linkAll(t2, List.of(from));

        tool.call(args(into, from, false));

        List<Long> t1Cast = titleActressRepo.findActressIdsByTitle(t1);
        List<Long> t2Cast = titleActressRepo.findActressIdsByTitle(t2);
        assertTrue(t1Cast.contains(into));
        assertTrue(t1Cast.contains(otherA));
        assertFalse(t1Cast.contains(from), "from removed from t1");
        assertEquals(2, t1Cast.size(), "t1 should have (into, otherA) — no dupe");
        assertEquals(List.of(into), t2Cast, "t2 migrated from -> into");
    }

    // ── flag merge policy ──────────────────────────────────────────────────

    @Test
    void executeMergesFavoriteAndBookmarkAsOr() throws Exception {
        long into = actressRepo.save(mk("Into", false, false)).getId();
        long from = actressRepo.save(mk("From", true, true)).getId();

        tool.call(args(into, from, false));

        Actress merged = actressRepo.findById(into).orElseThrow();
        assertTrue(merged.isFavorite());
        assertTrue(merged.isBookmark());
    }

    @Test
    void executeRejectsStaysTrueOnlyWhenBothRejected() throws Exception {
        long into = actressRepo.save(mk("Into")).getId();
        long from = actressRepo.save(mk("From")).getId();
        actressRepo.toggleRejected(from, true); // only from rejected

        tool.call(args(into, from, false));

        Actress merged = actressRepo.findById(into).orElseThrow();
        assertFalse(merged.isRejected(), "AND policy — merging into non-rejected clears flag");
    }

    @Test
    void mergeFlagsUnitChoosesStrongerGrade() {
        Actress into = Actress.builder().id(1L).canonicalName("i").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).grade(Actress.Grade.B).build();
        Actress from = Actress.builder().id(2L).canonicalName("f").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).grade(Actress.Grade.SSS).build();
        assertEquals(Actress.Grade.SSS, MergeActressesTool.mergeFlags(into, from).grade());
    }

    // ── false-positive guard ────────────────────────────────────────────────

    @Test
    void mergeDoesNotTouchUnrelatedActress() {
        long into      = actressRepo.save(mk("Nami Aino")).getId();
        long from      = actressRepo.save(mk("Aino Nami")).getId();
        long unrelated = actressRepo.save(mk("Unrelated Actress")).getId();

        actressRepo.saveAlias(new ActressAlias(unrelated, "Unrelated Alias"));
        long titleId = titleRepo.save(title("UNR-001", unrelated)).getId();
        titleActressRepo.linkAll(titleId, List.of(unrelated));

        tool.call(args(into, from, false));

        // A missing WHERE clause in any delete step would wipe these rows
        assertTrue(actressRepo.findById(unrelated).isPresent(),
                "unrelated actress row must not be deleted");
        List<String> aliases = actressRepo.findAliases(unrelated).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliases.contains("Unrelated Alias"),
                "unrelated actress's aliases must survive merge");
        assertTrue(titleActressRepo.findActressIdsByTitle(titleId).contains(unrelated),
                "unrelated actress's title credits must survive merge");
    }

    // ── validation ─────────────────────────────────────────────────────────

    @Test
    void rejectsSameIntoAndFrom() throws Exception {
        long id = actressRepo.save(mk("Someone")).getId();
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(id, id, true)));
    }

    @Test
    void rejectsMissingActress() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(9999L, 9998L, true)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Actress mk(String name) {
        return mk(name, false, false);
    }

    private static Actress mk(String name, boolean favorite, boolean bookmark) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .favorite(favorite)
                .bookmark(bookmark)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, Long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static ObjectNode args(long into, long from, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("into", into);
        n.put("from", from);
        n.put("dryRun", dryRun);
        return n;
    }
}
