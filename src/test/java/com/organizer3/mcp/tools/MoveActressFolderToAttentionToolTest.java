package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.command.ActressMergeService;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MoveActressFolderToAttentionToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private SessionContext session;
    private AttentionFakeFs fs;
    private MoveActressFolderToAttentionTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('m', 'conventional')");
        });
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        ActressMergeService mergeService = new ActressMergeService(jdbi, locationRepo, actressRepo);

        fs = new AttentionFakeFs();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//host/a", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));

        tool = new MoveActressFolderToAttentionTool(session, actressRepo, mergeService, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRun_returnsPlanWithoutMoving() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "a");

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args(a.getId(), true));

        assertEquals("dry-run", r.status());
        assertTrue(r.dryRun());
        assertEquals(1, r.moved().size());
        assertEquals("/stars/minor/Asusa Misaki", r.moved().get(0).actressFolderFrom());
        assertEquals("/attention/Azusa Misaki", r.moved().get(0).actressFolderTo());
        assertTrue(fs.moveCalls.isEmpty(), "dry run must not touch FS");
    }

    @Test
    void execute_movesActressFolderAndUpdatesDbPaths() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "a");

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args(a.getId(), false));

        assertEquals("moved", r.status());
        assertFalse(r.dryRun());
        assertEquals(1, r.moved().size());
        assertEquals(1, fs.moveCalls.size());
        assertEquals(Path.of("/stars/minor/Asusa Misaki"), fs.moveCalls.get(0).source());
        assertEquals(Path.of("/attention/Azusa Misaki"), fs.moveCalls.get(0).destination());

        String newPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("/attention/Azusa Misaki/Azusa Misaki (IPX-001)", newPath);

        String partition = jdbi.withHandle(h ->
                h.createQuery("SELECT partition_id FROM title_locations LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("attention", partition);
    }

    @Test
    void execute_writesReasonSidecar() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "a");

        tool.call(args(a.getId(), false));

        Path expectedSidecar = Path.of("/attention/Azusa Misaki/REASON.txt");
        assertTrue(fs.writtenFiles.containsKey(expectedSidecar), "REASON.txt must be written");
        String content = new String(fs.writtenFiles.get(expectedSidecar));
        assertTrue(content.contains("reason: actress-folder-old-name"), "sidecar must include reason code");
        assertTrue(content.contains("canonicalName: Azusa Misaki"), "sidecar must include canonical name");
        assertTrue(content.contains("previousFolderName: Asusa Misaki"), "sidecar must include old folder name");
        assertTrue(content.contains("Asusa Misaki"), "sidecar body must mention old name");
    }

    @Test
    void nothingToDo_whenFolderAlreadyUsesCanonicalName() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Azusa Misaki/Azusa Misaki (IPX-001)", "a");
        // path already uses canonical name — parent basename matches canonical, not alias

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args(a.getId(), false));

        assertEquals("nothing-to-do", r.status());
        assertTrue(r.moved().isEmpty());
        assertTrue(fs.moveCalls.isEmpty());
    }

    @Test
    void skipsLocationsOnUnmountedVolume_noMoveOnWrongVolume() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "m");
        // session mounted on "a"; location is on "m"

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args(a.getId(), false));

        // plan returns empty entries for the mounted volume "a" (nothing on "a")
        assertEquals("nothing-to-do", r.status());
        assertTrue(fs.moveCalls.isEmpty());
    }

    @Test
    void noMountedVolume_returnsDryRunStatus() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "a");
        session.setMountedVolume(null);
        session.setActiveConnection(null);

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args(a.getId(), false));

        // no volume mounted → plan returns empty (volumeId is null)
        assertEquals("nothing-to-do", r.status());
        assertTrue(fs.moveCalls.isEmpty());
    }

    @Test
    void resolvesByName() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "a");

        ObjectNode args = M.createObjectNode();
        args.put("name", "Azusa Misaki");
        args.put("dryRun", true);

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args);
        assertEquals(a.getId(), r.actressId());
        assertEquals("dry-run", r.status());
    }

    @Test
    void resolvesByAliasName() {
        Actress a = seedActressWithMisnamedFolder("Azusa Misaki", "Asusa Misaki",
                "IPX-001", "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "a");

        ObjectNode args = M.createObjectNode();
        args.put("name", "Asusa Misaki");
        args.put("dryRun", true);

        var r = (MoveActressFolderToAttentionTool.Result) tool.call(args);
        assertEquals(a.getId(), r.actressId());
    }

    @Test
    void rejectsWhenNoIdAndNoName() {
        ObjectNode args = M.createObjectNode();
        args.put("dryRun", true);
        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }

    @Test
    void rejectsMissingActress() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(99999L, true)));
    }

    @Test
    void multipleLocations_allPathsUpdated() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Azusa Misaki").tier(Actress.Tier.MINOR)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), "Asusa Misaki"));

        // Two titles in the same misnamed actress folder
        for (String code : List.of("IPX-001", "IPX-002")) {
            long titleId = jdbi.withHandle(h ->
                    h.createQuery("INSERT INTO titles (code, actress_id) VALUES (:c, :a) RETURNING id")
                            .bind("c", code).bind("a", a.getId()).mapTo(Long.class).one());
            jdbi.useHandle(h -> h.createUpdate("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (:t, 'a', 'minor', :p, '2024-01-01')
                    """)
                    .bind("t", titleId)
                    .bind("p", "/stars/minor/Asusa Misaki/Azusa Misaki (" + code + ")")
                    .execute());
        }

        tool.call(args(a.getId(), false));

        List<String> paths = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations ORDER BY path")
                        .mapTo(String.class).list());
        assertEquals(2, paths.size());
        assertTrue(paths.stream().allMatch(p -> p.startsWith("/attention/Azusa Misaki/")));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Actress seedActressWithMisnamedFolder(String canonical, String alias,
                                                   String code, String path, String volumeId) {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName(canonical).tier(Actress.Tier.MINOR)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), alias));
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code, actress_id) VALUES (:c, :a) RETURNING id")
                        .bind("c", code).bind("a", a.getId()).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (:t, :v, 'minor', :p, '2024-01-01')
                """)
                .bind("t", titleId).bind("v", volumeId).bind("p", path).execute());
        return a;
    }

    private static ObjectNode args(long actressId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        n.put("dryRun", dryRun);
        return n;
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class AttentionFakeFs implements VolumeFileSystem {
        record MoveCall(Path source, Path destination) {}
        final List<MoveCall> moveCalls = new ArrayList<>();
        final Map<Path, byte[]> writtenFiles = new HashMap<>();
        final Set<Path> directories = new HashSet<>();

        @Override public void move(Path source, Path destination) {
            moveCalls.add(new MoveCall(source, destination));
            // Record that the destination directory now exists
            directories.add(destination);
        }
        @Override public void createDirectories(Path path) { directories.add(path); }
        @Override public void writeFile(Path path, byte[] contents) { writtenFiles.put(path, contents); }
        @Override public boolean exists(Path path) {
            return path.toString().startsWith("/stars/") || path.toString().startsWith("/queue/")
                    || directories.contains(path);
        }
        @Override public boolean isDirectory(Path path) {
            return path.toString().startsWith("/stars/") || path.toString().startsWith("/queue/")
                    || directories.contains(path);
        }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public List<Path> listDirectory(Path path) { return Collections.emptyList(); }
        @Override public List<Path> walk(Path root) { return Collections.emptyList(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
