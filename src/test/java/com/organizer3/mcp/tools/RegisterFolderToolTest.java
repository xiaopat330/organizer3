package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitlePathHistoryRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.repository.jdbi.JdbiVolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.FolderRegistrar;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.SyncIdentityMatcher;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RegisterFolderToolTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String VOLUME_ID = "s";
    private static final String OTHER_VOLUME_ID = "a";

    /** Minimal OrganizerConfig providing the conventional structure. */
    private static final OrganizerConfig ORGANIZER_CFG;
    static {
        VolumeStructureDef conventional = new VolumeStructureDef(
                "conventional",
                List.of(
                        new PartitionDef("queue",     "queue"),
                        new PartitionDef("attention", "attention"),
                        new PartitionDef("archive",   "archive")
                ),
                new StructuredPartitionDef("stars", List.of(
                        new PartitionDef("library",   "library"),
                        new PartitionDef("minor",     "minor"),
                        new PartitionDef("popular",   "popular"),
                        new PartitionDef("superstar", "superstar"),
                        new PartitionDef("goddess",   "goddess")
                ))
        );
        ORGANIZER_CFG = new OrganizerConfig(
                "test", "/tmp",
                100, 10, 10, 10, 4, 0,
                List.of(),
                List.of(new VolumeConfig(VOLUME_ID, "//host/s", "conventional", "host", null)),
                List.of(conventional),
                List.of(),
                null
        );
    }

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;
    private JdbiVolumeRepository volumeRepo;
    private FolderRegistrar registrar;
    private RegisterFolderTool tool;
    private SessionContext session;
    private FakeFS fs;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        // Insert volumes used in tests
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('" + VOLUME_ID + "', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('" + OTHER_VOLUME_ID + "', 'conventional')");
        });

        locationRepo     = new JdbiTitleLocationRepository(jdbi);
        titleRepo        = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo      = new JdbiActressRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        videoRepo        = new JdbiVideoRepository(jdbi);
        volumeRepo       = new JdbiVolumeRepository(jdbi);

        CoverPath coverPath          = new CoverPath(tempDir);
        TitleEffectiveTagsService te = new TitleEffectiveTagsService(jdbi);
        ActressCompaniesService   ac = new ActressCompaniesService(jdbi);
        IndexLoader indexLoader       = new IndexLoader(titleRepo, actressRepo);
        JdbiTitlePathHistoryRepository pathHistoryRepo = new JdbiTitlePathHistoryRepository(jdbi);
        SyncIdentityMatcher identityMatcher = new SyncIdentityMatcher(jdbi, null, pathHistoryRepo);

        registrar = new FolderRegistrar(titleRepo, videoRepo, actressRepo, volumeRepo,
                locationRepo, titleActressRepo, indexLoader, te, ac, coverPath,
                null /* revalidationPendingRepo */, identityMatcher, pathHistoryRepo);

        fs      = new FakeFS();
        session = mock(SessionContext.class);
        VolumeConnection conn = mock(VolumeConnection.class);
        when(session.getMountedVolumeId()).thenReturn(VOLUME_ID);
        when(session.getMountedVolume()).thenReturn(new VolumeConfig(VOLUME_ID, "//host/s", "conventional", "host", null));
        when(session.getActiveConnection()).thenReturn(conn);
        when(conn.isConnected()).thenReturn(true);
        when(conn.fileSystem()).thenReturn(fs);

        tool = new RegisterFolderTool(session, registrar, ORGANIZER_CFG);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: fresh registration — creates title, location, videos, infers cast
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void freshFolder_createsTitle_location_videos_andInfersCast() {
        // folder: /queue/Marin Yakuno (IPZZ-679)
        String folderPath = "/queue/Marin Yakuno (IPZZ-679)";
        fs.dir("/queue/Marin Yakuno (IPZZ-679)");
        fs.videoFile("/queue/Marin Yakuno (IPZZ-679)/ipzz679.mp4");

        Map<String, Object> r = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));

        assertTrue((Boolean) r.get("ok"), "should be ok: " + r.get("error"));
        assertTrue((Boolean) r.get("committed"));
        assertFalse((Boolean) r.get("dryRun"));
        assertEquals("IPZZ-679", r.get("code"));
        assertTrue((Boolean) r.get("isNewTitle"), "should be a new title");
        assertTrue((Boolean) r.get("castInferred"), "cast should be inferred for new title");
        assertEquals(List.of("ipzz679.mp4"), r.get("videosRegistered"));
        assertEquals(1, (int) (Integer) r.get("videoCount"));

        // Title created
        var title = titleRepo.findByCode("IPZZ-679");
        assertTrue(title.isPresent());
        long titleId = title.get().getId();

        // Location created, not stale
        var locations = locationRepo.findByTitle(titleId);
        assertEquals(1, locations.size());
        TitleLocation loc = locations.get(0);
        assertEquals(VOLUME_ID, loc.getVolumeId());
        assertNull(loc.getStaleSince());

        // Videos created
        var videos = videoRepo.findByTitle(titleId);
        assertEquals(1, videos.size());
        assertEquals("ipzz679.mp4", videos.get(0).getFilename());

        // Cast linked (Marin Yakuno)
        var castIds = titleActressRepo.findActressIdsByTitle(titleId);
        assertEquals(1, castIds.size());
        var actress = actressRepo.findById(castIds.get(0));
        assertTrue(actress.isPresent());
        assertEquals("Marin Yakuno", actress.get().getCanonicalName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: existing title elsewhere — adds 2nd location, no cast added
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void existingTitle_adds2ndLocation_doesNotTouchExistingLocation_doesNotAddCast() throws Exception {
        // Pre-exist: IPZZ-679 already located on OTHER_VOLUME_ID with an existing cast
        long existingActressId = actressRepo.save(Actress.builder()
                .canonicalName("Real Actress")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build()).getId();
        var existingTitle = titleRepo.findOrCreateByCode(
                com.organizer3.model.Title.builder()
                        .code("IPZZ-679").baseCode("IPZZ-00679").label("IPZZ").seqNum(679)
                        .actressId(existingActressId).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(existingTitle.getId())
                .volumeId(OTHER_VOLUME_ID)
                .partitionId("minor")
                .path(Path.of("/stars/minor/Real Actress/IPZZ-679"))
                .lastSeenAt(LocalDate.now())
                .build());
        titleActressRepo.linkAll(existingTitle.getId(), List.of(existingActressId));
        videoRepo.save(Video.builder()
                .titleId(existingTitle.getId()).volumeId(OTHER_VOLUME_ID)
                .filename("ipzz679_orig.mp4")
                .path(Path.of("/stars/minor/Real Actress/IPZZ-679/ipzz679_orig.mp4"))
                .lastSeenAt(LocalDate.now()).build());

        // Now register on the new volume
        String folderPath = "/queue/Marin Yakuno (IPZZ-679)";
        fs.dir("/queue/Marin Yakuno (IPZZ-679)");
        fs.videoFile("/queue/Marin Yakuno (IPZZ-679)/ipzz679_copy.mp4");

        Map<String, Object> r = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));

        assertTrue((Boolean) r.get("ok"), "should be ok: " + r.get("error"));
        assertFalse((Boolean) r.get("isNewTitle"), "title should already exist");
        assertFalse((Boolean) r.get("castInferred"), "cast must NOT be inferred for existing title");

        // Keep-both: 2 locations now
        var locations = locationRepo.findByTitle(existingTitle.getId());
        assertEquals(2, locations.size(), "should have 2 locations (keep-both)");
        assertTrue(locations.stream().anyMatch(l -> OTHER_VOLUME_ID.equals(l.getVolumeId())),
                "original location on other volume must be preserved");
        assertTrue(locations.stream().anyMatch(l -> VOLUME_ID.equals(l.getVolumeId())),
                "new location on mounted volume must be added");

        // Original video still present
        var videos = videoRepo.findByTitle(existingTitle.getId());
        assertTrue(videos.stream().anyMatch(v -> "ipzz679_orig.mp4".equals(v.getFilename())),
                "original video must not be touched");
        assertTrue(videos.stream().anyMatch(v -> "ipzz679_copy.mp4".equals(v.getFilename())),
                "new video must be registered");

        // Cast unchanged — still only the original actress
        var castIds = titleActressRepo.findActressIdsByTitle(existingTitle.getId());
        assertEquals(List.of(existingActressId), castIds,
                "cast must remain untouched for existing title");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: videos in a video/ subdir are registered
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void videosInSubdirAreRegistered() {
        String folderPath = "/queue/Marin Yakuno (IPZZ-679)";
        fs.dir("/queue/Marin Yakuno (IPZZ-679)");
        // Subdir video/ and h265/
        fs.dir("/queue/Marin Yakuno (IPZZ-679)/video");
        fs.videoFile("/queue/Marin Yakuno (IPZZ-679)/video/ipzz679.mkv");
        fs.dir("/queue/Marin Yakuno (IPZZ-679)/h265");
        fs.videoFile("/queue/Marin Yakuno (IPZZ-679)/h265/ipzz679_h265.mkv");

        Map<String, Object> r = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));

        assertTrue((Boolean) r.get("ok"), "should be ok: " + r.get("error"));
        List<String> videos = (List<String>) r.get("videosRegistered");
        assertEquals(2, videos.size());
        assertTrue(videos.contains("ipzz679.mkv"));
        assertTrue(videos.contains("ipzz679_h265.mkv"));

        long titleId = (Long) r.get("titleId");
        assertEquals(2, videoRepo.findByTitle(titleId).size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: no-code folder name → refused, no rows created
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noCodeFolderName_refused_noRowsCreated() {
        // A plain actress-wrapper folder with no (CODE) annotation
        String folderPath = "/attention/Marin Yakuno";
        fs.dir("/attention/Marin Yakuno");
        fs.videoFile("/attention/Marin Yakuno/video.mp4");

        Map<String, Object> r = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));

        assertFalse((Boolean) r.get("ok"), "should be refused");
        String error = (String) r.get("error");
        assertNotNull(error);
        assertTrue(error.contains("no parseable JAV code") || error.contains("LABEL-NNN"),
                "error should mention the refusal reason, got: " + error);

        // No title, location, or video rows created
        assertTrue(titleRepo.findByCode("Marin Yakuno").isEmpty(), "no phantom title");
        assertEquals(0, (int) jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_locations").mapTo(Integer.class).one()),
                "no location rows");
        assertEquals(0, (int) jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM videos").mapTo(Integer.class).one()),
                "no video rows");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: idempotency — re-register same folder → no duplicates
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void idempotent_reregistringSameFolder_doesNotDuplicateRows() {
        String folderPath = "/queue/Marin Yakuno (IPZZ-679)";
        fs.dir("/queue/Marin Yakuno (IPZZ-679)");
        fs.videoFile("/queue/Marin Yakuno (IPZZ-679)/ipzz679.mp4");

        // First registration
        Map<String, Object> r1 = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));
        assertTrue((Boolean) r1.get("ok"), "first registration failed: " + r1.get("error"));
        long titleId = (Long) r1.get("titleId");

        // Second registration — must not throw or duplicate
        Map<String, Object> r2 = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));
        assertTrue((Boolean) r2.get("ok"), "second registration failed: " + r2.get("error"));

        // Still exactly 1 location row
        int locationCount = jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM title_locations WHERE title_id = :id AND volume_id = :vol")
                .bind("id", titleId).bind("vol", VOLUME_ID)
                .mapTo(Integer.class).one());
        assertEquals(1, locationCount, "must not duplicate location row");

        // Still exactly 1 video row
        assertEquals(1, videoRepo.findByTitle(titleId).size(), "must not duplicate video row");

        // Cast still linked once
        assertEquals(1, titleActressRepo.findActressIdsByTitle(titleId).size(),
                "cast must not be duplicated");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: dryRun=true makes no DB writes, plan matches subsequent commit
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dryRun_writesNothingAndPlanMatchesCommit() {
        String folderPath = "/queue/Marin Yakuno (IPZZ-679)";
        fs.dir("/queue/Marin Yakuno (IPZZ-679)");
        fs.videoFile("/queue/Marin Yakuno (IPZZ-679)/ipzz679.mp4");

        // Dry-run call
        Map<String, Object> plan = (Map<String, Object>) tool.call(dryRun(VOLUME_ID, folderPath));
        assertTrue((Boolean) plan.get("ok"), "plan should be ok: " + plan.get("error"));
        assertTrue((Boolean) plan.get("dryRun"));
        assertFalse((Boolean) plan.get("committed"));

        // Nothing written to DB
        assertEquals(0, (int) jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles").mapTo(Integer.class).one()),
                "dry-run must not write titles");
        assertEquals(0, (int) jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_locations").mapTo(Integer.class).one()),
                "dry-run must not write locations");
        assertEquals(0, (int) jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM videos").mapTo(Integer.class).one()),
                "dry-run must not write videos");

        // Commit call
        Map<String, Object> result = (Map<String, Object>) tool.call(commit(VOLUME_ID, folderPath));
        assertTrue((Boolean) result.get("ok"), "commit should be ok: " + result.get("error"));
        assertTrue((Boolean) result.get("committed"));

        // Plan and commit agree on key fields
        assertEquals(plan.get("code"),         result.get("code"),         "code mismatch");
        assertEquals(plan.get("isNewTitle"),    result.get("isNewTitle"),   "isNewTitle mismatch");
        assertEquals(plan.get("partitionId"),   result.get("partitionId"),  "partitionId mismatch");
        assertEquals(plan.get("castInferred"),  result.get("castInferred"), "castInferred mismatch");

        // video count in plan == videos actually registered
        List<String> planVideos   = (List<String>) plan.get("videosToRegister");
        List<String> commitVideos = (List<String>) result.get("videosRegistered");
        assertEquals(planVideos.size(), commitVideos.size(), "video count mismatch between plan and commit");
        assertTrue(commitVideos.containsAll(planVideos), "commit videos must include all planned videos");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard: wrong volume id is rejected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void wrongVolumeId_rejected() {
        Map<String, Object> r = (Map<String, Object>) tool.call(commit("wrong_vol", "/queue/Foo (IPZZ-100)"));
        assertFalse((Boolean) r.get("ok"));
        String error = (String) r.get("error");
        assertTrue(error.contains("does not match") || error.contains("mounted"),
                "should mention volume mismatch, got: " + error);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: build args
    // ─────────────────────────────────────────────────────────────────────────

    private ObjectNode commit(String volumeId, String path) {
        ObjectNode a = M.createObjectNode();
        a.put("volumeId", volumeId);
        a.put("path", path);
        a.put("dryRun", false);
        return a;
    }

    private ObjectNode dryRun(String volumeId, String path) {
        ObjectNode a = M.createObjectNode();
        a.put("volumeId", volumeId);
        a.put("path", path);
        a.put("dryRun", true);
        return a;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // In-memory VolumeFileSystem stub
    // ─────────────────────────────────────────────────────────────────────────

    static final class FakeFS implements VolumeFileSystem {

        /** node → isDirectory */
        private final Map<Path, Boolean> nodes = new HashMap<>();

        void dir(String p) {
            Path path = Path.of(p);
            Path cur  = path;
            while (cur != null) { nodes.put(cur, true); cur = cur.getParent(); }
        }

        void videoFile(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }

        @Override
        public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            String parent = path.toString();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.toString().equals(parent)) out.add(p);
            }
            return out;
        }

        @Override public boolean exists(Path path)             { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)        { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public List<Path> walk(Path root)            { throw new UnsupportedOperationException(); }
        @Override public LocalDate getLastModifiedDate(Path p) { return null; }
        @Override public long size(Path p)                     { return 0L; }
        @Override public InputStream openFile(Path p)          { throw new UnsupportedOperationException(); }
        @Override public void move(Path src, Path dst)         { nodes.put(dst, nodes.remove(src)); }
        @Override public void rename(Path p, String n)         { move(p, p.resolveSibling(n)); }
        @Override public void createDirectories(Path path)     { dir(path.toString()); }
        @Override public void writeFile(Path path, byte[] b)   { nodes.put(path, false); }
        @Override public FileTimestamps getTimestamps(Path p) throws IOException {
            return new FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }
}
