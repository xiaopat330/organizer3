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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        // Default withRetry stub: run the op once against the shared handle (no retry).
        // Individual tests override this to simulate the evict+retry path.
        when(smbFactory.withRetry(eq(VOL), any())).thenAnswer(inv ->
                ((SmbConnectionFactory.SmbOperation<?>) inv.getArgument(1)).execute(handle));

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

    // ── Root-anchored (queue_flat) paths — leading-slash preservation fix ──

    @Test
    void renameIfNeeded_rootAnchoredPath_preservesLeadingSlash() throws IOException {
        // queue_flat volumes (e.g. classic_fresh) store title folders directly at the share
        // root: "/(CODE)". parentPath("/(CODE)") returns "" (only the leading slash exists),
        // so the renamed path must special-case this and keep the leading slash rather than
        // producing a bare "Some Actress (ABP-196)" — otherwise a later sync sees a *different*
        // location string, duplicating the row and orphaning curated_at.
        String oldFolder = "/(ABP-196)";
        long titleId = seedTitle("ABP-196", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "Some Actress", null, "ABP-196");

        assertTrue(outcome.renamed());
        assertTrue(outcome.newPath().startsWith("/"),
                "root-anchored rename must keep the leading slash");
        assertEquals("/Some Actress (ABP-196)", outcome.newPath());

        // title_locations.path rewritten with the leading slash preserved (the actual bug:
        // a stale/duplicated row would appear here if the slash were dropped).
        String locPath = jdbi.withHandle(h -> h.createQuery(
                        "SELECT path FROM title_locations WHERE title_id = :id AND stale_since IS NULL")
                .bind("id", titleId).mapTo(String.class).one());
        assertEquals("/Some Actress (ABP-196)", locPath);
    }

    @Test
    void renameIfNeeded_nestedPath_stillPreservesLeadingSlash_unchangedBehavior() throws IOException {
        // Nested (non-root) paths already worked correctly before the fix — parent is
        // non-empty, so this branch is untouched. Regression guard for the surrounding logic.
        String oldFolder = "/fresh/(ABP-197)";
        long titleId = seedTitle("ABP-197", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "Other Actress", null, "ABP-197");

        assertTrue(outcome.renamed());
        assertEquals("/fresh/Other Actress (ABP-197)", outcome.newPath());
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
                renamer.renameIfNeeded(titleId, (String) null, null, "TST-005");
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

        // Simulate a genuine collision: BOTH the current folder AND the new path exist
        // on disk. (curExists=true distinguishes this from the lost-ack idempotency case,
        // where only the new path exists.)
        String newPath = "queue/New Actress (TST-008)";
        when(fs.exists(Path.of(folder))).thenReturn(true);
        when(fs.exists(Path.of(newPath))).thenReturn(true);
        // new path != current path (not equalsIgnoreCase)

        assertThrows(IllegalStateException.class,
                () -> renamer.renameIfNeeded(titleId, "New Actress", null, "TST-008"),
                "Should throw on collision");
    }

    // ── Broken-pipe retry + idempotency (BLOCKING bug regression) ─────────

    @Test
    void retryOnBrokenPipe_succeedsOnSecondAttempt() throws IOException {
        String oldFolder = "queue/Old Name (TST-012)";
        long titleId = seedTitle("TST-012", oldFolder, oldFolder + "/video/a.mp4");

        // Simulate the factory's evict+retry: the op is invoked, first fs.rename throws a
        // broken-pipe-style error, the factory reconnects and re-invokes, second succeeds.
        when(smbFactory.withRetry(eq(VOL), any())).thenAnswer(inv -> {
            SmbConnectionFactory.SmbOperation<?> op =
                    (SmbConnectionFactory.SmbOperation<?>) inv.getArgument(1);
            try {
                return op.execute(handle);
            } catch (IOException first) {
                return op.execute(handle); // evict+reopen happened; retry once
            }
        });
        doThrow(new IOException(new java.net.SocketException("Broken pipe")))
                .doNothing().when(fs).rename(any(), any());

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "New Name", null, "TST-012");

        assertTrue(outcome.renamed());
        String newFolder = "queue/New Name (TST-012)";
        assertEquals(newFolder, outcome.newPath());
        // rename was attempted twice (first throw, second success)
        verify(fs, times(2)).rename(any(), any());

        // renameFolderInDb ran exactly once (after the withRetry block) → DB rewritten.
        String locPath = jdbi.withHandle(h -> h.createQuery(
                        "SELECT path FROM title_locations WHERE title_id = :id AND stale_since IS NULL")
                .bind("id", titleId).mapTo(String.class).one());
        assertEquals(newFolder, locPath);
    }

    @Test
    void idempotentWhenAlreadyRenamed_noRenameButDbStillRewritten() throws IOException {
        String oldFolder = "queue/Old Name (TST-013)";
        long titleId = seedTitle("TST-013", oldFolder, oldFolder + "/video/a.mp4");
        String newPath = "queue/New Name (TST-013)";

        // Lost-ack case: a prior attempt already renamed on the server, but the ack was
        // lost to the broken pipe. On retry, current no longer exists and new exists.
        when(fs.exists(Path.of(oldFolder))).thenReturn(false);
        when(fs.exists(Path.of(newPath))).thenReturn(true);

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, "New Name", null, "TST-013");

        // Treated as success: no rename call, but renamed=true and DB rewritten.
        assertTrue(outcome.renamed());
        assertEquals(newPath, outcome.newPath());
        verify(fs, never()).rename(any(), any());

        String locPath = jdbi.withHandle(h -> h.createQuery(
                        "SELECT path FROM title_locations WHERE title_id = :id AND stale_since IS NULL")
                .bind("id", titleId).mapTo(String.class).one());
        assertEquals(newPath, locPath);
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

    // ── extractDescriptor (moved from UnsortedEditorService) ─────────────

    @Test
    void extractDescriptor_noSeparator_returnsEmpty() {
        assertEquals("", TitleFolderRenamer.extractDescriptor("Mana Sakura (ABP-527)", "ABP-527"));
    }

    @Test
    void extractDescriptor_withDescriptor_returnsDescriptor() {
        assertEquals("Demosaiced",
                TitleFolderRenamer.extractDescriptor("Mana Sakura - Demosaiced (ABP-527)", "ABP-527"));
    }

    @Test
    void extractDescriptor_wrongCode_returnsEmpty() {
        assertEquals("", TitleFolderRenamer.extractDescriptor("Mana Sakura - Demosaiced (ABP-527)", "WRONG-1"));
    }

    @Test
    void extractDescriptor_nullInputs_returnsEmpty() {
        assertEquals("", TitleFolderRenamer.extractDescriptor(null, "ABP-527"));
        assertEquals("", TitleFolderRenamer.extractDescriptor("Name (ABP-527)", null));
    }

    // ── renamePreservingDescriptor (Phase 2 entry point) ─────────────────

    @Test
    void renamePreservingDescriptor_happyPath_preservesDescriptor() throws IOException {
        // Current folder: "queue/Old - Demosaiced (TST-010)"
        String oldFolder = "queue/Old - Demosaiced (TST-010)";
        long titleId = seedTitle("TST-010", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renamePreservingDescriptor(titleId, "New Name", "TST-010");

        assertTrue(outcome.renamed());
        // Descriptor "Demosaiced" must be preserved in the new name
        assertEquals("queue/New Name - Demosaiced (TST-010)", outcome.newPath());
    }

    @Test
    void renamePreservingDescriptor_noDescriptor_omitsDescriptor() throws IOException {
        // Current folder has no descriptor: "queue/Old (TST-011)"
        String oldFolder = "queue/Old (TST-011)";
        long titleId = seedTitle("TST-011", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renamePreservingDescriptor(titleId, "New Actress", "TST-011");

        assertTrue(outcome.renamed());
        assertEquals("queue/New Actress (TST-011)", outcome.newPath());
    }

    // ── Amateur numeric-prefix codes (259LUXU-605) ───────────────────────

    @Test
    void renamePreservingDescriptor_amateurPrefix_preservesFullCode() throws IOException {
        // Stored code is the stripped "LUXU-605", but the on-disk folder keeps the
        // numeric distributor prefix: "(259LUXU-605)".
        String oldFolder = "queue/(259LUXU-605)";
        long titleId = seedTitle("LUXU-605", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renamePreservingDescriptor(titleId, List.of("Emiri Okazaki"), "LUXU-605");

        assertTrue(outcome.renamed());
        // The "259" prefix must survive — NOT be dropped to "(LUXU-605)".
        assertEquals("Emiri Okazaki (259LUXU-605)", TitleFolderRenamer.basename(outcome.newPath()));
        assertEquals("queue/Emiri Okazaki (259LUXU-605)", outcome.newPath());
    }

    @Test
    void renamePreservingDescriptor_amateurPrefixWithDescriptor_preservesBoth() throws IOException {
        // On-disk folder carries BOTH the numeric prefix and a "Demosaiced" descriptor.
        String oldFolder = "queue/Foo - Demosaiced (300MIUM-963)";
        long titleId = seedTitle("MIUM-963", oldFolder, oldFolder + "/video/a.mp4");

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renamePreservingDescriptor(titleId, List.of("Ayana Ushino"), "MIUM-963");

        assertTrue(outcome.renamed());
        // Both the "300" prefix AND the "Demosaiced" descriptor must be preserved.
        assertEquals("Ayana Ushino - Demosaiced (300MIUM-963)",
                TitleFolderRenamer.basename(outcome.newPath()));
        assertEquals("queue/Ayana Ushino - Demosaiced (300MIUM-963)", outcome.newPath());
    }

    @Test
    void folderCode_extractsTrailingParensCode() {
        assertEquals("259LUXU-605", TitleFolderRenamer.folderCode("Name - Demosaiced (259LUXU-605)"));
        assertEquals("300MIUM-963", TitleFolderRenamer.folderCode("(300MIUM-963)"));
        assertNull(TitleFolderRenamer.folderCode("No Parens Here"));
        // Last parenthesised group wins.
        assertEquals("ABP-123", TitleFolderRenamer.folderCode("Name (nickname) (ABP-123)"));
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

    // ── targetFolderName(List) ────────────────────────────────────────

    @Test
    void targetFolderName_list_single_byteIdenticalToStringOverload() {
        // Single-name list must produce the same output as the legacy String overload.
        String viaString = TitleFolderRenamer.targetFolderName("Mana Sakura", null, "ABP-527");
        String viaList   = TitleFolderRenamer.targetFolderName(List.of("Mana Sakura"), null, "ABP-527");
        assertEquals(viaString, viaList, "single-name list must be byte-identical to string overload");
    }

    @Test
    void targetFolderName_list_twoNames_joinsWithComma() {
        String result = TitleFolderRenamer.targetFolderName(
                List.of("Waka Misono", "Miyu Aizawa"), null, "ADN-778");
        assertEquals("Waka Misono, Miyu Aizawa (ADN-778)", result);
    }

    @Test
    void targetFolderName_list_namesAndDescriptor() {
        String result = TitleFolderRenamer.targetFolderName(
                List.of("Aika", "Koharu Suzuki"), "Demosaiced", "AVOP-212");
        assertEquals("Aika, Koharu Suzuki - Demosaiced (AVOP-212)", result);
    }

    @Test
    void targetFolderName_list_overflowBeyond200_producesVarious() {
        // Build a name long enough to exceed MAX_FOLDER_NAME_LEN (200) after assembly.
        // "A".repeat(195) + " (X-1)" = 201 chars → overflow.
        String longName = "A".repeat(195);
        String result = TitleFolderRenamer.targetFolderName(List.of(longName), null, "X-1");
        assertEquals("Various (X-1)", result,
                "basename > 200 chars (no desc) must produce 'Various (CODE)'");
    }

    @Test
    void targetFolderName_list_overflowWithDescriptor_producesVariousWithDesc() {
        String longName = "A".repeat(190);
        String result = TitleFolderRenamer.targetFolderName(List.of(longName), "Demosaiced", "X-2");
        assertEquals("Various - Demosaiced (X-2)", result,
                "basename > 200 chars with desc must produce 'Various - Desc (CODE)'");
    }

    @Test
    void targetFolderName_list_exactlyAtLimit_noOverflow() {
        // "A" + " (" + "B-1" + ")" = 8 chars; at 200 exactly no overflow.
        // Construct a name that makes the assembled basename == 200 chars.
        // basename = name + " (" + code + ")" ; code = "X-1" (3 chars) → " (X-1)" = 6 chars
        // name length = 200 - 6 = 194
        String name194 = "A".repeat(194);
        String result = TitleFolderRenamer.targetFolderName(List.of(name194), null, "X-1");
        assertEquals(name194 + " (X-1)", result,
                "basename == 200 chars must NOT overflow");
        assertEquals(200, result.length());
    }

    @Test
    void targetFolderName_list_sanitizesSpecialChars() {
        // Commas in names are kept (they're the join separator and are safe); colons are replaced.
        String result = TitleFolderRenamer.targetFolderName(
                List.of("Name:One", "Name/Two"), null, "TST-99");
        assertEquals("Name One, Name Two (TST-99)", result);
    }

    // ── usesMultiNameFolders ─────────────────────────────────────────────

    @Test
    void usesMultiNameFolders_queueIsTrue() {
        assertTrue(TitleFolderRenamer.usesMultiNameFolders("queue"),
                "staging/queue volumes must use multi-name folders");
    }

    @Test
    void usesMultiNameFolders_conventionalIsFalse() {
        assertFalse(TitleFolderRenamer.usesMultiNameFolders("conventional"));
    }

    @Test
    void usesMultiNameFolders_otherTypesAreFalse() {
        for (String type : List.of("collections", "exhibition", "sort_pool", "avstars")) {
            assertFalse(TitleFolderRenamer.usesMultiNameFolders(type),
                    "structure type '" + type + "' must NOT use multi-name folders");
        }
    }

    @Test
    void usesMultiNameFolders_nullAndBlankAreFalse() {
        assertFalse(TitleFolderRenamer.usesMultiNameFolders(null));
        assertFalse(TitleFolderRenamer.usesMultiNameFolders(""));
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
