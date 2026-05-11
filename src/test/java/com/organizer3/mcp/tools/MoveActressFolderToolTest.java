package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.command.ActressMergeService;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
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
import org.junit.jupiter.api.io.TempDir;

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

class MoveActressFolderToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private SessionContext session;
    private FlexibleFakeFs fs;
    private MoveActressFolderTool tool;

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

        // Source (/attention/...) exists by default; destination (/stars/...) does not.
        fs = new FlexibleFakeFs(/* attentionExists= */ true, /* starsExists= */ false);
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//host/a", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));

        tool = new MoveActressFolderTool(session, actressRepo, mergeService, Clock.systemUTC(),
                new CurationLog(logDir));
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Basic cases ───────────────────────────────────────────────────────────

    @Test
    void dryRun_returnsPlanWithoutMoving() {
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), true));

        assertEquals("dry-run", r.status());
        assertTrue(r.dryRun());
        assertEquals("/attention/Azusa Misaki", r.source());
        assertEquals("/stars/minor/Azusa Misaki", r.destination());
        assertEquals(1, r.locationsUpdated());
        assertTrue(fs.moveCalls.isEmpty(), "dry-run must not touch FS");
    }

    @Test
    void execute_movesActressFolderAndUpdatesDbPaths() {
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("moved", r.status());
        assertFalse(r.dryRun());
        assertEquals("minor", r.tier());
        assertEquals(1, fs.moveCalls.size());
        assertEquals(Path.of("/attention/Azusa Misaki"), fs.moveCalls.get(0).source());
        assertEquals(Path.of("/stars/minor/Azusa Misaki"), fs.moveCalls.get(0).destination());

        String newPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("/stars/minor/Azusa Misaki/Azusa Misaki (IPX-001)", newPath);

        String partition = jdbi.withHandle(h ->
                h.createQuery("SELECT partition_id FROM title_locations LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("minor", partition);
    }

    @Test
    void execute_createsParentDirBeforeMove() {
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");

        tool.call(args(a.getId(), false));

        // createDirectories should have been called for /stars/minor
        assertTrue(fs.createdDirs.contains(Path.of("/stars/minor")),
                "must create /stars/<tier> before moving");
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void refusesWhenSourceDoesNotExist() {
        fs = new FlexibleFakeFs(/* attentionExists= */ false, /* starsExists= */ false);
        session.setActiveConnection(new FakeConnection(fs));
        tool = new MoveActressFolderTool(session, actressRepo,
                new ActressMergeService(jdbi,
                        new JdbiTitleLocationRepository(jdbi), actressRepo),
                Clock.systemUTC(), new CurationLog(logDir));

        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("source-not-found", r.status());
        assertFalse(r.errors().isEmpty());
        assertTrue(fs.moveCalls.isEmpty(), "must not move when source missing");
    }

    @Test
    void refusesWhenDestinationAlreadyExists() {
        fs = new FlexibleFakeFs(/* attentionExists= */ true, /* starsExists= */ true);
        session.setActiveConnection(new FakeConnection(fs));
        tool = new MoveActressFolderTool(session, actressRepo,
                new ActressMergeService(jdbi,
                        new JdbiTitleLocationRepository(jdbi), actressRepo),
                Clock.systemUTC(), new CurationLog(logDir));

        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("collision", r.status());
        assertFalse(r.errors().isEmpty());
        assertTrue(r.errors().get(0).contains("collision"));
        assertTrue(fs.moveCalls.isEmpty(), "must not move on collision");
    }

    // ── Volume / session edge cases ───────────────────────────────────────────

    @Test
    void noMountedVolume_returnsDryRunStatus() {
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");
        session.setMountedVolume(null);
        session.setActiveConnection(null);

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("no-volume-mounted", r.status());
        assertTrue(fs.moveCalls.isEmpty());
    }

    @Test
    void skipsLocationsOnUnmountedVolume() {
        // Location is on volume "m" but we're mounted on "a"
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "m");

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), false));

        // Source /attention/Azusa Misaki/ exists on FS, but DB has no locations for volume "a",
        // so DB won't be updated. The FS move still happens, locationsUpdated=0.
        // (Mirror of to-attention tool: FS side is not volume-filtered, DB side is.)
        assertEquals("moved", r.status());
        assertEquals(0, r.locationsUpdated());
        assertEquals(1, fs.moveCalls.size());
    }

    // ── Resolve by name / alias ───────────────────────────────────────────────

    @Test
    void resolvesByCanonicalName() {
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");

        ObjectNode args = M.createObjectNode();
        args.put("name", "Azusa Misaki");
        args.put("dryRun", true);

        var r = (MoveActressFolderTool.Result) tool.call(args);
        assertEquals(a.getId(), r.actressId());
        assertEquals("dry-run", r.status());
    }

    @Test
    void resolvesByAlias() {
        Actress a = seedActressInAttention("Azusa Misaki", "IPX-001",
                "/attention/Azusa Misaki/Azusa Misaki (IPX-001)", "a");
        // Add an alias
        actressRepo.saveAlias(new ActressAlias(a.getId(), "Azuza Misaki"));

        ObjectNode args = M.createObjectNode();
        args.put("name", "Azuza Misaki");
        args.put("dryRun", true);

        var r = (MoveActressFolderTool.Result) tool.call(args);
        assertEquals(a.getId(), r.actressId());
    }

    // ── Validation ────────────────────────────────────────────────────────────

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

    // ── Multiple locations ────────────────────────────────────────────────────

    @Test
    void multipleLocations_allPathsUpdated() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Azusa Misaki").tier(Actress.Tier.MINOR)
                .firstSeenAt(LocalDate.now()).build());

        for (String code : List.of("IPX-001", "IPX-002")) {
            long titleId = jdbi.withHandle(h ->
                    h.createQuery("INSERT INTO titles (code, actress_id) VALUES (:c, :a) RETURNING id")
                            .bind("c", code).bind("a", a.getId()).mapTo(Long.class).one());
            jdbi.useHandle(h -> h.createUpdate("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (:t, 'a', 'attention', :p, '2024-01-01')
                    """)
                    .bind("t", titleId)
                    .bind("p", "/attention/Azusa Misaki/Azusa Misaki (" + code + ")")
                    .execute());
        }

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("moved", r.status());
        assertEquals(2, r.locationsUpdated());

        List<String> paths = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations ORDER BY path")
                        .mapTo(String.class).list());
        assertEquals(2, paths.size());
        assertTrue(paths.stream().allMatch(p -> p.startsWith("/stars/minor/Azusa Misaki/")));

        List<String> partitions = jdbi.withHandle(h ->
                h.createQuery("SELECT DISTINCT partition_id FROM title_locations")
                        .mapTo(String.class).list());
        assertEquals(List.of("minor"), partitions);
    }

    // ── Tier mapping ──────────────────────────────────────────────────────────

    @Test
    void popularTier_routesToCorrectStarsPath() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Top Star").tier(Actress.Tier.POPULAR)
                .firstSeenAt(LocalDate.now()).build());
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code, actress_id) VALUES (:c, :a) RETURNING id")
                        .bind("c", "ABC-001").bind("a", a.getId()).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (:t, 'a', 'attention', '/attention/Top Star/Top Star (ABC-001)', '2024-01-01')
                """).bind("t", titleId).execute());

        var r = (MoveActressFolderTool.Result) tool.call(args(a.getId(), true));

        assertEquals("popular", r.tier());
        assertEquals("/stars/popular/Top Star", r.destination());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Actress seedActressInAttention(String canonical, String code, String path, String volumeId) {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName(canonical).tier(Actress.Tier.MINOR)
                .firstSeenAt(LocalDate.now()).build());
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code, actress_id) VALUES (:c, :a) RETURNING id")
                        .bind("c", code).bind("a", a.getId()).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (:t, :v, 'attention', :p, '2024-01-01')
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

    /**
     * Flexible fake FS that lets tests control whether /attention/... and /stars/... exist.
     * Moves and createDirectories are recorded but not actually applied (no state mutation
     * for exists() — the exists() logic is fixed at construction time).
     */
    static final class FlexibleFakeFs implements VolumeFileSystem {
        record MoveCall(Path source, Path destination) {}
        final List<MoveCall> moveCalls = new ArrayList<>();
        final Set<Path> createdDirs = new HashSet<>();
        final Map<Path, byte[]> writtenFiles = new HashMap<>();

        private final boolean attentionExists;
        private final boolean starsExists;

        FlexibleFakeFs(boolean attentionExists, boolean starsExists) {
            this.attentionExists = attentionExists;
            this.starsExists = starsExists;
        }

        @Override public void move(Path source, Path destination) {
            moveCalls.add(new MoveCall(source, destination));
        }
        @Override public void createDirectories(Path path) { createdDirs.add(path); }
        @Override public void writeFile(Path path, byte[] contents) { writtenFiles.put(path, contents); }

        @Override public boolean exists(Path path) {
            String s = path.toString();
            if (s.startsWith("/attention/")) return attentionExists;
            if (s.startsWith("/stars/"))     return starsExists;
            return false;
        }

        @Override public boolean isDirectory(Path path) { return exists(path); }
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
