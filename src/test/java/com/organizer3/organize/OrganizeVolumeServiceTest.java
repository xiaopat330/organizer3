package com.organizer3.organize;

import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.MediaConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrganizeVolumeServiceTest {

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private JdbiTitleLocationRepository locationRepo;
    private OrganizeVolumeService svc;
    private TitleSorterServiceTest.RebasingFS fs;
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

        fs = new TitleSorterServiceTest.RebasingFS(tempDir);
        Clock fixed = Clock.fixed(Instant.parse("2026-04-17T00:00:00Z"), ZoneOffset.UTC);
        attentionRouter = new AttentionRouter(fs, "a", fixed);

        TitleNormalizerService normalizer = new TitleNormalizerService(MediaConfig.DEFAULTS);
        TitleRestructurerService restructurer = new TitleRestructurerService(MediaConfig.DEFAULTS);
        TitleTimestampService ts = new TitleTimestampService();
        TitleSorterService sorter = new TitleSorterService(
                titleRepo, actressRepo, titleActressRepo, locationRepo,
                LibraryConfig.DEFAULTS, ts);
        ActressClassifierService classifier = new ActressClassifierService(
                actressRepo, titleRepo, locationRepo, LibraryConfig.DEFAULTS);

        svc = new OrganizeVolumeService(titleRepo, locationRepo, normalizer, restructurer, sorter, classifier);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void fullRun_processesAllQueueTitlesAndClassifiesAffectedActress() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        // 5 titles in queue → after sort, actress has 5 titles → tier minor
        for (int i = 1; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            String folderPath = "/queue/Ai Haneda (ABP-%03d)".formatted(i);
            createFolder(folderPath);
            // put a video file in there so normalize has something to do
            createFile(folderPath + "/dirty-name-%03d.mp4".formatted(i));
            saveLoc(t.getId(), folderPath, "queue");
        }

        OrganizeVolumeService.Result r = svc.organize(fs, volumeA(), attentionRouter, jdbi, null, 0, 0, false);

        assertEquals(5, r.summary().titlesProcessed());
        assertEquals(5, r.summary().sortedToStars(), "all 5 sort to /stars");
        // Classify ran once for the affected actress; no promotion needed because she
        // is already filed in the right tier after sort.
        assertTrue(r.actresses().size() >= 1);

        // Verify one title end-to-end: normalize renamed dirty-name-001.mp4 → ABP-001.mp4,
        // restructure moved it into video/, sort moved the folder to /stars/minor/Ai Haneda/.
        assertTrue(fs.exists(Path.of("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)/video/ABP-001.mp4")),
                "video renamed + moved to video/ + folder sorted end-to-end");
    }

    @Test
    void dryRun_walksWithoutMutating() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 5; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            String folderPath = "/queue/Ai Haneda (ABP-%03d)".formatted(i);
            createFolder(folderPath);
            createFile(folderPath + "/dirty-%03d.mp4".formatted(i));
            saveLoc(t.getId(), folderPath, "queue");
        }

        OrganizeVolumeService.Result r = svc.organize(fs, volumeA(), attentionRouter, jdbi, null, 0, 0, true);

        assertTrue(r.dryRun());
        assertTrue(fs.exists(Path.of("/queue/Ai Haneda (ABP-001)")), "dry-run must leave queue intact");
        assertFalse(fs.exists(Path.of("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)")));
    }

    @Test
    void pagination_honored() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        for (int i = 1; i <= 10; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            String folderPath = "/queue/Ai Haneda (ABP-%03d)".formatted(i);
            createFolder(folderPath);
            createFile(folderPath + "/v.mp4");
            saveLoc(t.getId(), folderPath, "queue");
        }

        // limit=3 offset=0 → titles 1-3
        OrganizeVolumeService.Result r = svc.organize(
                fs, volumeA(), attentionRouter, jdbi, null, 3, 0, true);
        assertEquals(3, r.titles().size());
        assertEquals(10, r.queueTotal());

        // limit=3 offset=3 → titles 4-6
        OrganizeVolumeService.Result r2 = svc.organize(
                fs, volumeA(), attentionRouter, jdbi, null, 3, 3, true);
        assertEquals(3, r2.titles().size());
        assertTrue(r2.titles().get(0).titleCode().endsWith("-004"));
    }

    @Test
    void phaseSubset_onlyNormalize() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        Title t = titleRepo.save(mkTitle("ABP-001", aid));
        // need 5 titles for actress to reach minor tier, but we're only running normalize
        for (int i = 2; i <= 5; i++) titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
        String folderPath = "/queue/Ai Haneda (ABP-001)";
        createFolder(folderPath);
        createFile(folderPath + "/dirty-video.mp4");
        saveLoc(t.getId(), folderPath, "queue");

        OrganizeVolumeService.Result r = svc.organize(fs, volumeA(), attentionRouter, jdbi,
                Set.of(OrganizeVolumeService.Phase.NORMALIZE), 0, 0, false);

        // Normalize ran and renamed the video
        assertTrue(fs.exists(Path.of(folderPath + "/ABP-001.mp4")));
        // Sort didn't run — still in queue
        assertTrue(fs.exists(Path.of(folderPath)));
        assertFalse(fs.exists(Path.of("/stars/minor/Ai Haneda/Ai Haneda (ABP-001)")));
    }

    @Test
    void errorInOneTitleDoesNotAbortOthers() throws Exception {
        long aid = actressRepo.save(mkActress("Ai Haneda")).getId();
        // Save 5 titles; capture their ids.
        long[] ids = new long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = titleRepo.save(mkTitle("ABP-%03d".formatted(i + 1), aid)).getId();
        }
        // Title 0: points at a folder that doesn't exist on disk → sort will SKIP or error
        saveLoc(ids[0], "/queue/MISSING-001", "queue");
        // Titles 1-4: normal folders
        for (int i = 1; i < 5; i++) {
            String folderPath = "/queue/Ai Haneda (ABP-%03d)".formatted(i + 1);
            createFolder(folderPath);
            createFile(folderPath + "/v.mp4");
            saveLoc(ids[i], folderPath, "queue");
        }

        OrganizeVolumeService.Result result = svc.organize(fs, volumeA(), attentionRouter, jdbi, null, 0, 0, true);

        // All 5 processed regardless of the missing-folder case
        assertEquals(5, result.titles().size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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

    private void createFile(String volumePath) throws Exception {
        Path real = tempDir.resolve(volumePath.substring(1));
        Files.createDirectories(real.getParent());
        Files.createFile(real);
    }
}
