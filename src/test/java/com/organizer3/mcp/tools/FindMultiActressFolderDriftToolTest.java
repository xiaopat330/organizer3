package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FindMultiActressFolderDriftTool (C3).
 *
 * <p>The tool uses DB title_locations to find folders; no FS walk needed for core logic.
 * All test cases seed title_locations rows and actress DB entries, then verify drift detection.
 */
class FindMultiActressFolderDriftToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private FindMultiActressFolderDriftTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol', 'conventional')"));
        actressRepo  = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);

        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("vol", "//host/vol", "conventional", "host", null));
        session.setActiveConnection(new FakeConn());
        tool = new FindMultiActressFolderDriftTool(session, actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Clean multi-actress folder (no drift) → not surfaced ─────────────────

    @Test
    void cleanMultiActressFolderNotSurfaced() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long a2 = actressRepo.save(actress("Bob")).getId();
        long tid = titleRepo.save(title("DUAL-001", a1)).getId();
        // Add multi-actress credit
        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, a2));
        // Folder basename matches DB credits: Alice is pos-0 (primary), Bob is pos-1
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice, Bob (DUAL-001)"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        // No drift: parsed cast [Alice, Bob] matches DB [Alice, Bob]
        assertTrue(result.drifts().isEmpty(),
                "Clean multi-actress folder should not be surfaced");
    }

    // ── Single misspelled position → flagged with closestActress hint ─────────

    @Test
    void misspelledPositionFlagged() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long a2 = actressRepo.save(actress("Carol")).getId();
        long tid = titleRepo.save(title("DUAL-002", a1)).getId();
        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, a2));
        // Folder has "Carrol" (misspelling of Carol) — unresolvable, will get closestActress hint
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice, Carrol (DUAL-002)"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(1, result.drifts().size());
        var drift = result.drifts().get(0);
        assertTrue(drift.issues().contains("unresolvable-name"), "Misspelled Carrol should be unresolvable");
        // closestActress hint should point to Carol
        var unresolvedEntry = drift.parsed().cast().stream()
                .filter(ce -> ce.raw().equals("Carrol")).findFirst();
        assertTrue(unresolvedEntry.isPresent());
        assertEquals("Carol", unresolvedEntry.get().closestActress());
        assertNotNull(unresolvedEntry.get().closestDistance());
    }

    // ── Missing cast member → flagged ────────────────────────────────────────

    @Test
    void missingCastMemberFlagged() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long a2 = actressRepo.save(actress("Bob")).getId();
        long tid = titleRepo.save(title("MISS-001", a1)).getId();
        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, a2));
        // Folder only mentions Alice; Bob is missing from basename
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice (MISS-001)"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(1, result.drifts().size());
        assertTrue(result.drifts().get(0).issues().contains("missing-cast-member"));
    }

    // ── Non-standard separator (& and +) → warning surfaced ──────────────────

    @Test
    void nonStandardSeparatorFlagged() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long tid = titleRepo.save(title("SEP-001", a1)).getId();
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice & Bob (SEP-001)"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(1, result.drifts().size());
        assertTrue(result.drifts().get(0).issues().contains("non-standard-separator"));
    }

    @Test
    void plusSeparatorFlagged() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long tid = titleRepo.save(title("SEP-002", a1)).getId();
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice + Bob (SEP-002)"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(1, result.drifts().size());
        assertTrue(result.drifts().get(0).issues().contains("non-standard-separator"));
    }

    // ── Trailing tag preserved ────────────────────────────────────────────────

    @Test
    void trailingTagPreserved() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long tid = titleRepo.save(title("TAG-001", a1)).getId();
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice (TAG-001) [4K]"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        // Even if no drift in cast, we want to verify parsing; clean single-actress with [4K] tag
        // has no issues → not in drifts. But we can test the parser indirectly via unresolvable title.
        // For direct test, use a folder with an issue + trailing tag:
        // Let's verify clean single-actress title is NOT surfaced (no drift)
        assertTrue(result.drifts().isEmpty(),
                "Alice (TAG-001) [4K] with correct DB credit should not drift");
    }

    @Test
    void trailingTagPreservedInParsedInfo() throws Exception {
        // Create a drift so we can inspect the ParsedInfo
        long a1 = actressRepo.save(actress("Alice")).getId();
        long a2 = actressRepo.save(actress("Bob")).getId();
        long tid = titleRepo.save(title("TAG-002", a1)).getId();
        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, a2));
        // Missing Bob in basename; has trailing tag
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice (TAG-002) [4K]"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(1, result.drifts().size());
        assertEquals(" [4K]", result.drifts().get(0).parsed().trailingTag());
    }

    // ── Unparseable basename ──────────────────────────────────────────────────

    @Test
    void unparseableBasenameEmitsRecord() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long tid = titleRepo.save(title("UNK-001", a1)).getId();
        // No parenthesized code in basename
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/just-a-weird-name-no-code"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(1, result.drifts().size());
        var drift = result.drifts().get(0);
        assertTrue(drift.issues().contains("unparseable-basename"));
        assertNull(drift.parsed().code());
    }

    // ── Description with comma inside preserved correctly ─────────────────────

    @Test
    void descriptionWithCommaPreserved() throws Exception {
        long a1 = actressRepo.save(actress("Cast")).getId();
        long tid = titleRepo.save(title("DESC-001", a1)).getId();
        // "Cast - Fun, fun party (DESC-001)" → cast=["Cast"], description="Fun, fun party"
        locationRepo.save(loc(tid, "vol", "/stars/library/Cast/Cast - Fun, fun party (DESC-001)"));

        // This is a clean single-actress title → no drift
        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertTrue(result.drifts().isEmpty(), "Clean title with comma in description should not drift");
    }

    // ── Last " - " rule: "Cast - Description - more (CODE)" ──────────────────

    @Test
    void lastDashRuleSplitsCastCorrectly() throws Exception {
        long a1 = actressRepo.save(actress("Cast")).getId();
        long tid = titleRepo.save(title("DASH-001", a1)).getId();
        // "Cast - Description - more (DASH-001)" → cast=["Cast"], description="Description - more"
        locationRepo.save(loc(tid, "vol", "/stars/library/Cast/Cast - Description - more (DASH-001)"));

        // Clean single-actress title → no drift
        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertTrue(result.drifts().isEmpty(), "Last ' - ' rule: only the last separator splits cast");
    }

    // ── Severity sorting ──────────────────────────────────────────────────────

    @Test
    void driftsSortedByDescendingSeverity() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long a2 = actressRepo.save(actress("Bob")).getId();

        // Title 1: unparseable → severity 1.5
        long tid1 = titleRepo.save(title("SV1-001", a1)).getId();
        locationRepo.save(loc(tid1, "vol", "/stars/library/Alice/no-code-here"));

        // Title 2: non-standard-separator only → severity 0.1
        long tid2 = titleRepo.save(title("SV2-001", a1)).getId();
        locationRepo.save(loc(tid2, "vol", "/stars/library/Alice/Alice & Bob (SV2-001)"));

        var result = (FindMultiActressFolderDriftTool.Result) tool.call(noArgs());
        assertEquals(2, result.drifts().size());
        // Higher severity should be first
        assertTrue(result.drifts().get(0).severity() >= result.drifts().get(1).severity(),
                "Drifts should be sorted by descending severity");
    }

    // ── No volume mounted → throws ────────────────────────────────────────────

    @Test
    void throwsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noArgs()));
    }

    // ── BasenameParser unit tests ─────────────────────────────────────────────

    @Test
    void parserExtractsCodeAndCast() {
        var r = BasenameParser.parse("Alice, Bob (DUAL-001)");
        assertTrue(r.isParseable());
        assertEquals("DUAL-001", r.code());
        assertEquals(List.of("Alice", "Bob"), r.castTokens());
        assertNull(r.description());
        assertNull(r.trailingTag());
    }

    @Test
    void parserExtractsDescriptionWithLastDash() {
        var r = BasenameParser.parse("Cast - Description - more (CODE-001)");
        assertEquals("CODE-001", r.code());
        assertEquals(List.of("Cast"), r.castTokens());
        assertEquals("Description - more", r.description());
    }

    @Test
    void parserPreservesTrailingTag() {
        var r = BasenameParser.parse("Alice (TAG-001) [4K]");
        assertEquals("TAG-001", r.code());
        assertEquals(" [4K]", r.trailingTag());
    }

    @Test
    void parserHandlesAmbiguousCode() {
        // Two code-like patterns → ambiguous-code warning, picks rightmost
        var r = BasenameParser.parse("Alice (FIRST-001) - Desc (SECOND-002)");
        assertEquals("SECOND-002", r.code());
        assertTrue(r.warnings().contains("ambiguous-code"));
    }

    @Test
    void parserHandlesNonStandardSeparator() {
        var r = BasenameParser.parse("Alice & Bob (NS-001)");
        assertTrue(r.warnings().contains("non-standard-separator"));
        assertEquals(List.of("Alice", "Bob"), r.castTokens());
    }

    @Test
    void parserReturnsUnparseableForMissingCode() {
        var r = BasenameParser.parse("no-code-folder-name");
        assertFalse(r.isParseable());
        assertTrue(r.warnings().contains("unparseable-basename"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Actress actress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static com.organizer3.model.Title title(String code, long actressId) {
        return com.organizer3.model.Title.builder()
                .code(code).baseCode(code).label(code.split("-")[0]).seqNum(1)
                .actressId(actressId).build();
    }

    private static com.organizer3.model.TitleLocation loc(long titleId, String volumeId, String path) {
        return com.organizer3.model.TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build();
    }

    private static ObjectNode noArgs() {
        return M.createObjectNode();
    }

    // Minimal fake connection — C3 uses DB only, no FS walk
    private static final class FakeConn implements VolumeConnection {
        @Override public VolumeFileSystem fileSystem() { return new NoOpFs(); }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    private static final class NoOpFs implements VolumeFileSystem {
        @Override public java.util.List<Path> listDirectory(Path path) { return java.util.List.of(); }
        @Override public java.util.List<Path> walk(Path root) { return java.util.List.of(); }
        @Override public boolean exists(Path path) { return false; }
        @Override public boolean isDirectory(Path path) { return false; }
        @Override public long size(Path path) { return 0; }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
