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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenameActressFoldersToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private SessionContext session;
    private FakeFs fs;
    private RenameActressFoldersTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('pool', 'queue')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('r', 'queue')");
        });
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        ActressMergeService mergeService = new ActressMergeService(jdbi, locationRepo, actressRepo);

        fs = new FakeFs();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("pool", "//host/pool", "queue", "host", null));
        session.setActiveConnection(new FakeConnection(fs));

        tool = new RenameActressFoldersTool(session, actressRepo, mergeService);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRun_returnsPlanWithoutCallingFs() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-052", "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        var r = (RenameActressFoldersTool.Result) tool.call(args(a.getId(), true));

        assertEquals(1, r.renamedCount());
        assertEquals(0, r.skippedCount());
        assertEquals(0, r.unresolvableCount());
        assertTrue(r.dryRun());
        assertTrue(fs.renameCalls.isEmpty(), "dry run must not touch FS");
    }

    @Test
    void executesRenameAndUpdatesDbPath() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-052", "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        var r = (RenameActressFoldersTool.Result) tool.call(args(a.getId(), false));

        assertEquals(1, r.renamedCount());
        assertEquals(1, fs.renameCalls.size());
        assertEquals(Path.of("/queue/Rin Hatchimitsu (FNS-052)"), fs.renameCalls.get(0).path);
        assertEquals("Rin Hachimitsu (FNS-052)", fs.renameCalls.get(0).newName);

        String newPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("/queue/Rin Hachimitsu (FNS-052)", newPath);
    }

    @Test
    void skipsLocationsOnUnmountedVolume() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-168", "/queue/Rin Hatchimitsu (FNS-168)", "r");
        // session is mounted on "pool"; location is on "r"

        var r = (RenameActressFoldersTool.Result) tool.call(args(a.getId(), false));

        assertEquals(0, r.renamedCount());
        assertEquals(1, r.skippedCount());
        assertEquals("r", r.skipped().get(0).volumeId());
        assertTrue(fs.renameCalls.isEmpty());
    }

    @Test
    void surfacesUnresolvablePaths() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-052", "/queue/FNS-052", "pool");

        var r = (RenameActressFoldersTool.Result) tool.call(args(a.getId(), true));

        assertEquals(0, r.renamedCount());
        assertEquals(1, r.unresolvableCount());
        assertEquals("/queue/FNS-052", r.unresolvable().get(0).currentPath());
    }

    @Test
    void resolvesByName() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-052", "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        ObjectNode args = M.createObjectNode();
        args.put("name", "Rin Hachimitsu");
        args.put("dryRun", true);

        var r = (RenameActressFoldersTool.Result) tool.call(args);
        assertEquals(a.getId(), r.actressId());
        assertEquals(1, r.renamedCount());
    }

    @Test
    void resolvesByAliasName() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-052", "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        ObjectNode args = M.createObjectNode();
        args.put("name", "Rin Hatchimitsu"); // alias
        args.put("dryRun", true);

        var r = (RenameActressFoldersTool.Result) tool.call(args);
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
    void noMountedVolume_treatsAllAsSkipped() {
        Actress a = seedActressWithMisnamedFolder("Rin Hachimitsu", "Rin Hatchimitsu",
                "FNS-052", "/queue/Rin Hatchimitsu (FNS-052)", "pool");
        session.setMountedVolume(null);
        session.setActiveConnection(null);

        var r = (RenameActressFoldersTool.Result) tool.call(args(a.getId(), false));

        assertEquals(0, r.renamedCount());
        assertEquals(1, r.skippedCount());
        assertNull(r.mountedVolumeId());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Actress seedActressWithMisnamedFolder(String canonical, String alias,
                                                   String code, String path, String volumeId) {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName(canonical).tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), alias));
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code, actress_id) VALUES (:c, :a) RETURNING id")
                        .bind("c", code).bind("a", a.getId()).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (:t, :v, 'queue', :p, '2024-01-01')
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

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class FakeFs implements VolumeFileSystem {
        record RenameCall(Path path, String newName) {}
        final List<RenameCall> renameCalls = new ArrayList<>();

        @Override public void rename(Path path, String newName) { renameCalls.add(new RenameCall(path, newName)); }
        @Override public List<Path> listDirectory(Path path) { return Collections.emptyList(); }
        @Override public List<Path> walk(Path root) { return Collections.emptyList(); }
        @Override public boolean exists(Path path) { return false; }
        @Override public boolean isDirectory(Path path) { return false; }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
