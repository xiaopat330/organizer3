package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.curation.CurationLog;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReassignTitleCreditToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private ReassignTitleCreditTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo = new JdbiActressRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        CurationLog curationLog = new CurationLog(tempDir);
        tool = new ReassignTitleCreditTool(titleRepo, actressRepo, titleActressRepo, jdbi, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void swapsCreditAndMovesFilingWhenFromWasFiling() {
        long a = saveActress("Actress A");
        long b = saveActress("Actress B");
        long titleId = saveTitle("SOE-793", a); // single credit A + filing A
        titleActressRepo.linkAll(titleId, List.of(a));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, a, b));

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("committed"));
        assertEquals(List.of(b), titleActressRepo.findActressIdsByTitle(titleId),
                "only B's credit should remain");
        assertEquals(Long.valueOf(b), titleRepo.findById(titleId).orElseThrow().getActressId());
        assertEquals(Long.valueOf(a), r.get("oldFilingActressId"));
        assertEquals(Long.valueOf(b), r.get("newFilingActressId"));
        assertEquals(Boolean.TRUE, r.get("filingChanged"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void swapsOneOfTwoCreditsFilingUnchangedWhenFromNotFiling() {
        long a = saveActress("Actress A");
        long b = saveActress("Actress B");
        long c = saveActress("Actress C");
        long titleId = saveTitle("SOE-793", b); // filing B (not being replaced)
        titleActressRepo.linkAll(titleId, List.of(a, b));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, a, c));

        assertEquals(Boolean.TRUE, r.get("ok"));
        List<Long> expected = List.of(Math.min(b, c), Math.max(b, c));
        assertEquals(expected, titleActressRepo.findActressIdsByTitle(titleId));
        assertEquals(Long.valueOf(b), titleRepo.findById(titleId).orElseThrow().getActressId(),
                "filing actress must be unchanged");
        assertEquals(Boolean.FALSE, r.get("filingChanged"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void swapWhenToAlreadyCreditedCollapsesToRemoval() {
        long a = saveActress("Actress A");
        long b = saveActress("Actress B");
        long titleId = saveTitle("SOE-793", a); // filing A, both A and B credited
        titleActressRepo.linkAll(titleId, List.of(a, b));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, a, b));

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("toAlreadyCredited"));
        assertEquals(List.of(b), titleActressRepo.findActressIdsByTitle(titleId));
        assertEquals(Long.valueOf(b), titleRepo.findById(titleId).orElseThrow().getActressId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesByCodeAndNamesCaseInsensitive() {
        long a = saveActress("Kotomi Nagisa");
        long b = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", a);
        titleActressRepo.linkAll(titleId, List.of(a));

        ObjectNode args = M.createObjectNode();
        args.put("title_code", "soe-793");
        args.put("from_actress_name", "Kotomi Nagisa");
        args.put("to_actress_name", "Ria Horisaki");
        args.put("dry_run", false);
        Map<String, Object> r = (Map<String, Object>) tool.call(args);

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(List.of(b), titleActressRepo.findActressIdsByTitle(titleId));
    }

    // ── error cases ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sameActressRejected() {
        long a = saveActress("Actress A");
        long titleId = saveTitle("SOE-793", a);
        titleActressRepo.linkAll(titleId, List.of(a));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, a, a));

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("same_actress", r.get("error"));
        assertEquals(List.of(a), titleActressRepo.findActressIdsByTitle(titleId), "unchanged");
    }

    @Test
    @SuppressWarnings("unchecked")
    void creditNotFoundWhenFromNotCredited() {
        long a = saveActress("Actress A");
        long b = saveActress("Actress B");
        long c = saveActress("Actress C");
        long titleId = saveTitle("SOE-793", a);
        titleActressRepo.linkAll(titleId, List.of(a)); // B not credited

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, b, c));

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("credit_not_found", r.get("error"));
        assertEquals(List.of(a), r.get("currentCredits"));
        assertEquals(List.of(a), titleActressRepo.findActressIdsByTitle(titleId), "unchanged");
    }

    @Test
    @SuppressWarnings("unchecked")
    void titleNotFound() {
        long a = saveActress("Actress A");
        long b = saveActress("Actress B");
        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(9999L, a, b));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("title_not_found", r.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fromActressNotFound() {
        long b = saveActress("Actress B");
        long titleId = saveTitle("SOE-793", b);
        titleActressRepo.linkAll(titleId, List.of(b));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, 9999L, b));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("from_actress_not_found", r.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toActressNotFound() {
        long a = saveActress("Actress A");
        long titleId = saveTitle("SOE-793", a);
        titleActressRepo.linkAll(titleId, List.of(a));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, a, 9999L));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("to_actress_not_found", r.get("error"));
    }

    // ── dry run ──────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void dryRunWritesNothing() {
        long a = saveActress("Actress A");
        long b = saveActress("Actress B");
        long titleId = saveTitle("SOE-793", a);
        titleActressRepo.linkAll(titleId, List.of(a));

        ObjectNode args = M.createObjectNode();
        args.put("title_id", titleId);
        args.put("from_actress_id", a);
        args.put("to_actress_id", b);
        // dry_run defaults to true
        Map<String, Object> r = (Map<String, Object>) tool.call(args);

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("dryRun"));
        assertEquals(Long.valueOf(a), r.get("oldFilingActressId"));
        assertEquals(Long.valueOf(b), r.get("newFilingActressId"));
        // DB unchanged
        assertEquals(List.of(a), titleActressRepo.findActressIdsByTitle(titleId), "no credit changed");
        assertEquals(Long.valueOf(a), titleRepo.findById(titleId).orElseThrow().getActressId(),
                "filing actress unchanged in dry-run");
    }

    @Test
    void missingArgsThrow() {
        ObjectNode noTitle = M.createObjectNode();
        noTitle.put("from_actress_id", 1);
        noTitle.put("to_actress_id", 2);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noTitle));

        ObjectNode noFrom = M.createObjectNode();
        noFrom.put("title_id", 1);
        noFrom.put("to_actress_id", 2);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noFrom));

        ObjectNode noTo = M.createObjectNode();
        noTo.put("title_id", 1);
        noTo.put("from_actress_id", 2);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noTo));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private ObjectNode liveArgsById(long titleId, long fromId, long toId) {
        ObjectNode a = M.createObjectNode();
        a.put("title_id", titleId);
        a.put("from_actress_id", fromId);
        a.put("to_actress_id", toId);
        a.put("dry_run", false);
        return a;
    }

    private long saveActress(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build()).getId();
    }

    private long saveTitle(String code, Long actressId) {
        return titleRepo.save(Title.builder()
                .code(code)
                .baseCode(code)
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build()).getId();
    }
}
