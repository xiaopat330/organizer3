package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.TitleCodeParser;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RecodeTitleToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private EnrichmentHistoryRepository enrichmentHistory;
    private SessionContext session;
    private TitleCodeParser codeParser;
    private RecodeTitleTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));

        actressRepo   = new JdbiActressRepository(jdbi);
        locationRepo  = new JdbiTitleLocationRepository(jdbi);
        titleRepo     = new JdbiTitleRepository(jdbi, locationRepo);
        enrichmentHistory = new EnrichmentHistoryRepository(jdbi, new ObjectMapper());
        codeParser    = new TitleCodeParser();
        session       = mock(SessionContext.class);
        when(session.getMountedVolumeId()).thenReturn(null);
        when(session.getActiveConnection()).thenReturn(null);
        tool = new RecodeTitleTool(jdbi, session, titleRepo, locationRepo, enrichmentHistory, codeParser);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── dry-run ──────────────────────────────────────────────────────────────

    @Test
    void dryRunDoesNotMutateDb() {
        long id = saveTitle("ABC-001");

        var r = (RecodeTitleTool.Result) tool.call(args(id, "ABC-002", true, false));

        assertTrue(r.dryRun());
        assertEquals("ABC-001", titleRepo.findById(id).orElseThrow().getCode());
    }

    @Test
    void dryRunReturnsExpectedFields() {
        long id = saveTitle("ABC-001");

        var r = (RecodeTitleTool.Result) tool.call(args(id, "ABC-002", true, false));

        assertEquals("ABC-001",   r.oldCode());
        assertEquals("ABC-002",   r.newCode());
        assertEquals("ABC",       r.newLabel());
        assertEquals(2,           r.newSeqNum());
        assertEquals("ABC-00002", r.newBaseCode());
    }

    // ── execution — DB side ──────────────────────────────────────────────────

    @Test
    void executeUpdatesCode() {
        long id = saveTitle("ABC-001");

        tool.call(args(id, "ABC-002", false, false));

        Title updated = titleRepo.findById(id).orElseThrow();
        assertEquals("ABC-002",   updated.getCode());
        assertEquals("ABC-00002", updated.getBaseCode());
        assertEquals("ABC",       updated.getLabel());
        assertEquals(2,           updated.getSeqNum());
    }

    @Test
    void executeUpdatesLocationPath() {
        long id = saveTitle("ABC-001");
        saveLocation(id, "/vol-a/Actress Name ABC-001");

        tool.call(args(id, "ABC-002", false, false));

        List<TitleLocation> locs = locationRepo.findByTitle(id);
        assertEquals(1, locs.size());
        assertEquals("/vol-a/Actress Name ABC-002", locs.get(0).getPath().toString());
    }

    @Test
    void executeSnapshotsEnrichmentHistory() {
        long id = saveTitle("ABC-001");
        // Insert a synthetic enrichment row directly
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO title_javdb_enrichment
                    (title_id, javdb_slug, confidence, fetched_at)
                VALUES (?, 'abc-001', 'HIGH', datetime('now'))
                """, id));

        tool.call(args(id, "ABC-002", false, false));

        assertEquals(1, enrichmentHistory.countForTitle(id));
        var history = enrichmentHistory.recentForTitle(id, 1);
        assertEquals("recode", history.get(0).reason());
    }

    @Test
    void executeWithNoEnrichmentDoesNotSnapshot() {
        long id = saveTitle("ABC-001");

        tool.call(args(id, "ABC-002", false, false));

        assertEquals(0, enrichmentHistory.countForTitle(id));
    }

    // ── path computation ─────────────────────────────────────────────────────

    @Test
    void computeNewPathReplacesCodeAtEnd() {
        Path old = Path.of("/vol/Actress Name ABC-001");
        Path result = RecodeTitleTool.computeNewPath(old, "ABC-001", "ABC-002");
        assertEquals(Path.of("/vol/Actress Name ABC-002"), result);
    }

    @Test
    void computeNewPathHandlesCodeOnlyFolder() {
        Path old = Path.of("/vol/ABC-001");
        Path result = RecodeTitleTool.computeNewPath(old, "ABC-001", "ABC-002");
        assertEquals(Path.of("/vol/ABC-002"), result);
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    void rejectsSameCode() {
        long id = saveTitle("ABC-001");
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "abc-001", false, false)));
    }

    @Test
    void rejectsInvalidCode() {
        long id = saveTitle("ABC-001");
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "not-a-jav-code", false, false)));
    }

    @Test
    void rejectsCodeTakenByAnotherTitle() {
        long id1 = saveTitle("ABC-001");
        long id2 = saveTitle("ABC-002");
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id1, "ABC-002", false, false)));
    }

    @Test
    void rejectsMissingTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(9999L, "ABC-002", false, false)));
    }

    // ── false-positive guard ─────────────────────────────────────────────────

    @Test
    void recodeDoesNotTouchOtherTitle() {
        long target    = saveTitle("ABC-001");
        long unrelated = saveTitle("XYZ-001");
        saveLocation(target,    "/vol/Actress ABC-001");
        saveLocation(unrelated, "/vol/Actress XYZ-001");

        tool.call(args(target, "ABC-002", false, false));

        // Unrelated title untouched
        assertEquals("XYZ-001", titleRepo.findById(unrelated).orElseThrow().getCode());
        List<TitleLocation> uLocs = locationRepo.findByTitle(unrelated);
        assertEquals("/vol/Actress XYZ-001", uLocs.get(0).getPath().toString());
    }

    // ── disk rename (mocked fs) ───────────────────────────────────────────────

    @Test
    void executeWithRenameDiskCallsFsRename() throws Exception {
        long id = saveTitle("ABC-001");
        saveLocation(id, "/vol/Actress ABC-001");

        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        when(fs.exists(any())).thenReturn(false);
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        when(conn.fileSystem()).thenReturn(fs);
        when(session.getMountedVolumeId()).thenReturn("vol-a");
        when(session.getActiveConnection()).thenReturn(conn);

        tool.call(args(id, "ABC-002", false, true));

        verify(fs).rename(Path.of("/vol/Actress ABC-001"), "Actress ABC-002");
    }

    @Test
    void executeRefusesWhenDestinationExists() throws Exception {
        long id = saveTitle("ABC-001");
        saveLocation(id, "/vol/Actress ABC-001");

        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        when(fs.exists(Path.of("/vol/Actress ABC-002"))).thenReturn(true);
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        when(conn.fileSystem()).thenReturn(fs);
        when(session.getMountedVolumeId()).thenReturn("vol-a");
        when(session.getActiveConnection()).thenReturn(conn);

        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "ABC-002", false, true)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long saveTitle(String code) {
        String label = code.split("-")[0];
        int seq = Integer.parseInt(code.split("-")[1]);
        String baseCode = String.format("%s-%05d", label, seq);
        return titleRepo.save(Title.builder()
                .code(code)
                .baseCode(baseCode)
                .label(label)
                .seqNum(seq)
                .build()).getId();
    }

    private void saveLocation(long titleId, String path) {
        locationRepo.save(TitleLocation.builder()
                .titleId(titleId)
                .volumeId("vol-a")
                .partitionId("part-1")
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .addedDate(LocalDate.now())
                .build());
    }

    private static ObjectNode args(long titleId, String newCode, boolean dryRun, boolean renameDisk) {
        ObjectNode n = M.createObjectNode();
        n.put("title_id",    titleId);
        n.put("new_code",    newCode);
        n.put("dry_run",     dryRun);
        n.put("rename_disk", renameDisk);
        return n;
    }
}
