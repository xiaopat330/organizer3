package com.organizer3.utilities.backup;

import com.organizer3.backup.UserDataBackupService;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.avstars.repository.jdbi.JdbiAvActressRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvVideoRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVolumeRepository;
import com.organizer3.repository.jdbi.JdbiWatchHistoryRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BackupCatalogService} — especially that date fields are
 * serialized as ISO strings rather than Jackson's default LocalDateTime array,
 * which would crash the frontend JavaScript.
 */
class BackupCatalogServiceTest {

    @TempDir Path tempDir;

    private UserDataBackupService backupService;
    private Path basePath;
    private BackupCatalogService catalog;

    @BeforeEach
    void setUp() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(conn);
        new SchemaInitializer(jdbi).initialize();

        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        backupService = new UserDataBackupService(
                new JdbiActressRepository(jdbi),
                new JdbiTitleRepository(jdbi, locationRepo),
                new JdbiWatchHistoryRepository(jdbi),
                new JdbiAvActressRepository(jdbi),
                new JdbiAvVideoRepository(jdbi));

        basePath = tempDir.resolve("backups").resolve("user-data-backup.json");
        catalog  = new BackupCatalogService(backupService, basePath);
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Test
    void listReturnsEmptyWhenNoSnapshotsExist() {
        assertTrue(catalog.list().isEmpty());
    }

    @Test
    void listReturnsSnapshotsNewestFirst() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 10, 12, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 4, 11, 12, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 4, 12, 12, 0, 0);

        writeSnapshot(t1);
        writeSnapshot(t2);
        writeSnapshot(t3);

        List<BackupCatalogService.Snapshot> result = catalog.list();

        assertEquals(3, result.size());
        // newest first
        assertTrue(result.get(0).name().contains("2026-04-12"), result.get(0).name());
        assertTrue(result.get(1).name().contains("2026-04-11"), result.get(1).name());
        assertTrue(result.get(2).name().contains("2026-04-10"), result.get(2).name());
    }

    @Test
    void listMarksMostRecentAsLatest() throws Exception {
        writeSnapshot(LocalDateTime.of(2026, 4, 10, 12, 0, 0));
        writeSnapshot(LocalDateTime.of(2026, 4, 12, 12, 0, 0));

        List<BackupCatalogService.Snapshot> result = catalog.list();

        assertTrue(result.get(0).latest(),  "newest snapshot should be marked latest");
        assertFalse(result.get(1).latest(), "older snapshot should not be marked latest");
    }

    @Test
    void timestampIsIsoString() throws Exception {
        writeSnapshot(LocalDateTime.of(2026, 4, 24, 14, 30, 0));

        BackupCatalogService.Snapshot snapshot = catalog.list().get(0);

        assertNotNull(snapshot.timestamp());
        assertInstanceOf(String.class, snapshot.timestamp(),
                "timestamp must be a String so new Date() in JavaScript parses it correctly");
        assertTrue(snapshot.timestamp().startsWith("2026-04-24"),
                "Expected ISO date prefix, got: " + snapshot.timestamp());
    }

    @Test
    void sizeIsReported() throws Exception {
        writeSnapshot(LocalDateTime.of(2026, 4, 24, 10, 0, 0));

        BackupCatalogService.Snapshot snapshot = catalog.list().get(0);

        assertTrue(snapshot.sizeBytes() > 0, "sizeBytes should be positive");
    }

    // ── snapshotDetail() ─────────────────────────────────────────────────────

    @Test
    void snapshotDetailCreatedAtIsIsoString() throws Exception {
        writeSnapshot(LocalDateTime.of(2026, 4, 24, 14, 30, 0));
        String name = catalog.list().get(0).name();
        Path path = catalog.resolve(name).orElseThrow();

        UserDataBackupService.SnapshotDetail detail = backupService.snapshotDetail(path);

        assertNotNull(detail.createdAt());
        assertInstanceOf(String.class, detail.createdAt(),
                "createdAt must be a String so new Date() in JavaScript parses it correctly");
        assertTrue(detail.createdAt().startsWith("2026-04-24"),
                "Expected ISO date prefix, got: " + detail.createdAt());
    }

    @Test
    void snapshotDetailCountsMatchBackupContents() throws Exception {
        writeSnapshot(LocalDateTime.of(2026, 4, 24, 10, 0, 0));
        String name = catalog.list().get(0).name();
        Path path = catalog.resolve(name).orElseThrow();

        UserDataBackupService.SnapshotDetail detail = backupService.snapshotDetail(path);

        assertEquals(0, detail.actresses());
        assertEquals(0, detail.titles());
        assertEquals(0, detail.watchHistory());
        assertTrue(detail.sizeBytes() > 0);
    }

    // ── resolve() ─────────────────────────────────────────────────────────────

    @Test
    void resolveRejectsPathTraversal() {
        assertTrue(catalog.resolve("../secret.json").isEmpty());
        assertTrue(catalog.resolve("foo/bar.json").isEmpty());
        assertTrue(catalog.resolve("..").isEmpty());
    }

    @Test
    void resolveReturnsEmptyForNonExistentFile() {
        assertTrue(catalog.resolve("user-data-backup-2099-01-01T00-00-00.json").isEmpty());
    }

    @Test
    void resolveFindsExistingSnapshot() throws Exception {
        writeSnapshot(LocalDateTime.of(2026, 4, 24, 10, 0, 0));
        String name = catalog.list().get(0).name();

        assertTrue(catalog.resolve(name).isPresent());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void writeSnapshot(LocalDateTime ts) throws Exception {
        Path dest = UserDataBackupService.snapshotPathFor(basePath, ts);
        backupService.write(backupService.export(), dest);
    }
}
