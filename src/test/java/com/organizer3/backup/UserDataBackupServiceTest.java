package com.organizer3.backup;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.jdbi.JdbiAvActressRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvVideoRepository;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.Volume;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVolumeRepository;
import com.organizer3.repository.jdbi.JdbiWatchHistoryRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link UserDataBackupService} using a real in-memory SQLite DB.
 */
class UserDataBackupServiceTest {

    @TempDir Path tempDir;

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiWatchHistoryRepository watchHistoryRepo;
    private JdbiAvActressRepository avActressRepo;
    private JdbiAvVideoRepository avVideoRepo;
    private UserDataBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        JdbiVolumeRepository volumeRepo = new JdbiVolumeRepository(jdbi);
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        watchHistoryRepo = new JdbiWatchHistoryRepository(jdbi);
        avActressRepo = new JdbiAvActressRepository(jdbi);
        avVideoRepo = new JdbiAvVideoRepository(jdbi);
        service = new UserDataBackupService(actressRepo, titleRepo, watchHistoryRepo, avActressRepo, avVideoRepo);

        // Seed a volume so title locations can be created
        volumeRepo.save(new Volume("vol-a", "conventional"));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Export ───────────────────────────────────────────────────────────────

    @Test
    void exportExcludesDefaultOnlyActresses() {
        actressRepo.save(Actress.builder()
                .canonicalName("Untouched Actress")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build());

        UserDataBackup backup = service.export();

        assertTrue(backup.actresses().isEmpty(),
                "Actress with all-default user fields should be omitted from backup");
    }

    @Test
    void exportIncludesFavoritedActress() {
        Actress saved = actressRepo.save(Actress.builder()
                .canonicalName("Yua Mikami")
                .tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.now())
                .favorite(true)
                .build());
        actressRepo.save(saved);

        UserDataBackup backup = service.export();

        assertEquals(1, backup.actresses().size());
        ActressBackupEntry entry = backup.actresses().get(0);
        assertEquals("Yua Mikami", entry.canonicalName());
        assertTrue(entry.favorite());
    }

    @Test
    void exportIncludesGradedActress() {
        Actress saved = actressRepo.save(Actress.builder()
                .canonicalName("Airi Suzumura")
                .tier(Actress.Tier.SUPERSTAR)
                .firstSeenAt(LocalDate.now())
                .grade(Actress.Grade.A_PLUS)
                .build());
        actressRepo.save(saved);

        UserDataBackup backup = service.export();

        assertEquals(1, backup.actresses().size());
        assertEquals("A+", backup.actresses().get(0).grade());
    }

    @Test
    void exportIncludesVisitedActress() {
        actressRepo.save(Actress.builder()
                .canonicalName("Nana Ogura")
                .tier(Actress.Tier.POPULAR)
                .firstSeenAt(LocalDate.now())
                .build());
        actressRepo.restoreUserData("Nana Ogura", false, false, null, null, false, 5,
                LocalDateTime.of(2026, 1, 15, 20, 0));

        UserDataBackup backup = service.export();

        assertEquals(1, backup.actresses().size());
        ActressBackupEntry entry = backup.actresses().get(0);
        assertEquals(5, entry.visitCount());
        assertEquals(LocalDateTime.of(2026, 1, 15, 20, 0), entry.lastVisitedAt());
    }

    @Test
    void exportExcludesDefaultOnlyTitles() {
        titleRepo.save(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").build());

        UserDataBackup backup = service.export();

        assertTrue(backup.titles().isEmpty(),
                "Title with all-default user fields should be omitted from backup");
    }

    @Test
    void exportIncludesFavoritedTitle() {
        titleRepo.save(Title.builder()
                .code("ABP-123").baseCode("ABP-00123").label("ABP")
                .favorite(true)
                .build());

        UserDataBackup backup = service.export();

        assertEquals(1, backup.titles().size());
        TitleBackupEntry entry = backup.titles().get(0);
        assertEquals("ABP-123", entry.code());
        assertTrue(entry.favorite());
    }

    @Test
    void exportIncludesTitleWithNotes() {
        titleRepo.save(Title.builder()
                .code("SSIS-456").baseCode("SSIS-00456").label("SSIS")
                .build());
        titleRepo.restoreUserData("SSIS-456", false, false, null, null, false, 0, null, "Great performance.");

        UserDataBackup backup = service.export();

        assertEquals(1, backup.titles().size());
        assertEquals("Great performance.", backup.titles().get(0).notes());
    }

    @Test
    void exportIncludesWatchHistory() {
        watchHistoryRepo.record("ABP-123", LocalDateTime.of(2026, 3, 10, 21, 0));
        watchHistoryRepo.record("ABP-123", LocalDateTime.of(2026, 3, 15, 22, 30));

        UserDataBackup backup = service.export();

        assertEquals(2, backup.watchHistory().size());
        // findAllEntries returns ASC order
        assertEquals(LocalDateTime.of(2026, 3, 10, 21, 0), backup.watchHistory().get(0).watchedAt());
    }

    @Test
    void exportVersionAndTimestamp() {
        UserDataBackup backup = service.export();
        assertEquals(UserDataBackupService.CURRENT_BACKUP_VERSION, backup.version());
        assertNotNull(backup.exportedAt());
    }

    // ── Write + read round-trip ───────────────────────────────────────────────

    @Test
    void writeAndReadRoundTrip() throws Exception {
        actressRepo.save(Actress.builder()
                .canonicalName("Yua Mikami")
                .tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.now())
                .favorite(true)
                .grade(Actress.Grade.SSS)
                .build());
        titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP")
                .bookmark(true).grade(Actress.Grade.S)
                .build());
        titleRepo.restoreUserData("ABP-001", false, true, null, "S", false, 0, null, "Classic.");
        watchHistoryRepo.record("ABP-001", LocalDateTime.of(2026, 1, 1, 12, 0));

        Path backupFile = tempDir.resolve("backup.json");
        UserDataBackup written = service.export();
        service.write(written, backupFile);

        UserDataBackup read = service.read(backupFile);

        assertEquals(written.version(), read.version());
        assertEquals(1, read.actresses().size());
        assertEquals("Yua Mikami", read.actresses().get(0).canonicalName());
        assertTrue(read.actresses().get(0).favorite());
        assertEquals("SSS", read.actresses().get(0).grade());

        assertEquals(1, read.titles().size());
        assertEquals("ABP-001", read.titles().get(0).code());
        assertTrue(read.titles().get(0).bookmark());
        assertEquals("S", read.titles().get(0).grade());
        assertEquals("Classic.", read.titles().get(0).notes());

        assertEquals(1, read.watchHistory().size());
        assertEquals("ABP-001", read.watchHistory().get(0).titleCode());
    }

    @Test
    void readRejectsUnsupportedVersion() throws Exception {
        // Write a valid file then manually bump the version field
        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION + 1,
                LocalDateTime.now(), List.of(), List.of(), List.of(), List.of(), List.of());
        Path backupFile = tempDir.resolve("future.json");
        service.write(backup, backupFile);

        assertThrows(UnsupportedBackupVersionException.class,
                () -> service.read(backupFile));
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    @Test
    void restoreOverlaysActressUserFields() {
        actressRepo.save(Actress.builder()
                .canonicalName("Airi Suzumura")
                .tier(Actress.Tier.SUPERSTAR)
                .firstSeenAt(LocalDate.now())
                .build());

        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION,
                LocalDateTime.now(),
                List.of(new ActressBackupEntry("Airi Suzumura", true, false, null, "A+", false, 7,
                        LocalDateTime.of(2026, 2, 10, 18, 0))),
                List.of(), List.of(), List.of(), List.of());

        RestoreResult result = service.restore(backup);

        assertEquals(1, result.actressesRestored());
        assertEquals(0, result.actressesSkipped());

        Actress after = actressRepo.findByCanonicalName("Airi Suzumura").orElseThrow();
        assertTrue(after.isFavorite());
        assertEquals(Actress.Grade.A_PLUS, after.getGrade());
        assertEquals(7, after.getVisitCount());
        assertEquals(LocalDateTime.of(2026, 2, 10, 18, 0), after.getLastVisitedAt());
    }

    @Test
    void restoreOverlaysTitleUserFields() {
        titleRepo.save(Title.builder()
                .code("SSIS-001").baseCode("SSIS-00001").label("SSIS").build());

        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION,
                LocalDateTime.now(), List.of(),
                List.of(new TitleBackupEntry("SSIS-001", true, false, null, "S", false, 3,
                        LocalDateTime.of(2026, 3, 1, 20, 0), "Excellent solo.")),
                List.of(), List.of(), List.of());

        RestoreResult result = service.restore(backup);

        assertEquals(1, result.titlesRestored());
        assertEquals(0, result.titlesSkipped());

        Title after = titleRepo.findByCode("SSIS-001").orElseThrow();
        assertTrue(after.isFavorite());
        assertEquals(Actress.Grade.S, after.getGrade());
        assertEquals(3, after.getVisitCount());
        assertEquals("Excellent solo.", after.getNotes());
    }

    @Test
    void restoreSkipsActressNotInDatabase() {
        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION,
                LocalDateTime.now(),
                List.of(new ActressBackupEntry("Unknown Actress", true, false, null, null, false, 0, null)),
                List.of(), List.of(), List.of(), List.of());

        RestoreResult result = service.restore(backup);

        assertEquals(0, result.actressesRestored());
        assertEquals(1, result.actressesSkipped());
    }

    @Test
    void restoreSkipsTitleNotInDatabase() {
        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION,
                LocalDateTime.now(), List.of(),
                List.of(new TitleBackupEntry("GHOST-999", true, false, null, null, false, 0, null, null)),
                List.of(), List.of(), List.of());

        RestoreResult result = service.restore(backup);

        assertEquals(0, result.titlesRestored());
        assertEquals(1, result.titlesSkipped());
    }

    @Test
    void restoreInsertsWatchHistoryIdempotently() {
        watchHistoryRepo.record("ABP-001", LocalDateTime.of(2026, 1, 1, 12, 0));

        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION,
                LocalDateTime.now(), List.of(), List.of(),
                List.of(
                        new WatchHistoryEntry("ABP-001", LocalDateTime.of(2026, 1, 1, 12, 0)),  // already exists
                        new WatchHistoryEntry("ABP-001", LocalDateTime.of(2026, 2, 1, 20, 0))   // new
                ), List.of(), List.of());

        RestoreResult result = service.restore(backup);

        // Only the new entry counts as inserted
        assertEquals(1, result.watchHistoryInserted());
        assertEquals(2, watchHistoryRepo.findByTitleCode("ABP-001").size());
    }

    // ── Snapshot support ─────────────────────────────────────────────────────

    @Test
    void snapshotPathForGeneratesTimestampedName() {
        Path base = Path.of("/data/user-data-backup.json");
        LocalDateTime ts = LocalDateTime.of(2026, 4, 13, 14, 30, 0);

        Path result = UserDataBackupService.snapshotPathFor(base, ts);

        assertEquals("user-data-backup-2026-04-13T14-30-00.json", result.getFileName().toString());
        assertEquals(base.getParent(), result.getParent());
    }

    @Test
    void snapshotPathForHandlesNoExtension() {
        Path base = Path.of("/data/backup");
        LocalDateTime ts = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

        Path result = UserDataBackupService.snapshotPathFor(base, ts);

        assertEquals("backup-2026-01-01T00-00-00.json", result.getFileName().toString());
    }

    @Test
    void exportAndWriteSnapshotCreatesTimestampedFile() throws Exception {
        actressRepo.save(Actress.builder()
                .canonicalName("Yua Mikami").tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.now()).favorite(true).build());

        Path base = tempDir.resolve("backup.json");
        Path written = service.exportAndWriteSnapshot(base, 5);

        assertTrue(java.nio.file.Files.exists(written));
        String name = written.getFileName().toString();
        assertTrue(name.startsWith("backup-"), "Expected timestamped name, got: " + name);
        assertTrue(name.endsWith(".json"));
        // Base path is NOT written
        assertFalse(java.nio.file.Files.exists(base));
    }

    @Test
    void findSnapshotsReturnsSortedOldestFirst() throws Exception {
        Path base = tempDir.resolve("backup.json");
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 10, 12, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 4, 11, 12, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 4, 12, 12, 0, 0);

        // Write snapshots out of order
        service.write(service.export(), UserDataBackupService.snapshotPathFor(base, t3));
        service.write(service.export(), UserDataBackupService.snapshotPathFor(base, t1));
        service.write(service.export(), UserDataBackupService.snapshotPathFor(base, t2));

        List<Path> snapshots = service.findSnapshots(base);

        assertEquals(3, snapshots.size());
        assertTrue(snapshots.get(0).getFileName().toString().contains("2026-04-10"));
        assertTrue(snapshots.get(1).getFileName().toString().contains("2026-04-11"));
        assertTrue(snapshots.get(2).getFileName().toString().contains("2026-04-12"));
    }

    @Test
    void exportAndWriteSnapshotPrunesOldestBeyondCount() throws Exception {
        Path base = tempDir.resolve("backup.json");

        // Write 4 snapshots with distinct timestamps
        for (int i = 1; i <= 4; i++) {
            LocalDateTime ts = LocalDateTime.of(2026, 4, i, 12, 0, 0);
            service.write(service.export(), UserDataBackupService.snapshotPathFor(base, ts));
        }

        // exportAndWriteSnapshot with keep=3 → 5 total, prune 2 oldest
        service.exportAndWriteSnapshot(base, 3);

        List<Path> remaining = service.findSnapshots(base);
        assertEquals(3, remaining.size());
        // The 3 newest (April 3, 4, plus the new one) should remain; April 1 and 2 pruned
        assertFalse(remaining.get(0).getFileName().toString().contains("2026-04-01"));
        assertFalse(remaining.get(0).getFileName().toString().contains("2026-04-02"));
    }

    @Test
    void findLatestBackupPrefersNewestSnapshot() throws Exception {
        Path base = tempDir.resolve("backup.json");

        // Write the plain file
        service.write(service.export(), base);

        // Write two snapshots
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 11, 12, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 4, 12, 12, 0, 0);
        service.write(service.export(), UserDataBackupService.snapshotPathFor(base, t1));
        service.write(service.export(), UserDataBackupService.snapshotPathFor(base, t2));

        Path latest = service.findLatestBackup(base).orElseThrow();

        assertTrue(latest.getFileName().toString().contains("2026-04-12"),
                "Expected newest snapshot, got: " + latest.getFileName());
    }

    @Test
    void findLatestBackupFallsBackToBasePath() throws Exception {
        Path base = tempDir.resolve("backup.json");
        service.write(service.export(), base);

        Path latest = service.findLatestBackup(base).orElseThrow();

        assertEquals(base, latest);
    }

    @Test
    void findLatestBackupReturnsEmptyWhenNothingExists() {
        Path base = tempDir.resolve("backup.json");

        assertTrue(service.findLatestBackup(base).isEmpty());
    }

    @Test
    void findSnapshotsIgnoresBaseFileAndOtherFiles() throws Exception {
        Path base = tempDir.resolve("backup.json");

        // Write the base file and a random unrelated file
        service.write(service.export(), base);
        java.nio.file.Files.writeString(tempDir.resolve("other.json"), "{}");

        List<Path> snapshots = service.findSnapshots(base);

        assertTrue(snapshots.isEmpty(), "Base file and unrelated files should not be returned as snapshots");
    }

    @Test
    void fullExportRestoreCycle() throws Exception {
        // Set up state
        actressRepo.save(Actress.builder()
                .canonicalName("Yuma Asami").tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.now()).favorite(true).grade(Actress.Grade.SSS)
                .build());
        actressRepo.restoreUserData("Yuma Asami", true, false, null, "SSS", false, 20,
                LocalDateTime.of(2026, 4, 1, 21, 0));

        titleRepo.save(Title.builder()
                .code("PRED-001").baseCode("PRED-00001").label("PRED")
                .favorite(true).grade(Actress.Grade.SS)
                .build());
        titleRepo.restoreUserData("PRED-001", true, false, null, "SS", false, 4, null, "Top shelf.");
        watchHistoryRepo.record("PRED-001", LocalDateTime.of(2026, 4, 1, 21, 30));

        // Export
        Path backupFile = tempDir.resolve("cycle.json");
        service.write(service.export(), backupFile);

        // Simulate DB drop: clear user fields and watch history (mirrors what a schema recreate does)
        actressRepo.restoreUserData("Yuma Asami", false, false, null, null, false, 0, null);
        titleRepo.restoreUserData("PRED-001", false, false, null, null, false, 0, null, null);
        watchHistoryRepo.deleteByTitleCode("PRED-001");

        // Verify cleared
        assertFalse(actressRepo.findByCanonicalName("Yuma Asami").orElseThrow().isFavorite());
        assertFalse(titleRepo.findByCode("PRED-001").orElseThrow().isFavorite());

        // Restore
        RestoreResult result = service.restore(service.read(backupFile));
        assertEquals(1, result.actressesRestored());
        assertEquals(1, result.titlesRestored());
        assertEquals(1, result.watchHistoryInserted()); // pre-existing watch entry is skipped (idempotent)

        // Verify restored
        Actress restoredActress = actressRepo.findByCanonicalName("Yuma Asami").orElseThrow();
        assertTrue(restoredActress.isFavorite());
        assertEquals(Actress.Grade.SSS, restoredActress.getGrade());
        assertEquals(20, restoredActress.getVisitCount());

        Title restoredTitle = titleRepo.findByCode("PRED-001").orElseThrow();
        assertTrue(restoredTitle.isFavorite());
        assertEquals("Top shelf.", restoredTitle.getNotes());
    }

    // ── AV actress backup ────────────────────────────────────────────────────

    @Test
    void exportExcludesDefaultOnlyAvActresses() {
        avActressRepo.upsert(AvActress.builder()
                .volumeId("av_vol").folderName("anissa_kate").stageName("Anissa Kate").build());

        UserDataBackup backup = service.export();

        assertTrue(backup.avActresses().isEmpty(),
                "AV actress with all-default user fields should be omitted from backup");
    }

    @Test
    void exportIncludesFavoritedAvActress() {
        long id = avActressRepo.upsert(AvActress.builder()
                .volumeId("av_vol").folderName("anissa_kate").stageName("Anissa Kate").build());
        avActressRepo.toggleFavorite(id, true);

        UserDataBackup backup = service.export();

        assertEquals(1, backup.avActresses().size());
        AvActressBackupEntry entry = backup.avActresses().get(0);
        assertEquals("av_vol", entry.volumeId());
        assertEquals("anissa_kate", entry.folderName());
        assertTrue(entry.favorite());
    }

    @Test
    void restoreOverlaysAvActressUserFields() {
        avActressRepo.upsert(AvActress.builder()
                .volumeId("av_vol").folderName("lela_star").stageName("Lela Star").build());

        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION, LocalDateTime.now(),
                List.of(), List.of(), List.of(),
                List.of(new AvActressBackupEntry("av_vol", "lela_star", true, false, false, "A", "Great performer.", 3, null)),
                List.of());

        RestoreResult result = service.restore(backup);

        assertEquals(1, result.avActressesRestored());
        assertEquals(0, result.avActressesSkipped());

        AvActress after = avActressRepo.findByVolumeAndFolder("av_vol", "lela_star").orElseThrow();
        assertTrue(after.isFavorite());
        assertEquals("A", after.getGrade());
        assertEquals("Great performer.", after.getNotes());
    }

    @Test
    void restoreSkipsAvActressNotInDatabase() {
        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION, LocalDateTime.now(),
                List.of(), List.of(), List.of(),
                List.of(new AvActressBackupEntry("av_vol", "ghost_folder", true, false, false, null, null, 0, null)),
                List.of());

        RestoreResult result = service.restore(backup);

        assertEquals(0, result.avActressesRestored());
        assertEquals(1, result.avActressesSkipped());
    }

    // ── AV video backup ──────────────────────────────────────────────────────

    @Test
    void exportExcludesDefaultOnlyAvVideos() {
        long actressId = avActressRepo.upsert(AvActress.builder()
                .volumeId("av_vol").folderName("anissa_kate").stageName("Anissa Kate").build());
        avVideoRepo.upsert(AvVideo.builder()
                .avActressId(actressId).volumeId("av_vol")
                .relativePath("anissa_kate/video.mp4").filename("video.mp4").extension("mp4")
                .build());

        UserDataBackup backup = service.export();

        assertTrue(backup.avVideos().isEmpty(),
                "AV video with all-default user fields should be omitted from backup");
    }

    @Test
    void exportIncludesWatchedAvVideo() {
        long actressId = avActressRepo.upsert(AvActress.builder()
                .volumeId("av_vol").folderName("anissa_kate").stageName("Anissa Kate").build());
        long videoId = avVideoRepo.upsert(AvVideo.builder()
                .avActressId(actressId).volumeId("av_vol")
                .relativePath("anissa_kate/video.mp4").filename("video.mp4").extension("mp4")
                .build());
        avVideoRepo.recordWatch(videoId);

        UserDataBackup backup = service.export();

        assertEquals(1, backup.avVideos().size());
        AvVideoBackupEntry entry = backup.avVideos().get(0);
        assertEquals("av_vol", entry.volumeId());
        assertEquals("anissa_kate", entry.folderName());
        assertEquals("anissa_kate/video.mp4", entry.relativePath());
        assertTrue(entry.watched());
        assertEquals(1, entry.watchCount());
    }

    @Test
    void restoreOverlaysAvVideoUserFields() {
        long actressId = avActressRepo.upsert(AvActress.builder()
                .volumeId("av_vol").folderName("lela_star").stageName("Lela Star").build());
        avVideoRepo.upsert(AvVideo.builder()
                .avActressId(actressId).volumeId("av_vol")
                .relativePath("lela_star/scene.mp4").filename("scene.mp4").extension("mp4")
                .build());

        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION, LocalDateTime.now(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new AvVideoBackupEntry("av_vol", "lela_star", "lela_star/scene.mp4",
                        true, false, true, 2, null)));

        RestoreResult result = service.restore(backup);

        assertEquals(1, result.avVideosRestored());
        assertEquals(0, result.avVideosSkipped());
    }

    @Test
    void restoreSkipsAvVideoWhenActressNotInDatabase() {
        UserDataBackup backup = new UserDataBackup(
                UserDataBackupService.CURRENT_BACKUP_VERSION, LocalDateTime.now(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new AvVideoBackupEntry("av_vol", "ghost_folder", "ghost_folder/scene.mp4",
                        true, false, false, 0, null)));

        RestoreResult result = service.restore(backup);

        assertEquals(0, result.avVideosRestored());
        assertEquals(1, result.avVideosSkipped());
    }

    // ── Backward compatibility ────────────────────────────────────────────────

    @Test
    void restoreHandlesV1BackupWithNullAvFields() throws Exception {
        // Construct a v1-style JSON manually (no avActresses / avVideos fields)
        String v1Json = """
                {
                  "version": 1,
                  "exportedAt": "2026-01-01T12:00:00",
                  "actresses": [],
                  "titles": [],
                  "watchHistory": []
                }
                """;
        Path backupFile = tempDir.resolve("v1-backup.json");
        java.nio.file.Files.writeString(backupFile, v1Json);

        // Reading should not throw
        UserDataBackup backup = service.read(backupFile);

        assertNull(backup.avActresses());
        assertNull(backup.avVideos());

        // Restoring should not throw — null lists treated as empty
        RestoreResult result = service.restore(backup);
        assertEquals(0, result.avActressesRestored());
        assertEquals(0, result.avActressesSkipped());
        assertEquals(0, result.avVideosRestored());
        assertEquals(0, result.avVideosSkipped());
    }
}
