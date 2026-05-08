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

class FindSimilarActressesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository repo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private FindSimilarActressesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')"));
        repo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        tool = new FindSimilarActressesTool(repo, jdbi);
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

    // ── volume_id filter tests ───────────────────────────────────────────────

    @Test
    void volumeIdIncludesPairWhenOneSideHasTitlesOnVolume() throws Exception {
        // Pair: "Yua Mikami" (has titles on vol-a) / "Yua Mikarni" (titles only on vol-b)
        // ≥1 side condition: "Yua Mikami" is on vol-a → pair must surface
        Actress a = repo.save(mk("Yua Mikami"));
        Actress b = repo.save(mk("Yua Mikarni"));
        long ta = titleRepo.save(title("VOL-001", a.getId())).getId();
        locationRepo.save(loc(ta, "vol-a", "/stars/Yua Mikami/VOL-001"));
        long tb = titleRepo.save(title("VOL-002", b.getId())).getId();
        locationRepo.save(loc(tb, "vol-b", "/stars/Yua Mikarni/VOL-002"));

        var r = (FindSimilarActressesTool.Result) tool.call(argsWithVolume(2, 4, 100, "vol-a"));
        assertTrue(r.count() >= 1, "pair must surface because one side (Yua Mikami) has titles on vol-a");
    }

    @Test
    void volumeIdExcludesPairWhenNeitherSideHasTitlesOnVolume() throws Exception {
        // Both actresses only have titles on vol-b, not vol-a → excluded when filtering by vol-a
        Actress a = repo.save(mk("Yua Mikami"));
        Actress b = repo.save(mk("Yua Mikarni"));
        long ta = titleRepo.save(title("VOL-003", a.getId())).getId();
        locationRepo.save(loc(ta, "vol-b", "/stars/Yua Mikami/VOL-003"));
        long tb = titleRepo.save(title("VOL-004", b.getId())).getId();
        locationRepo.save(loc(tb, "vol-b", "/stars/Yua Mikarni/VOL-004"));

        var r = (FindSimilarActressesTool.Result) tool.call(argsWithVolume(2, 4, 100, "vol-a"));
        assertEquals(0, r.count(), "pair must be excluded: neither side has titles on vol-a");
    }

    @Test
    void volumeIdNullReturnsAllPairs() throws Exception {
        repo.save(mk("Yua Mikami"));
        repo.save(mk("Yua Mikarni"));

        // No volume_id → same as default behavior
        var r = (FindSimilarActressesTool.Result) tool.call(args(2, 4, 100));
        assertTrue(r.count() >= 1, "without volume filter all pairs should surface");
    }

    // ── require_full_name filter tests ───────────────────────────────────────

    @Test
    void requireFullNameFiltersOutSingleTokenPairs() throws Exception {
        repo.save(mk("ichika"));
        repo.save(mk("ichixa")); // single-token pair, distance=1

        var r = (FindSimilarActressesTool.Result) tool.call(argsWithFullName(2, 4, 100, true));
        assertEquals(0, r.count(), "single-token names must be filtered when require_full_name=true");
    }

    @Test
    void requireFullNamePreservesMultiTokenPairs() throws Exception {
        repo.save(mk("Yua Mikami"));
        repo.save(mk("Yua Mikarni"));

        var r = (FindSimilarActressesTool.Result) tool.call(argsWithFullName(2, 4, 100, true));
        assertTrue(r.count() >= 1, "multi-token pairs should survive require_full_name=true");
    }

    @Test
    void requireFullNameFalseDefaultPreservesSingleTokenPairs() throws Exception {
        repo.save(mk("ichika"));
        repo.save(mk("ichixa")); // distance=1

        var r = (FindSimilarActressesTool.Result) tool.call(argsWithFullName(2, 4, 100, false));
        assertTrue(r.count() >= 1, "require_full_name=false should not filter single-token names");
    }

    // ── isFullName unit tests ────────────────────────────────────────────────

    @Test
    void isFullNameReturnsTrueForTwoTokens() {
        assertTrue(FindSimilarActressesTool.isFullName("Yua Mikami"));
        assertTrue(FindSimilarActressesTool.isFullName("  Yua  Mikami  ")); // extra whitespace
    }

    @Test
    void isFullNameReturnsFalseForSingleToken() {
        assertFalse(FindSimilarActressesTool.isFullName("Ichika"));
        assertFalse(FindSimilarActressesTool.isFullName("   "));
        assertFalse(FindSimilarActressesTool.isFullName(null));
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

    private static ObjectNode args(int maxDist, int minLen, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("max_distance", maxDist);
        n.put("min_length", minLen);
        n.put("limit", limit);
        return n;
    }

    private static ObjectNode argsWithVolume(int maxDist, int minLen, int limit, String volumeId) {
        ObjectNode n = args(maxDist, minLen, limit);
        if (volumeId != null) n.put("volume_id", volumeId);
        return n;
    }

    private static ObjectNode argsWithFullName(int maxDist, int minLen, int limit, boolean requireFullName) {
        ObjectNode n = args(maxDist, minLen, limit);
        n.put("require_full_name", requireFullName);
        return n;
    }
}
