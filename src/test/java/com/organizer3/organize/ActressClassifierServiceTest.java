package com.organizer3.organize;

import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActressClassifierServiceTest {

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private ActressClassifierService svc;
    private TitleSorterServiceTest.RebasingFS fs;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);

        fs = new TitleSorterServiceTest.RebasingFS(tempDir);
        svc = new ActressClassifierService(actressRepo, titleRepo, locationRepo, LibraryConfig.DEFAULTS);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    void promotesActressLibraryToPopular() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        // 20 titles → tier = popular (>= popular threshold)
        for (int i = 1; i <= 20; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            // currently filed under /stars/library/
            saveLoc(t.getId(), "/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "library");
            createFolder("/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i));
        }

        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.PROMOTED, r.outcome());
        assertEquals("library", r.fromTier());
        assertEquals("popular", r.toTier());
        assertTrue(fs.exists(Path.of("/stars/popular/Ai Haneda")));
        assertFalse(fs.exists(Path.of("/stars/library/Ai Haneda")));
        // All title_locations updated
        List<TitleLocation> locs = locationRepo.findByVolume("a");
        for (TitleLocation l : locs) {
            assertEquals("popular", l.getPartitionId(), "partition_id updated");
            assertTrue(l.getPath().startsWith(Path.of("/stars/popular/Ai Haneda")),
                    "path rewritten: " + l.getPath());
        }
    }

    @Test
    void dryRun_leavesFilesAndDbUntouched() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "library");
            createFolder("/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i));
        }
        // 5 titles → tier = minor

        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, true);

        assertEquals(ActressClassifierService.Outcome.WOULD_PROMOTE, r.outcome());
        assertTrue(fs.exists(Path.of("/stars/library/Ai Haneda")), "dry-run leaves folder");
        assertEquals("library", locationRepo.findByVolume("a").get(0).getPartitionId());
    }

    // ── skip cases ──────────────────────────────────────────────────────────

    @Test
    void alreadyAtTargetTier_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/minor/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "minor");
            createFolder("/stars/minor/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i));
        }
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("already at target"));
    }

    @Test
    void neverDemote() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            // Currently at popular (rare, but represents a manually-placed folder) — 5 titles wants 'minor'
            saveLoc(t.getId(), "/stars/popular/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "popular");
            createFolder("/stars/popular/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i));
        }
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("never demote"));
    }

    @Test
    void inFavorites_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 20; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/favorites/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "favorites");
        }
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("user-curated"));
    }

    @Test
    void belowStarThreshold_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        saveLoc(t.getId(), "/queue/Ai Haneda (ABP-001)", "queue");
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("below star threshold"));
    }

    @Test
    void splitAcrossTiers_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        // 3 titles in library, 2 in minor — 5 titles total wants 'minor'
        for (int i = 1; i <= 3; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "library");
        }
        for (int i = 4; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/minor/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "minor");
        }
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("split across"));
    }

    @Test
    void targetFolderAlreadyExists_failed() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 20; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i), "library");
            createFolder("/stars/library/Ai Haneda/Ai Haneda (ABP-%03d)".formatted(i));
        }
        createFolder("/stars/popular/Ai Haneda");   // pre-existing collision

        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.FAILED, r.outcome());
        assertTrue(r.reason().contains("target tier folder already exists"));
    }

    @Test
    void missingActress_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.classify(fs, volumeA(), jdbi, 9999L, true));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @Test
    void rebase_isPureRename() {
        Path out = ActressClassifierService.rebase(
                Path.of("/stars/library/Ai Haneda/Ai Haneda (ABP-001)"),
                "library", "popular", "Ai Haneda");
        assertEquals("/stars/popular/Ai Haneda/Ai Haneda (ABP-001)", out.toString());
    }

    @Test
    void isPathUnderActress_matcher() {
        assertTrue(ActressClassifierService.isPathUnderActress(
                Path.of("/stars/library/Ai Haneda/Ai Haneda (ABP-001)"), "Ai Haneda"));
        assertFalse(ActressClassifierService.isPathUnderActress(
                Path.of("/stars/library/Someone Else/Something"), "Ai Haneda"));
        assertFalse(ActressClassifierService.isPathUnderActress(
                Path.of("/queue/Ai Haneda (X-1)"), "Ai Haneda"));
    }

    private static VolumeConfig volumeA() {
        return new VolumeConfig("a", "//h/a", "conventional", "h", null, List.of("A"));
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

    private void saveLoc(long titleId, String path, String partition) {
        locationRepo.save(TitleLocation.builder()
                .titleId(titleId)
                .volumeId("a")
                .partitionId(partition)
                .path(Path.of(path))
                .lastSeenAt(LocalDate.of(2024, 1, 2))
                .addedDate(LocalDate.of(2024, 1, 2))
                .build());
    }

    private void createFolder(String volumePath) throws Exception {
        Path real = tempDir.resolve(volumePath.substring(1));
        Files.createDirectories(real);
    }
}
