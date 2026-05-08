package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FindMisnamedFoldersForActressToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private FindMisnamedFoldersForActressTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        tool = new FindMisnamedFoldersForActressTool(jdbi, actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void reportsMismatchedPathWithMatchedAlias() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        actressRepo.saveAlias(new ActressAlias(aid, "Aino Nami"));
        long tid = titleRepo.save(title("SKY-283", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/archive/Aino Nami (SKY-283)"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(args(aid, 100));
        assertEquals(1, r.count());
        var row = r.misnamedLocations().get(0);
        assertEquals("SKY-283", row.titleCode());
        assertEquals("Aino Nami", row.matchedAlias());
    }

    @Test
    void reportsMismatchedPathWithNullAliasWhenUnrelated() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        long tid = titleRepo.save(title("WEIRD-001", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/archive/Random Folder Name"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(args(aid, 100));
        assertEquals(1, r.count());
        assertNull(r.misnamedLocations().get(0).matchedAlias());
    }

    @Test
    void excludesPathsThatContainCanonical() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        long t1 = titleRepo.save(title("OK-001", aid)).getId();
        long t2 = titleRepo.save(title("BAD-001", aid)).getId();
        locationRepo.save(loc(t1, "vol-a", "/stars/library/Nami Aino/OK-001"));
        locationRepo.save(loc(t2, "vol-a", "/archive/Aino Nami (BAD-001)"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(args(aid, 100));
        assertEquals(1, r.count());
        assertEquals("BAD-001", r.misnamedLocations().get(0).titleCode());
    }

    @Test
    void resolvesByName() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        actressRepo.saveAlias(new ActressAlias(aid, "Aino Nami"));
        long tid = titleRepo.save(title("SKY-283", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/archive/Aino Nami (SKY-283)"));

        ObjectNode n = M.createObjectNode();
        n.put("name", "Aino Nami"); // resolve via alias
        n.put("limit", 100);
        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(n);
        assertEquals(aid, r.actressId());
        assertEquals(1, r.count());
    }

    @Test
    void rejectsMissingActress() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(99999L, 100)));
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static TitleLocation loc(long titleId, String volumeId, String path) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build();
    }

    // ── strict-mode tests ───────────────────────────────────────────────────

    @Test
    void strictFalsePreservesSubstringBehavior() throws Exception {
        // "Sumire Yuki" is a substring of "Sumire Yukie (CODE)" → non-strict treats it as clean
        long aid = actressRepo.save(mk("Sumire Yuki")).getId();
        long tid = titleRepo.save(title("TSK-001", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Sumire Yukie (TSK-001)"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(argsStrict(aid, 100, false));
        // Non-strict: "Sumire Yuki" found inside "Sumire Yukie" → NOT reported as misnamed
        assertEquals(0, r.count(), "non-strict: substring match should report clean");
    }

    @Test
    void strictTrueCatchesSubstringFalsePositive() throws Exception {
        // "Sumire Yuki" is a substring of "Sumire Yukie (CODE)" → strict must flag this
        long aid = actressRepo.save(mk("Sumire Yuki")).getId();
        long tid = titleRepo.save(title("TSK-001", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Sumire Yukie (TSK-001)"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(argsStrict(aid, 100, true));
        assertEquals(1, r.count(), "strict: 'Sumire Yukie' is NOT token-bounded 'Sumire Yuki' → must be flagged");
        assertEquals("TSK-001", r.misnamedLocations().get(0).titleCode());
    }

    @Test
    void strictTruePreservesTrueMatch() throws Exception {
        // "Sumire Yuki (CODE)" contains canonical "Sumire Yuki" with proper boundaries → clean
        long aid = actressRepo.save(mk("Sumire Yuki")).getId();
        long tid = titleRepo.save(title("TSK-002", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Sumire Yuki (TSK-002)"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(argsStrict(aid, 100, true));
        assertEquals(0, r.count(), "strict: canonical bounded by '(' → should be clean");
    }

    @Test
    void strictTrueTreatsSlashBoundedMatchAsClean() throws Exception {
        // "/Sumire Yuki/TSK-003" — canonical followed by / → clean
        long aid = actressRepo.save(mk("Sumire Yuki")).getId();
        long tid = titleRepo.save(title("TSK-003", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Sumire Yuki/TSK-003"));

        var r = (FindMisnamedFoldersForActressTool.Result) tool.call(argsStrict(aid, 100, true));
        assertEquals(0, r.count(), "strict: canonical followed by '/' → should be clean");
    }

    // ── buildBoundaryPattern unit tests ─────────────────────────────────────

    @Test
    void boundaryPatternMatchesBoundedOccurrence() {
        var p = FindMisnamedFoldersForActressTool.buildBoundaryPattern("Sumire Yuki");
        assertTrue(p.matcher("/stars/Sumire Yuki (TSK-001)").find());
        assertTrue(p.matcher("/stars/Sumire Yuki/TSK-001").find());
        assertTrue(p.matcher("Sumire Yuki (TSK-001)").find()); // at start
    }

    @Test
    void boundaryPatternDoesNotMatchSubstringEmbeddedInLongerName() {
        var p = FindMisnamedFoldersForActressTool.buildBoundaryPattern("Sumire Yuki");
        assertFalse(p.matcher("/stars/Sumire Yukie (TSK-001)").find());
    }

    private static ObjectNode args(long actressId, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        n.put("limit", limit);
        return n;
    }

    private static ObjectNode argsStrict(long actressId, int limit, boolean strict) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        n.put("limit", limit);
        n.put("strict", strict);
        return n;
    }
}
