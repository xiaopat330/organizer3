package com.organizer3.web;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.smb.SmbConnectionFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TitleFolderRenamerTest {

    private static final String VOL = "unsorted";

    private Connection connection;
    private Jdbi jdbi;
    private TitleFolderRenamer renamer;

    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;

    // SMB mocks (shared)
    private SmbConnectionFactory smbFactory;
    private SmbConnectionFactory.SmbShareHandle handle;
    private VolumeFileSystem fs;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('" + VOL + "', 'queue')"));

        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);
        videoRepo    = new JdbiVideoRepository(jdbi);

        smbFactory = mock(SmbConnectionFactory.class);
        handle     = mock(SmbConnectionFactory.SmbShareHandle.class);
        fs         = mock(VolumeFileSystem.class);
        when(smbFactory.open(VOL)).thenReturn(handle);
        when(handle.fileSystem()).thenReturn(fs);
        when(fs.exists(any())).thenReturn(false); // default: no collision

        renamer = new TitleFolderRenamer(smbFactory, jdbi, VOL);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Dual path rewrite (critical regression test) ───────────────────

    @Test
    void renames_rewrites_both_title_locations_and_videos_path() throws IOException {
        String oldFolder = "queue/Old Name (TST-001)";
        String oldVideoPath = oldFolder + "/video/a.mp4";
        long titleId = seedTitle("TST-001", oldFolder, oldVideoPath);

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "New Name", null, "TST-001");

        assertTrue(outcome.renamed());
        String newFolder = "queue/New Name (TST-001)";
        assertEquals(newFolder, outcome.newPath());

        // title_locations.path rewritten
        String locPath = jdbi.withHandle(h -> h.createQuery(
                        "SELECT path FROM title_locations WHERE title_id = :id AND stale_since IS NULL")
                .bind("id", titleId).mapTo(String.class).one());
        assertEquals(newFolder, locPath);

        // videos.path rewritten (the load-bearing dual rewrite)
        String vidPath = jdbi.withHandle(h -> h.createQuery(
                        "SELECT path FROM videos WHERE title_id = :id")
                .bind("id", titleId).mapTo(String.class).one());
        assertEquals(newFolder + "/video/a.mp4", vidPath);
    }

    @Test
    void renames_with_descriptor_produces_correct_name() throws IOException {
        String oldFolder = "queue/Old Name (TST-002)";
        long titleId = seedTitle("TST-002", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "Yua Aida", "Demosaiced", "TST-002");

        assertTrue(outcome.renamed());
        assertEquals("queue/Yua Aida - Demosaiced (TST-002)", outcome.newPath());
    }

    @Test
    void renames_without_descriptor_produces_correct_name() throws IOException {
        String oldFolder = "queue/SomethingElse (TST-003)";
        long titleId = seedTitle("TST-003", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "Nao Wakana", "", "TST-003");

        assertTrue(outcome.renamed());
        assertEquals("queue/Nao Wakana (TST-003)", outcome.newPath());
    }

    // ── No-op cases ─────────────────────────────────────────────────────

    @Test
    void noOp_when_no_staging_row() {
        // No location seeded → step 1 returns empty
        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(9999L, "Actress", null, "TST-004");
        assertFalse(outcome.renamed());
        assertNull(outcome.newPath());
        verifyNoInteractions(smbFactory);
    }

    @Test
    void noOp_when_null_primary_name() {
        String folder = "queue/Some Folder (TST-005)";
        long titleId = seedTitle("TST-005", folder, folder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, null, null, "TST-005");
        assertFalse(outcome.renamed());
        assertEquals(folder, outcome.newPath());
        verifyNoInteractions(smbFactory);
    }

    @Test
    void noOp_when_blank_primary_name() {
        String folder = "queue/Some Folder (TST-006)";
        long titleId = seedTitle("TST-006", folder, folder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "   ", null, "TST-006");
        assertFalse(outcome.renamed());
        assertEquals(folder, outcome.newPath());
        verifyNoInteractions(smbFactory);
    }

    @Test
    void noOp_when_target_equals_current_basename() throws IOException {
        // Folder name already matches the pattern — no rename needed
        String folder = "queue/Nao Wakana (TST-007)";
        long titleId = seedTitle("TST-007", folder, folder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "Nao Wakana", null, "TST-007");
        assertFalse(outcome.renamed());
        assertEquals(folder, outcome.newPath());
        verifyNoInteractions(smbFactory);
    }

    // ── Collision ────────────────────────────────────────────────────────

    @Test
    void collision_throws_IllegalStateException() throws IOException {
        String folder = "queue/Old Folder (TST-008)";
        long titleId = seedTitle("TST-008", folder, folder + "/video/a.mp4");

        // Simulate: new path already exists on disk
        String newPath = "queue/New Actress (TST-008)";
        when(fs.exists(Path.of(newPath))).thenReturn(true);
        // new path != current path (not equalsIgnoreCase)

        assertThrows(IllegalStateException.class,
                () -> renamer.renameIfNeeded(titleId, "New Actress", null, "TST-008"),
                "Should throw on collision");
    }

    // ── Stale-location scope guard ────────────────────────────────────

    @Test
    void staleLocation_treated_as_no_staging_row() {
        // Seed a title with a stale location only — renamer should treat it as absent
        Title t = titleRepo.save(Title.builder().code("TST-009").baseCode("TST-009").label("TST").build());
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, stale_since) " +
                "VALUES (" + t.getId() + ", '" + VOL + "', 'queue', 'queue/Stale (TST-009)', date('now'), date('now'))"));

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(t.getId(), "New Name", null, "TST-009");
        assertFalse(outcome.renamed());
        assertNull(outcome.newPath());
        verifyNoInteractions(smbFactory);
    }

    // ── Static helper parity tests ────────────────────────────────────

    @Test
    void sanitizeFolderName_matches_UnsortedEditorService_behavior() {
        // These must stay byte-identical so promote and no-draft produce same names.
        assertEquals("Name (CODE-1)",  TitleFolderRenamer.sanitizeFolderName("Name (CODE-1)"));
        assertEquals("A B (X-1)",      TitleFolderRenamer.sanitizeFolderName("A/B (X-1)"));
        assertEquals("Foo Bar (Y-2)",  TitleFolderRenamer.sanitizeFolderName("Foo:Bar (Y-2)"));
        assertEquals("Who (Z-3)",      TitleFolderRenamer.sanitizeFolderName("Who?  (Z-3)"));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private long seedTitle(String code, String folderPath, String videoPath) {
        Title t = titleRepo.save(Title.builder().code(code).baseCode(code).label(code.split("-")[0]).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(t.getId()).volumeId(VOL).partitionId("queue")
                .path(Path.of(folderPath))
                .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now()).build());
        videoRepo.save(Video.builder()
                .titleId(t.getId()).volumeId(VOL)
                .filename("a.mp4").path(Path.of(videoPath))
                .lastSeenAt(LocalDate.now()).build());
        return t.getId();
    }
}
