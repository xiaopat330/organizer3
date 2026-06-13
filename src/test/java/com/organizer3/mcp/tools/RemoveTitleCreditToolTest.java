package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.AgeAtReleaseRecomputer;
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
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoveTitleCreditToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private AgeAtReleaseRecomputer mockRecomputer;
    private RemoveTitleCreditTool tool;

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
        mockRecomputer = Mockito.mock(AgeAtReleaseRecomputer.class);
        when(mockRecomputer.recomputeAll()).thenReturn(0);
        tool = new RemoveTitleCreditTool(titleRepo, actressRepo, titleActressRepo, jdbi, curationLog, mockRecomputer);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void removesOneCreditOfTwoAndReassignsFilingWhenRemovedWasFiling() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", ria); // filing actress = Ria (the one being removed)
        titleActressRepo.linkAll(titleId, List.of(kotomi, ria));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, ria));

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.FALSE, r.get("dryRun"));
        assertEquals(Boolean.TRUE, r.get("committed"));
        assertEquals(List.of(kotomi), titleActressRepo.findActressIdsByTitle(titleId),
                "only Kotomi's credit should remain");
        // filing actress reassigned to the remaining credit
        assertEquals(Long.valueOf(kotomi), titleRepo.findById(titleId).orElseThrow().getActressId());
        assertEquals(Long.valueOf(ria), r.get("oldFilingActressId"));
        assertEquals(Long.valueOf(kotomi), r.get("newFilingActressId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filingActressUnchangedWhenRemovedIsNotFiling() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", kotomi); // filing actress = Kotomi (NOT removed)
        titleActressRepo.linkAll(titleId, List.of(kotomi, ria));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, ria));

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(List.of(kotomi), titleActressRepo.findActressIdsByTitle(titleId));
        assertEquals(Long.valueOf(kotomi), titleRepo.findById(titleId).orElseThrow().getActressId(),
                "filing actress must be unchanged");
        assertEquals(Long.valueOf(kotomi), r.get("oldFilingActressId"));
        assertEquals(Long.valueOf(kotomi), r.get("newFilingActressId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesByCodeAndActressNameCaseInsensitive() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", null);
        titleActressRepo.linkAll(titleId, List.of(kotomi, ria));

        ObjectNode a = M.createObjectNode();
        a.put("title_code", "soe-793");
        a.put("actress_name", "Ria Horisaki");
        a.put("dry_run", false);
        Map<String, Object> r = (Map<String, Object>) tool.call(a);

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(List.of(kotomi), titleActressRepo.findActressIdsByTitle(titleId));
    }

    // ── last credit guard ──────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void blocksRemovingLastCreditWithoutForce() {
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", ria);
        titleActressRepo.linkAll(titleId, List.of(ria));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, ria));

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("would_leave_no_credits", r.get("error"));
        // nothing written
        assertEquals(List.of(ria), titleActressRepo.findActressIdsByTitle(titleId));
        assertEquals(Long.valueOf(ria), titleRepo.findById(titleId).orElseThrow().getActressId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void forceRemovesLastCreditAndNullsFiling() {
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", ria);
        titleActressRepo.linkAll(titleId, List.of(ria));

        ObjectNode a = liveArgsById(titleId, ria);
        a.put("force", true);
        Map<String, Object> r = (Map<String, Object>) tool.call(a);

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("willLeaveNoCredits"));
        assertTrue(titleActressRepo.findActressIdsByTitle(titleId).isEmpty(), "credit removed");
        assertNull(titleRepo.findById(titleId).orElseThrow().getActressId(),
                "filing actress set to null when no credits remain");
        assertNull(r.get("newFilingActressId"));
    }

    // ── error cases ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void creditNotFoundWhenActressNotCredited() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", kotomi);
        titleActressRepo.linkAll(titleId, List.of(kotomi));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, ria));

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("credit_not_found", r.get("error"));
        assertEquals(List.of(kotomi), r.get("currentCredits"));
        assertEquals(List.of(kotomi), titleActressRepo.findActressIdsByTitle(titleId), "unchanged");
    }

    @Test
    @SuppressWarnings("unchecked")
    void titleNotFound() {
        long ria = saveActress("Ria Horisaki");
        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(9999L, ria));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("title_not_found", r.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void actressNotFound() {
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", ria);
        titleActressRepo.linkAll(titleId, List.of(ria));

        Map<String, Object> r = (Map<String, Object>) tool.call(liveArgsById(titleId, 9999L));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("actress_not_found", r.get("error"));
    }

    // ── dry run ──────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void dryRunWritesNothing() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", ria);
        titleActressRepo.linkAll(titleId, List.of(kotomi, ria));

        ObjectNode a = M.createObjectNode();
        a.put("title_id", titleId);
        a.put("actress_id", ria);
        // dry_run defaults to true
        Map<String, Object> r = (Map<String, Object>) tool.call(a);

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("dryRun"));
        assertEquals(Long.valueOf(ria), r.get("oldFilingActressId"));
        assertEquals(Long.valueOf(kotomi), r.get("newFilingActressId"));
        // DB unchanged
        assertEquals(2, titleActressRepo.findActressIdsByTitle(titleId).size(), "no credit removed");
        assertEquals(Long.valueOf(ria), titleRepo.findById(titleId).orElseThrow().getActressId(),
                "filing actress unchanged in dry-run");
    }

    @Test
    void missingArgsThrow() {
        ObjectNode noTitle = M.createObjectNode();
        noTitle.put("actress_id", 1);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noTitle));

        ObjectNode noActress = M.createObjectNode();
        noActress.put("title_id", 1);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noActress));
    }

    // ── age_at_release recompute trigger tests ────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void recomputeCalledExactlyOnceOnLivePath() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", kotomi);
        titleActressRepo.linkAll(titleId, List.of(kotomi, ria));

        tool.call(liveArgsById(titleId, ria));

        verify(mockRecomputer, times(1)).recomputeAll();
    }

    @Test
    void recomputeNotCalledOnDryRun() {
        long kotomi = saveActress("Kotomi Nagisa");
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", kotomi);
        titleActressRepo.linkAll(titleId, List.of(kotomi, ria));

        ObjectNode a = M.createObjectNode();
        a.put("title_id", titleId);
        a.put("actress_id", ria);
        // dry_run defaults to true
        tool.call(a);

        verify(mockRecomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledOnRefused() {
        long ria = saveActress("Ria Horisaki");
        long titleId = saveTitle("SOE-793", ria);
        titleActressRepo.linkAll(titleId, List.of(ria));

        // no force — will be refused (would leave no credits)
        tool.call(liveArgsById(titleId, ria));

        verify(mockRecomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledOnNotFound() {
        long ria = saveActress("Ria Horisaki");
        // title doesn't exist
        tool.call(liveArgsById(9999L, ria));

        verify(mockRecomputer, never()).recomputeAll();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private ObjectNode liveArgsById(long titleId, long actressId) {
        ObjectNode a = M.createObjectNode();
        a.put("title_id", titleId);
        a.put("actress_id", actressId);
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
