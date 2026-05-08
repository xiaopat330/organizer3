package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindFsOnlyTitlesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private MapFs fs;
    private FindFsOnlyTitlesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo  = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);

        fs      = new MapFs();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("vol-a", "//host/vol-a", "conventional", "host", null));
        session.setActiveConnection(new FakeConn(fs));
        tool = new FindFsOnlyTitlesTool(session, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── title with no DB row → surfaced ──────────────────────────────────────

    @Test
    void titleWithNoDbRowIsSurfaced() throws Exception {
        // Folder on disk with a video, but no matching DB row
        fs.addDir("/stars/library/Some Actress");
        fs.addDir("/stars/library/Some Actress/Some Actress (ABP-999)");
        fs.addFile("/stars/library/Some Actress/Some Actress (ABP-999)/video.mkv", 1_000_000L);

        var result = (FindFsOnlyTitlesTool.Result) tool.call(noArgs());
        assertEquals(1, result.results().size());
        var r = result.results().get(0);
        assertEquals("/stars/library/Some Actress/Some Actress (ABP-999)", r.folderPath());
        assertEquals("ABP-999", r.parsedCode());
    }

    // ── title with DB row → excluded ─────────────────────────────────────────

    @Test
    void titleWithDbRowIsExcluded() throws Exception {
        // DB row exists for ABP-001
        long aid = actressRepo.save(actress("Test Actress")).getId();
        long tid = titleRepo.save(title("ABP-001", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Test Actress/Test Actress (ABP-001)"));

        fs.addDir("/stars/library/Test Actress");
        fs.addDir("/stars/library/Test Actress/Test Actress (ABP-001)");
        fs.addFile("/stars/library/Test Actress/Test Actress (ABP-001)/video.mkv", 500_000L);

        var result = (FindFsOnlyTitlesTool.Result) tool.call(noArgs());
        assertEquals(0, result.results().size());
    }

    // ── folder with no parseable code → surfaced with parsedCode null ────────

    @Test
    void folderWithNoParsableCodeSurfacedWithNullCode() throws Exception {
        fs.addDir("/queue");
        fs.addDir("/queue/just-a-mystery-folder");
        fs.addFile("/queue/just-a-mystery-folder/video.mp4", 200_000L);

        var result = (FindFsOnlyTitlesTool.Result) tool.call(noArgs());
        assertEquals(1, result.results().size());
        assertNull(result.results().get(0).parsedCode());
    }

    // ── mixed: one known, one unknown ────────────────────────────────────────

    @Test
    void mixedSurfacesOnlyUnknown() throws Exception {
        long aid = actressRepo.save(actress("Known Actress")).getId();
        long tid = titleRepo.save(title("KNOWN-001", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Known Actress/Known Actress (KNOWN-001)"));

        fs.addDir("/stars/library/Known Actress");
        fs.addDir("/stars/library/Known Actress/Known Actress (KNOWN-001)");
        fs.addFile("/stars/library/Known Actress/Known Actress (KNOWN-001)/video.mkv", 100_000L);
        fs.addDir("/stars/library/Known Actress/Known Actress (UNKNOWN-999)");
        fs.addFile("/stars/library/Known Actress/Known Actress (UNKNOWN-999)/video.mkv", 200_000L);

        var result = (FindFsOnlyTitlesTool.Result) tool.call(noArgs());
        assertEquals(1, result.results().size());
        assertEquals("UNKNOWN-999", result.results().get(0).parsedCode());
    }

    // ── no volume mounted → throws ────────────────────────────────────────────

    @Test
    void throwsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        assertThrows(IllegalArgumentException.class, () -> tool.call(noArgs()));
    }

    // ── size and childFileCount are populated ────────────────────────────────

    @Test
    void populatesSizeAndChildFileCount() throws Exception {
        fs.addDir("/queue");
        fs.addDir("/queue/Actress (NEW-123)");
        fs.addFile("/queue/Actress (NEW-123)/video.mkv", 3_000_000L);
        fs.addFile("/queue/Actress (NEW-123)/cover.jpg", 50_000L);

        var result = (FindFsOnlyTitlesTool.Result) tool.call(noArgs());
        assertEquals(1, result.results().size());
        var r = result.results().get(0);
        // sizeBytes counts only video files; childFileCount counts all non-directory children
        assertEquals(3_000_000L, r.sizeBytes());
        assertEquals(2, r.childFileCount());
    }

    // ── parsedCast extracted for known-format folders ─────────────────────────

    @Test
    void extractsCastForUnknownCode() throws Exception {
        fs.addDir("/queue");
        fs.addDir("/queue/First Actress, Second Actress (DUAL-001)");
        fs.addFile("/queue/First Actress, Second Actress (DUAL-001)/video.mkv", 1_000_000L);

        var result = (FindFsOnlyTitlesTool.Result) tool.call(noArgs());
        assertEquals(1, result.results().size());
        var r = result.results().get(0);
        assertEquals(List.of("First Actress", "Second Actress"), r.parsedCast());
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
                .code(code)
                .baseCode(code)
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static com.organizer3.model.TitleLocation loc(long titleId, String volumeId, String path) {
        return com.organizer3.model.TitleLocation.builder()
                .titleId(titleId)
                .volumeId(volumeId)
                .partitionId("p")
                .path(java.nio.file.Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }

    private static ObjectNode noArgs() {
        return M.createObjectNode();
    }

    // ── fake filesystem ───────────────────────────────────────────────────────

    /** MapFs: in-memory filesystem — directories and files registered by path. */
    static final class MapFs implements VolumeFileSystem {

        // Maps parent path → list of direct children
        private final Map<String, List<Path>> childMap = new HashMap<>();
        // Directories registered
        private final java.util.Set<String>   dirs     = new java.util.HashSet<>();
        // Files: path → size
        private final Map<String, Long>        files    = new HashMap<>();

        void addDir(String path) {
            Path p = Path.of(path);
            // Ensure the full ancestor chain is registered as directories
            registerAncestors(p);
            dirs.add(path);
            Path parent = p.getParent();
            if (parent != null) {
                List<Path> siblings = childMap.computeIfAbsent(parent.toString(), k -> new ArrayList<>());
                if (!siblings.contains(p)) siblings.add(p);
            }
            // Ensure this dir's own child list exists (even if empty)
            childMap.computeIfAbsent(path, k -> new ArrayList<>());
        }

        void addFile(String path, long size) {
            files.put(path, size);
            Path p = Path.of(path);
            Path parent = p.getParent();
            if (parent != null) {
                List<Path> siblings = childMap.computeIfAbsent(parent.toString(), k -> new ArrayList<>());
                if (!siblings.contains(p)) siblings.add(p);
            }
        }

        private void registerAncestors(Path p) {
            Path parent = p.getParent();
            if (parent == null) return;
            String parentStr = parent.toString();
            if (!dirs.contains(parentStr)) {
                dirs.add(parentStr);
                childMap.computeIfAbsent(parentStr, k -> new ArrayList<>());
                // Register this ancestor as a child of its own parent
                Path grandparent = parent.getParent();
                if (grandparent != null) {
                    List<Path> gpChildren = childMap.computeIfAbsent(grandparent.toString(), k -> new ArrayList<>());
                    if (!gpChildren.contains(parent)) gpChildren.add(parent);
                }
                registerAncestors(parent);
            }
        }

        @Override
        public List<Path> listDirectory(Path path) {
            return childMap.getOrDefault(path.toString(), List.of());
        }

        @Override
        public List<Path> walk(Path root) {
            // Not needed for C4
            return List.of();
        }

        @Override
        public boolean exists(Path path) {
            return dirs.contains(path.toString()) || files.containsKey(path.toString());
        }

        @Override
        public boolean isDirectory(Path path) {
            return dirs.contains(path.toString());
        }

        @Override
        public long size(Path path) {
            Long s = files.get(path.toString());
            return s != null ? s : 0L;
        }

        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }

    private static final class FakeConn implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConn(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }
}
