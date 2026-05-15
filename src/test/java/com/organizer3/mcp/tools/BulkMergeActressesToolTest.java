package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BulkMergeActressesToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private BulkMergeActressesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        tool = new BulkMergeActressesTool(jdbi, actressRepo, new CurationLog(logDir));
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── 1. Two-merge happy path (both succeed) ──────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void twoMergeHappyPath() throws Exception {
        long into1 = actressRepo.save(mk("Actress A")).getId();
        long from1 = actressRepo.save(mk("Actress A Typo")).getId();
        long into2 = actressRepo.save(mk("Actress B")).getId();
        long from2 = actressRepo.save(mk("Actress B Typo")).getId();

        titleRepo.save(title("ABP-001", from1));
        titleRepo.save(title("ABP-002", from2));

        ObjectNode args = args(false, pair(into1, from1), pair(into2, from2));
        Map<String, Object> result = call(args);

        assertEquals(2, result.get("totalMerges"));
        assertEquals(2, result.get("successful"));
        assertEquals(0, result.get("failed"));
        assertFalse((Boolean) result.get("dryRun"));

        // Verify both merges actually executed
        assertTrue(actressRepo.findById(from1).isEmpty(), "from1 should be deleted");
        assertTrue(actressRepo.findById(from2).isEmpty(), "from2 should be deleted");
        assertEquals(1, titleRepo.findByActress(into1).size(), "title should move to into1");
        assertEquals(1, titleRepo.findByActress(into2).size(), "title should move to into2");

        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals(2, results.size());
        assertTrue((Boolean) results.get(0).get("ok"));
        assertTrue((Boolean) results.get(1).get("ok"));
    }

    // ── 2. Partial failure: bad from id — others still apply ────────────────

    @Test
    @SuppressWarnings("unchecked")
    void partialFailureContinuesRemainingMerges() throws Exception {
        long into1 = actressRepo.save(mk("Good Into")).getId();
        long from1 = actressRepo.save(mk("Good From")).getId();
        long into2 = actressRepo.save(mk("Another Into")).getId();
        long nonExistentFrom = 99999L;

        ObjectNode args = args(false,
                pair(into1, from1),
                pair(into2, nonExistentFrom));
        Map<String, Object> result = call(args);

        assertEquals(2, result.get("totalMerges"));
        assertEquals(1, result.get("successful"));
        assertEquals(1, result.get("failed"));

        // The successful merge executed despite the later failure
        assertTrue(actressRepo.findById(from1).isEmpty(), "from1 should be deleted");
        assertTrue(actressRepo.findById(into2).isPresent(), "into2 should still exist");

        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertTrue((Boolean) results.get(0).get("ok"), "pair 0 should succeed");
        assertFalse((Boolean) results.get(1).get("ok"), "pair 1 should fail");
        assertNotNull(results.get(1).get("error"), "failed pair should have error message");
        assertTrue(results.get(1).get("error").toString().contains("99999"),
                "error should mention the bad id");
    }

    // ── 3. Per-pair dropAliases flows through ────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void perPairDropAliasesApplied() throws Exception {
        long into = actressRepo.save(mk("Canon Name")).getId();
        long from = actressRepo.save(mk("Old Name")).getId();
        // Pre-seed an alias on 'from' that will be migrated, then we'll drop it
        actressRepo.saveAlias(new ActressAlias(from, "Alias To Keep"));
        actressRepo.saveAlias(new ActressAlias(from, "Alias To Drop"));

        ObjectNode pairNode = M.createObjectNode();
        pairNode.put("into", into);
        pairNode.put("from", from);
        ArrayNode drops = pairNode.putArray("dropAliases");
        drops.add("Alias To Drop");
        drops.add("Old Name"); // also drop the migrated canonical name

        ObjectNode args = args(false, pairNode);
        Map<String, Object> result = call(args);

        assertEquals(1, result.get("successful"));
        assertEquals(0, result.get("failed"));

        List<String> aliasNames = actressRepo.findAliases(into).stream()
                .map(ActressAlias::aliasName).toList();
        assertTrue(aliasNames.contains("Alias To Keep"), "kept alias should remain");
        assertFalse(aliasNames.contains("Alias To Drop"), "dropped alias should be gone");
        assertFalse(aliasNames.contains("Old Name"), "dropped canonical should be gone");
    }

    // ── 4. dryRun:true previews all pairs without mutating ──────────────────

    @Test
    @SuppressWarnings("unchecked")
    void dryRunPreviewsWithoutMutating() throws Exception {
        long into1 = actressRepo.save(mk("Preview Into 1")).getId();
        long from1 = actressRepo.save(mk("Preview From 1")).getId();
        long into2 = actressRepo.save(mk("Preview Into 2")).getId();
        long from2 = actressRepo.save(mk("Preview From 2")).getId();

        ObjectNode args = args(true, pair(into1, from1), pair(into2, from2));
        Map<String, Object> result = call(args);

        assertTrue((Boolean) result.get("dryRun"));
        assertEquals(2, result.get("successful"));
        assertEquals(0, result.get("failed"));

        // No mutations should have happened
        assertTrue(actressRepo.findById(from1).isPresent(), "from1 must survive dry-run");
        assertTrue(actressRepo.findById(from2).isPresent(), "from2 must survive dry-run");

        // Each result entry should have a plan
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        for (Map<String, Object> entry : results) {
            assertTrue((Boolean) entry.get("ok"));
            assertNotNull(entry.get("result"), "dry-run result should contain plan");
        }
    }

    // ── 4b. Explicit confirming test: bulk dry-run returns per-pair plans ────

    /**
     * Confirms the existing top-level {@code dryRun:true} on {@code bulk_merge_actresses}
     * already aggregates per-pair plans (one full {@link MergeActressesTool.Result} per pair)
     * and does not mutate any DB state. Verifies the documented use-case of previewing N
     * pending merges in a single call.
     */
    @Test
    @SuppressWarnings("unchecked")
    void bulkDryRun_returnsPerPairPlansWithoutMutating() throws Exception {
        // 3 pairs — matches the report sample shape
        long into1 = actressRepo.save(mk("Canon 1")).getId();
        long from1 = actressRepo.save(mk("Typo 1")).getId();
        long into2 = actressRepo.save(mk("Canon 2")).getId();
        long from2 = actressRepo.save(mk("Typo 2")).getId();
        long into3 = actressRepo.save(mk("Canon 3")).getId();
        long from3 = actressRepo.save(mk("Typo 3")).getId();

        // Seed a title on each 'from' to give the plan something to describe
        titleRepo.save(title("ABP-101", from1));
        titleRepo.save(title("ABP-102", from2));
        titleRepo.save(title("ABP-103", from3));

        // Snapshot pre-call state for strict no-mutation assertions
        int actressesBefore = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses").mapTo(Integer.class).one());
        int titlesBefore = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles").mapTo(Integer.class).one());

        ObjectNode args = args(true, pair(into1, from1), pair(into2, from2), pair(into3, from3));
        Map<String, Object> result = call(args);

        // Top-level shape
        assertTrue((Boolean) result.get("dryRun"));
        assertEquals(3, result.get("totalMerges"));
        assertEquals(3, result.get("successful"));
        assertEquals(0, result.get("failed"));

        // Per-pair plans
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals(3, results.size());
        long[] expectedInto = {into1, into2, into3};
        long[] expectedFrom = {from1, from2, from3};
        for (int i = 0; i < 3; i++) {
            Map<String, Object> entry = results.get(i);
            assertEquals(i, entry.get("index"));
            assertEquals(expectedInto[i], ((Number) entry.get("into")).longValue());
            assertEquals(expectedFrom[i], ((Number) entry.get("from")).longValue());
            assertTrue((Boolean) entry.get("ok"));
            Object plan = entry.get("result");
            assertNotNull(plan, "dry-run entry must include the merge Result");
            assertTrue(plan instanceof MergeActressesTool.Result,
                    "per-pair result should be a MergeActressesTool.Result");
            MergeActressesTool.Result mr = (MergeActressesTool.Result) plan;
            assertTrue(mr.dryRun(), "inner Result.dryRun must be true");
            assertNotNull(mr.plan(), "inner Plan must be present");
            assertNotNull(mr.plan().summary(), "Plan.summary must be present");
            assertNotNull(mr.plan().changes(), "Plan.changes must be present");
        }

        // Strict no-mutation: every 'from' survives, every title still points to its 'from'
        assertTrue(actressRepo.findById(from1).isPresent(), "from1 must survive dry-run");
        assertTrue(actressRepo.findById(from2).isPresent(), "from2 must survive dry-run");
        assertTrue(actressRepo.findById(from3).isPresent(), "from3 must survive dry-run");
        assertEquals(1, titleRepo.findByActress(from1).size(), "from1 title must not move");
        assertEquals(1, titleRepo.findByActress(from2).size(), "from2 title must not move");
        assertEquals(1, titleRepo.findByActress(from3).size(), "from3 title must not move");
        assertTrue(titleRepo.findByActress(into1).isEmpty(), "into1 must not gain titles");
        assertTrue(titleRepo.findByActress(into2).isEmpty(), "into2 must not gain titles");
        assertTrue(titleRepo.findByActress(into3).isEmpty(), "into3 must not gain titles");

        // Row counts unchanged
        int actressesAfter = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses").mapTo(Integer.class).one());
        int titlesAfter = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles").mapTo(Integer.class).one());
        assertEquals(actressesBefore, actressesAfter, "actresses row count must not change");
        assertEquals(titlesBefore, titlesAfter, "titles row count must not change");
    }

    // ── 5. Empty merges array returns zero-result without error ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void emptyMergesArrayReturnsZeroResult() throws Exception {
        ObjectNode args = M.createObjectNode();
        args.put("dryRun", false);
        args.putArray("merges"); // empty

        Map<String, Object> result = call(args);

        assertEquals(0, result.get("totalMerges"));
        assertEquals(0, result.get("successful"));
        assertEquals(0, result.get("failed"));
        List<?> results = (List<?>) result.get("results");
        assertTrue(results.isEmpty());
    }

    // ── 6. Missing merges field → IllegalArgumentException ──────────────────

    @Test
    void missingMergesFieldThrowsIllegalArgumentException() {
        ObjectNode args = M.createObjectNode();
        args.put("dryRun", false);
        // no "merges" field at all

        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }

    // ── 7. Result index matches pair order ───────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void resultIndexMatchesPairOrder() throws Exception {
        long into1 = actressRepo.save(mk("Indexed Into 1")).getId();
        long from1 = actressRepo.save(mk("Indexed From 1")).getId();
        long into2 = actressRepo.save(mk("Indexed Into 2")).getId();
        long from2 = actressRepo.save(mk("Indexed From 2")).getId();

        ObjectNode args = args(true, pair(into1, from1), pair(into2, from2));
        Map<String, Object> result = call(args);

        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals(0, results.get(0).get("index"));
        assertEquals(1, results.get(1).get("index"));
        assertEquals(into1, ((Number) results.get(0).get("into")).longValue());
        assertEquals(from1, ((Number) results.get(0).get("from")).longValue());
        assertEquals(into2, ((Number) results.get(1).get("into")).longValue());
        assertEquals(from2, ((Number) results.get(1).get("from")).longValue());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(ObjectNode args) {
        return (Map<String, Object>) tool.call(args);
    }

    private static ObjectNode args(boolean dryRun, ObjectNode... pairs) {
        ObjectNode args = M.createObjectNode();
        args.put("dryRun", dryRun);
        ArrayNode merges = args.putArray("merges");
        for (ObjectNode p : pairs) merges.add(p);
        return args;
    }

    private static ObjectNode pair(long intoId, long fromId) {
        ObjectNode p = M.createObjectNode();
        p.put("into", intoId);
        p.put("from", fromId);
        return p;
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
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
}
