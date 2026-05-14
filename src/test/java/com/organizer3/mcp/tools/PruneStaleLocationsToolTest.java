package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
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

class PruneStaleLocationsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private PruneStaleLocationsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);
        tool         = new PruneStaleLocationsTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── single-id prune ─────────────────────────────────────────────────────

    @Test
    void prunesSingleLocationById() {
        seedVolume("vol-a", "2024-06-01");
        long tid = titleRepo.save(title("STALE-001")).getId();
        TitleLocation loc = locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/stale")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsLocation(loc.getId(), false));
        assertFalse(r.dryRun());
        assertEquals(1, r.candidateCount());
        assertEquals(1, r.deletedCount());
        assertEquals(0, locationRepo.findByTitle(tid, true).size());
    }

    @Test
    void singleIdMissingReturnsError() {
        var r = (PruneStaleLocationsTool.Result) tool.call(argsLocation(9999L, false));
        assertNotNull(r.error());
        assertTrue(r.error().contains("9999"));
        assertEquals(0, r.deletedCount());
    }

    // ── batch prune ─────────────────────────────────────────────────────────

    @Test
    void batchPrunesOnlyStaleRowsOnVolume() {
        seedVolume("vol-a", "2024-06-01");
        long staleId = titleRepo.save(title("STALE-001")).getId();
        long freshId = titleRepo.save(title("FRESH-001")).getId();

        locationRepo.save(TitleLocation.builder()
                .titleId(staleId).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/stale")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(freshId).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/fresh")).lastSeenAt(LocalDate.of(2024, 6, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsVolume("vol-a", false));
        assertEquals(1, r.candidateCount());
        assertEquals(1, r.deletedCount());
        assertEquals("STALE-001", r.prunedLocations().get(0).titleCode());

        // fresh row untouched
        assertEquals(1, locationRepo.findByTitle(freshId).size());
        assertEquals(0, locationRepo.findByTitle(staleId).size());
    }

    @Test
    void batchIsScopedToVolume() {
        seedVolume("vol-a", "2024-06-01");
        seedVolume("vol-b", "2024-06-01");
        long ta = titleRepo.save(title("A-001")).getId();
        long tb = titleRepo.save(title("B-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(ta).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/a")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(tb).volumeId("vol-b").partitionId("p1")
                .path(Path.of("/b")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsVolume("vol-a", false));
        assertEquals(1, r.deletedCount());
        assertEquals(1, locationRepo.findByTitle(tb).size()); // vol-b survived
    }

    // ── dry run ─────────────────────────────────────────────────────────────

    @Test
    void dryRunReportsPlanWithoutDeleting() {
        seedVolume("vol-a", "2024-06-01");
        long tid = titleRepo.save(title("STALE-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/stale")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsVolume("vol-a", true));
        assertTrue(r.dryRun());
        assertEquals(1, r.candidateCount());
        assertEquals(0, r.deletedCount());
        assertEquals(1, locationRepo.findByTitle(tid).size());
    }

    @Test
    void dryRunDefaultsToTrue() {
        seedVolume("vol-a", "2024-06-01");
        long tid = titleRepo.save(title("STALE-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/stale")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        ObjectNode args = M.createObjectNode();
        args.put("volumeId", "vol-a");
        var r = (PruneStaleLocationsTool.Result) tool.call(args);
        assertTrue(r.dryRun());
        assertEquals(0, r.deletedCount());
    }

    // ── orphan surfacing ────────────────────────────────────────────────────

    @Test
    void surfacesOrphanedTitlesWhenLastLocationPruned() {
        seedVolume("vol-a", "2024-06-01");
        long tid = titleRepo.save(title("ORPH-001")).getId();
        TitleLocation loc = locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/orph")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsLocation(loc.getId(), false));
        assertEquals(1, r.orphanedTitles().size());
        assertEquals("ORPH-001", r.orphanedTitles().get(0).titleCode());
    }

    @Test
    void doesNotMarkOrphanWhenOtherLocationSurvives() {
        seedVolume("vol-a", "2024-06-01");
        seedVolume("vol-b", "2024-06-01");
        long tid = titleRepo.save(title("MULTI-001")).getId();
        TitleLocation stale = locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/stale")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-b").partitionId("p1")
                .path(Path.of("/other")).lastSeenAt(LocalDate.of(2024, 6, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsLocation(stale.getId(), false));
        assertEquals(1, r.deletedCount());
        assertEquals(0, r.orphanedTitles().size());
    }

    @Test
    void dryRunOrphanPreviewMatchesActual() {
        seedVolume("vol-a", "2024-06-01");
        long tid = titleRepo.save(title("PREV-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/p")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        var r = (PruneStaleLocationsTool.Result) tool.call(argsVolume("vol-a", true));
        assertTrue(r.dryRun());
        assertEquals(1, r.orphanedTitles().size());
        assertEquals("PREV-001", r.orphanedTitles().get(0).titleCode());
    }

    // ── argument validation ────────────────────────────────────────────────

    @Test
    void rejectsMissingArguments() {
        ObjectNode args = M.createObjectNode();
        var r = (PruneStaleLocationsTool.Result) tool.call(args);
        assertNotNull(r.error());
    }

    @Test
    void rejectsBothLocationIdAndVolumeId() {
        ObjectNode args = M.createObjectNode();
        args.put("locationId", 1L);
        args.put("volumeId", "vol-a");
        var r = (PruneStaleLocationsTool.Result) tool.call(args);
        assertNotNull(r.error());
        assertTrue(r.error().contains("mutually exclusive"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void seedVolume(String id, String lastSyncedAt) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES (?, 'conventional', ?)",
                id, lastSyncedAt));
    }

    private static Title title(String code) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .build();
    }

    private static ObjectNode argsVolume(String volumeId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("dryRun", dryRun);
        return n;
    }

    private static ObjectNode argsLocation(long id, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("locationId", id);
        n.put("dryRun", dryRun);
        return n;
    }
}
