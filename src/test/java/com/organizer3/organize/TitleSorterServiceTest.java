package com.organizer3.organize;

import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.LocalFileSystem;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests TitleSorterService against in-memory SQLite + a RebasingFS that maps volume
 * paths ({@code /queue/...}) to a real tempdir subtree — so real filesystem moves
 * happen, but stay inside the test's temp dir.
 */
class TitleSorterServiceTest {

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private JdbiTitleLocationRepository locationRepo;
    private TitleSorterService svc;
    private RebasingFS fs;
    private AttentionRouter attentionRouter;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);

        fs = new RebasingFS(tempDir);
        Clock fixed = Clock.fixed(Instant.parse("2026-04-17T00:00:00Z"), ZoneOffset.UTC);
        attentionRouter = new AttentionRouter(fs, "a", fixed);
        svc = new TitleSorterService(titleRepo, actressRepo, titleActressRepo, locationRepo,
                LibraryConfig.DEFAULTS, new TitleTimestampService());
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    void successfulSort_movesToStarsTierActress_updatesDb() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        // seed 5 titles total so tier is "minor"
        for (int i = 2; i <= 5; i++) titleRepo.save(mkTitle("ABP-00" + i, aid));

        createTitleFolder("/queue/Ai Haneda (ABP-001)");
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.SORTED, r.outcome());
        assertEquals("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)", r.to());
        assertTrue(fs.exists(Path.of("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)")));
        assertFalse(fs.exists(Path.of("/queue/Ai Haneda (ABP-001)")));

        // DB updated
        TitleLocation updated = locationRepo.findByTitle(t.getId()).get(0);
        assertEquals("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)", updated.getPath().toString());
        assertEquals("minor", updated.getPartitionId());
    }

    @Test
    void dryRun_leavesFilesystemAndDbUntouched() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        for (int i = 2; i <= 5; i++) titleRepo.save(mkTitle("ABP-00" + i, aid));
        createTitleFolder("/queue/Ai Haneda (ABP-001)");
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "ABP-001", true);

        assertEquals(TitleSorterService.Outcome.WOULD_SORT, r.outcome());
        assertTrue(fs.exists(Path.of("/queue/Ai Haneda (ABP-001)")));
        assertFalse(fs.exists(Path.of("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)")));
        assertEquals("queue", locationRepo.findByTitle(t.getId()).get(0).getPartitionId());
    }

    // ── attention routing ──────────────────────────────────────────────────

    @Test
    void actressLetterMismatch_routesToAttention() throws Exception {
        long aid = actressRepo.save(mkActress("Nami Aino")).getId();   // N on volume a
        Title t = titleRepo.save(mkTitle("SDNM-176", aid));
        for (int i = 2; i <= 5; i++) titleRepo.save(mkTitle("SDNM-17" + i, aid));
        createTitleFolder("/queue/Nami Aino (SDNM-176)");
        saveLocation(t.getId(), "/queue/Nami Aino (SDNM-176)", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "SDNM-176", false);

        assertEquals(TitleSorterService.Outcome.ROUTED_TO_ATTENTION, r.outcome());
        assertEquals("actress-letter-mismatch", r.reason());
        assertTrue(fs.exists(Path.of("/attention/Nami Aino (SDNM-176)")));
        assertTrue(fs.exists(Path.of("/attention/Nami Aino (SDNM-176)/REASON.txt")));
        assertEquals("attention", locationRepo.findByTitle(t.getId()).get(0).getPartitionId());
    }

    @Test
    void actressless_routesToAttention() throws Exception {
        Title t = titleRepo.save(mkTitle("SOMETHING-001", null));
        createTitleFolder("/queue/SOMETHING-001");
        saveLocation(t.getId(), "/queue/SOMETHING-001", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "SOMETHING-001", false);

        assertEquals(TitleSorterService.Outcome.ROUTED_TO_ATTENTION, r.outcome());
        assertEquals("actressless-title", r.reason());
    }

    @Test
    void collisionAtTarget_routesToAttention() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        for (int i = 2; i <= 5; i++) titleRepo.save(mkTitle("ABP-00" + i, aid));
        createTitleFolder("/queue/Ai Haneda (ABP-001)");
        createTitleFolder("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)");   // pre-existing
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.ROUTED_TO_ATTENTION, r.outcome());
        assertEquals("collision", r.reason());
    }

    // ── skip cases ──────────────────────────────────────────────────────────

    @Test
    void multiActress_skipped() throws Exception {
        long a1 = actressRepo.save(mkActress("Ai Haneda")).getId();
        long a2 = actressRepo.save(mkActress("Another One")).getId();
        Title t = titleRepo.save(mkTitle("HAR-003", a1));
        titleActressRepo.linkAll(t.getId(), List.of(a1, a2));
        createTitleFolder("/queue/HAR-003");
        saveLocation(t.getId(), "/queue/HAR-003", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "HAR-003", false);

        assertEquals(TitleSorterService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("multi-actress"));
        assertTrue(fs.exists(Path.of("/queue/HAR-003")), "nothing moved");
    }

    @Test
    void belowStarThreshold_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        // only 1 title total, below star=3
        createTitleFolder("/queue/Ai Haneda (ABP-001)");
        saveLocation(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("below star threshold"));
    }

    @Test
    void alreadyCanonical_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        for (int i = 2; i <= 5; i++) titleRepo.save(mkTitle("ABP-00" + i, aid));
        createTitleFolder("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)");
        saveLocation(t.getId(), "/stars/minor/Ai Haneda/Ai Haneda (ABP-001)", "minor");

        var r = svc.sort(fs, volumeA(), attentionRouter, jdbi, "ABP-001", false);

        assertEquals(TitleSorterService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("already at canonical path"));
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void missingTitle_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.sort(fs, volumeA(), attentionRouter, jdbi, "DOES-NOT-EXIST", true));
    }

    @Test
    void titleNotOnThisVolume_throws() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        titleRepo.save(mkTitle("ABP-001", aid));   // no location row

        assertThrows(IllegalArgumentException.class,
                () -> svc.sort(fs, volumeA(), attentionRouter, jdbi, "ABP-001", true));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static VolumeConfig volumeA() {
        return new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null, List.of("A"));
    }

    private static Actress mkActress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title mkTitle(String code, Long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private void createTitleFolder(String volumePath) throws Exception {
        Path real = tempDir.resolve(volumePath.substring(1));   // strip leading /
        Files.createDirectories(real);
    }

    private TitleLocation saveLocation(long titleId, String volumePath, String partition) {
        return locationRepo.save(TitleLocation.builder()
                .titleId(titleId)
                .volumeId("a")
                .partitionId(partition)
                .path(Path.of(volumePath))
                .lastSeenAt(LocalDate.of(2024, 1, 2))
                .addedDate(LocalDate.of(2024, 1, 2))
                .build());
    }

    // ── RebasingFS: LocalFileSystem that maps /foo/bar → tempDir/foo/bar ────

    static final class RebasingFS implements VolumeFileSystem {
        private final Path root;
        private final LocalFileSystem delegate = new LocalFileSystem();

        RebasingFS(Path root) { this.root = root; }

        private Path rebase(Path p) {
            String s = p.toString();
            if (s.startsWith("/")) s = s.substring(1);
            return root.resolve(s);
        }

        @Override public List<Path> listDirectory(Path p) throws java.io.IOException {
            // Return paths in the volume-path namespace, not the tempdir one
            List<Path> out = new java.util.ArrayList<>();
            for (Path real : delegate.listDirectory(rebase(p))) {
                out.add(p.resolve(real.getFileName().toString()));
            }
            return out;
        }
        @Override public List<Path> walk(Path p) throws java.io.IOException { return delegate.walk(rebase(p)); }
        @Override public boolean exists(Path p) { return delegate.exists(rebase(p)); }
        @Override public boolean isDirectory(Path p) { return delegate.isDirectory(rebase(p)); }
        @Override public LocalDate getLastModifiedDate(Path p) throws java.io.IOException { return delegate.getLastModifiedDate(rebase(p)); }
        @Override public InputStream openFile(Path p) throws java.io.IOException { return delegate.openFile(rebase(p)); }
        @Override public void move(Path s, Path d) throws java.io.IOException { delegate.move(rebase(s), rebase(d)); }
        @Override public void rename(Path p, String n) throws java.io.IOException { delegate.rename(rebase(p), n); }
        @Override public void createDirectories(Path p) throws java.io.IOException { delegate.createDirectories(rebase(p)); }
        @Override public void writeFile(Path p, byte[] b) throws java.io.IOException { delegate.writeFile(rebase(p), b); }
        @Override public FileTimestamps getTimestamps(Path p) throws java.io.IOException { return delegate.getTimestamps(rebase(p)); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) throws java.io.IOException { delegate.setTimestamps(rebase(p), c, m); }
    }
}
