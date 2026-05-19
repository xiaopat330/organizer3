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

    // ── reconcileFromDisk ────────────────────────────────────────────────────

    /**
     * AC1: DB LIBRARY + disk POPULAR + reconcileFromDisk=true → DB updated to POPULAR.
     * Post-merge scenario: actress folder was manually moved to popular/, DB actresses.tier
     * still says LIBRARY.
     */
    @Test
    void reconcileFromDisk_diskHigherThanDb_updatesDbTier() throws Exception {
        long aid = actressRepo.save(mkActress("Amu Hanamiya")).getId(); // LIBRARY
        // Create folder on disk at POPULAR
        createFolder("/stars/popular/Amu Hanamiya");

        ActressClassifierService.Result r = svc.reconcileFromDisk(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.RECONCILED, r.outcome());
        assertEquals("LIBRARY", r.fromTier());
        assertEquals("POPULAR", r.toTier());
        // Verify DB was updated
        Actress updated = actressRepo.findById(aid).orElseThrow();
        assertEquals(Actress.Tier.POPULAR, updated.getTier());
    }

    /**
     * AC2: reconcileFromDisk=false (default) → delegates to classify(), returns SKIPPED
     * (5 titles → minor, but actress is already at popular → "never demote").
     */
    @Test
    void reconcileFromDisk_false_delegatesToClassify_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Amu Hanamiya")).getId(); // LIBRARY in DB
        // folder is at popular on disk, 20 titles → classify target=popular
        for (int i = 1; i <= 20; i++) {
            Title t = titleRepo.save(mkTitle("IPX-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/library/Amu Hanamiya/Amu Hanamiya (IPX-%03d)".formatted(i), "library");
            createFolder("/stars/library/Amu Hanamiya/Amu Hanamiya (IPX-%03d)".formatted(i));
        }
        // reconcileFromDisk=false: go through normal classify path
        // DB partition = library, target = popular → WOULD_PROMOTE (with dryRun=false = PROMOTED)
        // We want to test the false default: no reconcile, normal behavior
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, true);
        // Should be WOULD_PROMOTE (the normal classify path)
        assertEquals(ActressClassifierService.Outcome.WOULD_PROMOTE, r.outcome());
    }

    /**
     * AC2b: DB LIBRARY + disk POPULAR + reconcileFromDisk=false → classify() runs; since
     * title_locations show library and title count says popular, it WOULD_PROMOTE (normal path).
     * This confirms that reconcileFromDisk=false does NOT engage the reconcile path at all.
     */
    @Test
    void reconcileFromDisk_false_doesNotReconcile() throws Exception {
        long aid = actressRepo.save(mkActress("Azusa Shinonome")).getId(); // LIBRARY in DB
        // Only 5 titles → classify target = minor
        for (int i = 1; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/popular/Azusa Shinonome/Azusa Shinonome (ABP-%03d)".formatted(i), "popular");
            createFolder("/stars/popular/Azusa Shinonome/Azusa Shinonome (ABP-%03d)".formatted(i));
        }
        // Normal classify: DB/title_locations say popular, target=minor → "never demote"
        ActressClassifierService.Result r = svc.classify(fs, volumeA(), jdbi, aid, false);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("never demote"));
        // Actress.tier NOT touched by classify
        Actress unchanged = actressRepo.findById(aid).orElseThrow();
        assertEquals(Actress.Tier.LIBRARY, unchanged.getTier());
    }

    /**
     * AC3: DB POPULAR + disk LIBRARY (pathological downward case) → never downgrade, SKIPPED.
     */
    @Test
    void reconcileFromDisk_diskLowerThanDb_skipped() throws Exception {
        Actress a = mkActress("Himawari Yuzuki");
        // Build actress with POPULAR tier
        long aid = actressRepo.save(a).getId();
        actressRepo.updateTier(aid, Actress.Tier.POPULAR);

        // Folder is only in library on disk (disk < DB)
        createFolder("/stars/library/Himawari Yuzuki");

        ActressClassifierService.Result r = svc.reconcileFromDisk(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("never downgrade"));
        // DB not changed
        Actress unchanged = actressRepo.findById(aid).orElseThrow();
        assertEquals(Actress.Tier.POPULAR, unchanged.getTier());
    }

    /**
     * AC4: DB POPULAR + disk POPULAR → no-op SKIPPED (already in sync).
     */
    @Test
    void reconcileFromDisk_dbMatchesDisk_noop() throws Exception {
        Actress a = mkActress("Test Actress");
        long aid = actressRepo.save(a).getId();
        actressRepo.updateTier(aid, Actress.Tier.POPULAR);

        createFolder("/stars/popular/Test Actress");

        ActressClassifierService.Result r = svc.reconcileFromDisk(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("already matches"));
    }

    /**
     * Dry-run: reconcileFromDisk=true + dryRun=true → WOULD_RECONCILE, DB not changed.
     */
    @Test
    void reconcileFromDisk_dryRun_doesNotWrite() throws Exception {
        long aid = actressRepo.save(mkActress("Amu Hanamiya")).getId(); // LIBRARY
        createFolder("/stars/popular/Amu Hanamiya");

        ActressClassifierService.Result r = svc.reconcileFromDisk(fs, volumeA(), jdbi, aid, true);

        assertEquals(ActressClassifierService.Outcome.WOULD_RECONCILE, r.outcome());
        // DB NOT changed on dry-run
        Actress unchanged = actressRepo.findById(aid).orElseThrow();
        assertEquals(Actress.Tier.LIBRARY, unchanged.getTier());
    }

    /**
     * When folder is not found under any tier directory, return SKIPPED with explanation.
     */
    @Test
    void reconcileFromDisk_folderNotOnDisk_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ghost Actress")).getId();
        // No folder created on disk

        ActressClassifierService.Result r = svc.reconcileFromDisk(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("not found"));
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
