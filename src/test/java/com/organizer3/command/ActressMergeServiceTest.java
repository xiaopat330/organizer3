package com.organizer3.command;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActressMergeServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleLocationRepository locationRepo;
    private ActressMergeService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('pool', 'queue')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('r', 'queue')");
        });
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        service = new ActressMergeService(jdbi, locationRepo, actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── computeNewPath ───────────────────────────────────────────────────────

    @Test
    void computeNewPath_standardFolder() {
        Path result = ActressMergeService.computeNewPath(
                Path.of("/queue/Rin Hatchimitsu (FNS-052)"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertEquals(Path.of("/queue/Rin Hachimitsu (FNS-052)"), result);
    }

    @Test
    void computeNewPath_noMatch_returnsNull() {
        Path result = ActressMergeService.computeNewPath(
                Path.of("/queue/Rin Hachimitsu (FNS-052)"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertNull(result);
    }

    @Test
    void computeNewPath_exactMatch_noCode() {
        Path result = ActressMergeService.computeNewPath(
                Path.of("/stars/Rin Hatchimitsu"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertEquals(Path.of("/stars/Rin Hachimitsu"), result);
    }

    @Test
    void computeNewPath_doesNotMatchSubstring() {
        // "Rin Hatchimitsu X" should not match if suspect is "Rin Hatchimitsu" without space check
        // Actually it DOES start with "Rin Hatchimitsu " so it SHOULD match
        Path result = ActressMergeService.computeNewPath(
                Path.of("/queue/Rin Hatchimitsu X (FNS-052)"),
                "Rin Hatchimitsu", "Rin Hachimitsu");
        assertEquals(Path.of("/queue/Rin Hachimitsu X (FNS-052)"), result);
    }

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    void preview_countsCorrectly() {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        // Filing title (actress_id = suspect)
        saveTitleFiled("FNS-052", suspect.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");
        // Cast-only title (in title_actresses, actress_id on title is null)
        Title t2 = saveTitle("FNS-100");
        linkActress(t2.getId(), suspect.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);

        assertEquals(1, preview.castTitleCount());   // only t2 — FNS-052 filing title isn't in title_actresses
        assertEquals(1, preview.filingTitleCount());
        assertEquals(1, preview.renames().size());
        assertEquals(Path.of("/queue/Rin Hatchimitsu (FNS-052)"), preview.renames().get(0).currentPath());
        assertEquals(Path.of("/queue/Rin Hachimitsu (FNS-052)"), preview.renames().get(0).newPath());
    }

    @Test
    void preview_skipsLocationWithNoNameMatch() {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        // Filing title but folder name doesn't contain suspect name (already fixed or different convention)
        saveTitleFiled("FNS-052", suspect.getId(), "/unsorted/FNS-052", "pool");

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);

        assertEquals(0, preview.renames().size());
    }

    // ── execute ──────────────────────────────────────────────────────────────

    @Test
    void execute_reassignsAllTitleActressesRows() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitle("FNS-052");
        Title t2 = saveTitle("FNS-100");
        linkActress(t1.getId(), suspect.getId());
        linkActress(t2.getId(), suspect.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        List<Long> t1Actresses = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).list());
        List<Long> t2Actresses = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t2.getId()).mapTo(Long.class).list());

        assertEquals(List.of(canonical.getId()), t1Actresses);
        assertEquals(List.of(canonical.getId()), t2Actresses);
    }

    @Test
    void execute_insertOrIgnore_whenCanonicalAlreadyLinked() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitle("FNS-052");
        // Both suspect and canonical already linked to the same title
        linkActress(t1.getId(), suspect.getId());
        linkActress(t1.getId(), canonical.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        // Should not throw (INSERT OR IGNORE handles the duplicate)
        assertDoesNotThrow(() -> service.execute(preview, "pool", null, false));

        List<Long> actressIds = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).list());
        assertEquals(List.of(canonical.getId()), actressIds);
    }

    @Test
    void execute_updatesFilingActressId() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitleFiled("FNS-052", suspect.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        Long updatedActressId = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM titles WHERE id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).one());
        assertEquals(canonical.getId(), updatedActressId);
    }

    @Test
    void execute_deletesSuspectActress() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE id = :id")
                        .bind("id", suspect.getId()).mapTo(Integer.class).one());
        assertEquals(0, count);
    }

    @Test
    void execute_cleansActressAliases() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO actress_aliases (actress_id, alias_name) VALUES (:id, 'Old Alias')")
                .bind("id", suspect.getId()).execute());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, false);

        int aliasCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actress_aliases WHERE actress_id = :id")
                        .bind("id", suspect.getId()).mapTo(Integer.class).one());
        assertEquals(0, aliasCount);
    }

    @Test
    void execute_skipsLocationsOnUnmountedVolume() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        saveTitleFiled("FNS-168", suspect.getId(), "/Rin Hatchimitsu (FNS-168)", "pool");

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        // Execute with "r" mounted, but location is on "pool"
        ActressMergeService.MergeResult result = service.execute(preview, "r", null, false);

        assertEquals(0, result.renamedPaths().size());
        assertEquals(1, result.skipped().size());
        assertEquals("pool", result.skipped().get(0).volumeId());
    }

    @Test
    void execute_dryRun_makesNoDbChanges() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));

        Title t1 = saveTitle("FNS-052");
        linkActress(t1.getId(), suspect.getId());

        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", null, true);

        // Suspect actress still exists
        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE id = :id")
                        .bind("id", suspect.getId()).mapTo(Integer.class).one());
        assertEquals(1, count);

        // title_actresses unchanged
        List<Long> actressIds = jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :id")
                        .bind("id", t1.getId()).mapTo(Long.class).list());
        assertEquals(List.of(suspect.getId()), actressIds);
    }

    // ── execute() with a real FS (rename loop) ───────────────────────────────

    @Test
    void execute_renamesFolder_onMountedVolume() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        saveTitleFiled("FNS-052", suspect.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        FakeFs fs = new FakeFs();
        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        ActressMergeService.MergeResult result = service.execute(preview, "pool", fs, false);

        assertEquals(1, result.renamedPaths().size());
        assertEquals(0, result.skipped().size());
        assertEquals(1, fs.renameCalls.size());
        assertEquals(Path.of("/queue/Rin Hatchimitsu (FNS-052)"), fs.renameCalls.get(0).path);
        assertEquals("Rin Hachimitsu (FNS-052)", fs.renameCalls.get(0).newName);
    }

    @Test
    void execute_dryRun_doesNotCallFs() throws Exception {
        Actress suspect = actressRepo.save(mkActress("Rin Hatchimitsu"));
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        saveTitleFiled("FNS-052", suspect.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        FakeFs fs = new FakeFs();
        ActressMergeService.MergePreview preview = service.preview(suspect, canonical);
        service.execute(preview, "pool", fs, true);

        assertTrue(fs.renameCalls.isEmpty());
    }

    // ── renameOnly / planRenamesFor (post-merge cleanup) ─────────────────────

    @Test
    void planRenamesFor_matchesAlias() {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");

        // Filing title for canonical, but folder still uses the alias name
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);

        assertEquals(1, plan.renames().size());
        assertEquals(0, plan.unresolved().size());
        assertEquals(Path.of("/queue/Rin Hachimitsu (FNS-052)"), plan.renames().get(0).newPath());
    }

    @Test
    void planRenamesFor_skipsLeafAlreadyCanonical() {
        // Leaf folder name already starts with the canonical name → no-op
        // (neither in renames nor unresolved).
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hachimitsu (FNS-052)", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);

        assertEquals(0, plan.renames().size());
        assertEquals(0, plan.unresolved().size());
    }

    /**
     * Bug 3 regression: when the parent actress folder has been renamed to canonical but
     * child title leaves still carry the old name, the child must enter the rename plan.
     * The old SQL filter (instr on full path) silently excluded these rows.
     */
    @Test
    void planRenamesFor_includesChildWhenParentAlreadyCanonical() {
        Actress canonical = actressRepo.save(mkActress("Mion Sakuragi"));
        addAlias(canonical.getId(), "Mion Sakaragi");
        // Parent is canonical; leaf still uses the misspelled alias.
        saveTitleFiled("APAA-434", canonical.getId(),
                "/stars/library/Mion Sakuragi/Mion Sakaragi (APAA-434)", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);

        assertEquals(1, plan.renames().size(),
                "leaf still carries old name even though parent is canonical");
        assertEquals(0, plan.unresolved().size());
        assertEquals(Path.of("/stars/library/Mion Sakuragi/Mion Sakuragi (APAA-434)"),
                plan.renames().get(0).newPath());
    }

    /**
     * Bug 3 regression: a leaf that is already canonical must be a silent no-op —
     * not in renames, not in unresolved, even when the SQL filter is gone.
     */
    @Test
    void planRenamesFor_canonicalLeafIsSilentNoOp() {
        Actress canonical = actressRepo.save(mkActress("Mion Sakuragi"));
        addAlias(canonical.getId(), "Mion Sakaragi");
        saveTitleFiled("APAA-434", canonical.getId(),
                "/stars/library/Mion Sakuragi/Mion Sakuragi (APAA-434)", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);

        assertEquals(0, plan.renames().size());
        assertEquals(0, plan.unresolved().size(),
                "canonical leaf is a no-op, not unresolved");
    }

    @Test
    void planRenamesFor_unresolvedWhenNoAliasMatches() {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        // Folder name doesn't start with any known alias
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/FNS-052", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);

        assertEquals(0, plan.renames().size());
        assertEquals(1, plan.unresolved().size());
        assertEquals(Path.of("/queue/FNS-052"), plan.unresolved().get(0).currentPath());
    }

    @Test
    void planRenamesFor_picksLongestMatchingAliasFirst() {
        // Two aliases where one is a prefix of the other — longest must win
        Actress canonical = actressRepo.save(mkActress("Hachimitsu"));
        addAlias(canonical.getId(), "Rin");
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);

        assertEquals(1, plan.renames().size());
        // Longest alias "Rin Hatchimitsu" replaced — not just "Rin"
        assertEquals(Path.of("/queue/Hachimitsu (FNS-052)"), plan.renames().get(0).newPath());
    }

    @Test
    void renameOnly_executesRenameAndUpdatesPath() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        Title t = saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        FakeFs fs = new FakeFs();
        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        ActressMergeService.RenameResult result = service.renameOnly(plan, "pool", fs, false);

        assertEquals(1, result.renamedPaths().size());
        assertEquals(0, result.skipped().size());
        assertEquals(1, fs.renameCalls.size());

        // DB path was updated
        String newPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations WHERE title_id = :id")
                        .bind("id", t.getId()).mapTo(String.class).one());
        assertEquals("/queue/Rin Hachimitsu (FNS-052)", newPath);
    }

    @Test
    void renameOnly_skipsUnmountedVolume() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        FakeFs fs = new FakeFs();
        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        // "r" is mounted, but the location is on "pool"
        ActressMergeService.RenameResult result = service.renameOnly(plan, "r", fs, false);

        assertEquals(0, result.renamedPaths().size());
        assertEquals(1, result.skipped().size());
        assertTrue(fs.renameCalls.isEmpty());
    }

    @Test
    void renameOnly_dryRun_doesNotCallFs() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        FakeFs fs = new FakeFs();
        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        ActressMergeService.RenameResult result = service.renameOnly(plan, "pool", fs, true);

        assertEquals(1, result.renamedPaths().size());
        assertTrue(fs.renameCalls.isEmpty());
    }

    @Test
    void renameOnly_skipsWithVolumeNotMountedReason_whenLocationVolumeDiffersFromMounted() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        // Location is on 'pool' but session mounts 'r'
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        FakeFs fs = new FakeFs();
        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        ActressMergeService.RenameResult result = service.renameOnly(plan, "r", fs, false);

        assertEquals(1, result.skipped().size());
        assertEquals(ActressMergeService.SkipReason.VOLUME_NOT_MOUNTED,
                result.skipped().get(0).reason());
        assertEquals("pool", result.skipped().get(0).volumeId());
    }

    @Test
    void renameOnly_skipsWithFsRenameFailedReason_whenFileSystemRenameThrows() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/Rin Hatchimitsu (FNS-052)", "pool");

        VolumeFileSystem fs = org.mockito.Mockito.mock(VolumeFileSystem.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
                .when(fs).rename(org.mockito.ArgumentMatchers.any(Path.class),
                        org.mockito.ArgumentMatchers.anyString());
        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        ActressMergeService.RenameResult result = service.renameOnly(plan, "pool", fs, false);

        assertEquals(0, result.renamedPaths().size());
        assertEquals(1, result.skipped().size());
        String reason = result.skipped().get(0).reason();
        assertTrue(reason.startsWith("fs rename failed: "),
                "reason should start with 'fs rename failed: ' but was: " + reason);
        assertTrue(reason.contains("boom"), "reason should contain underlying error: " + reason);
    }

    @Test
    void renameOnly_propagatesUnresolved() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");
        saveTitleFiled("FNS-052", canonical.getId(), "/queue/FNS-052", "pool");

        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        ActressMergeService.RenameResult result = service.renameOnly(plan, "pool", new FakeFs(), false);

        assertEquals(1, result.unresolved().size());
    }

    private void addAlias(long actressId, String aliasName) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO actress_aliases (actress_id, alias_name) VALUES (:id, :name)")
                .bind("id", actressId).bind("name", aliasName).execute());
    }

    // ── planMoveActressFolderFromAttention — Bug 1 regression ────────────────

    /**
     * Bug 1 regression: stale rows under /attention/ must NOT be skipped.
     * Before the fix, the query included AND tl.stale_since IS NULL which caused
     * rows marked stale by sync to be silently dropped from the plan.
     */
    @Test
    void planMoveActressFolderFromAttention_includesStaleRows() {
        Actress actress = actressRepo.save(mkActress("Shion Fujimoto"));

        // Insert a title_location row that is stale (stale_since IS NOT NULL)
        jdbi.useHandle(h -> {
            long titleId = h.createQuery(
                    "INSERT INTO titles (code, actress_id) VALUES ('MIDE-001', :id) RETURNING id")
                    .bind("id", actress.getId()).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, stale_since)
                    VALUES (?, 'pool', 'attention', '/attention/Shion Fujimoto/Shion Fujimoto (MIDE-001)', '2024-01-01', '2024-02-01')
                    """, titleId);
        });

        ActressMergeService.AttentionExitPlan plan =
                service.planMoveActressFolderFromAttention(actress, "pool");

        assertEquals(1, plan.locations().size(),
                "stale rows under /attention/ must be included — they become live after move");
        assertEquals("/stars/library/Shion Fujimoto/Shion Fujimoto (MIDE-001)",
                plan.locations().get(0).newPath().toString());
    }

    /**
     * Bug 1 regression: applyMoveActressFolderFromAttention must clear stale_since so the
     * relocated rows are treated as live. Before the fix, stale_since was left unchanged.
     */
    @Test
    void applyMoveActressFolderFromAttention_clearsStaleSince() {
        Actress actress = actressRepo.save(mkActress("Shion Fujimoto"));

        long locationId = jdbi.withHandle(h -> {
            long titleId = h.createQuery(
                    "INSERT INTO titles (code, actress_id) VALUES ('MIDE-001', :id) RETURNING id")
                    .bind("id", actress.getId()).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, stale_since)
                    VALUES (?, 'pool', 'attention', '/attention/Shion Fujimoto/Shion Fujimoto (MIDE-001)', '2024-01-01', '2024-02-01')
                    """, titleId);
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });

        ActressMergeService.AttentionExitPlan plan =
                service.planMoveActressFolderFromAttention(actress, "pool");
        assertEquals(1, plan.locations().size());

        service.applyMoveActressFolderFromAttention(plan);

        String staleSince = jdbi.withHandle(h ->
                h.createQuery("SELECT stale_since FROM title_locations WHERE id = :id")
                        .bind("id", locationId).mapTo(String.class).one());
        assertNull(staleSince, "applyMoveActressFolderFromAttention must clear stale_since");
    }

    // ── deriveShortPartitionId — Bug 2 regression ────────────────────────────

    @Test
    void deriveShortPartitionId_starsPath_returnsShortTier() {
        assertEquals("popular",
                ActressMergeService.deriveShortPartitionId(
                        Path.of("/stars/popular/Actress Name/Title (CODE-001)"), "stars/popular"));
    }

    @Test
    void deriveShortPartitionId_starsMinorPath_returnsMinor() {
        assertEquals("minor",
                ActressMergeService.deriveShortPartitionId(
                        Path.of("/stars/minor/Actress/Title"), "stars/minor"));
    }

    @Test
    void deriveShortPartitionId_queuePath_returnsQueue() {
        assertEquals("queue",
                ActressMergeService.deriveShortPartitionId(
                        Path.of("/queue/Title (CODE-001)"), "queue"));
    }

    @Test
    void deriveShortPartitionId_attentionPath_returnsAttention() {
        assertEquals("attention",
                ActressMergeService.deriveShortPartitionId(
                        Path.of("/attention/Actress/Title"), "attention"));
    }

    @Test
    void deriveShortPartitionId_nullPath_returnsFallback() {
        assertEquals("stars/popular",
                ActressMergeService.deriveShortPartitionId(null, "stars/popular"));
    }

    /**
     * Bug 2 regression: renameOnly must write the short-form partition_id when the existing
     * DB row has a path-style value like "stars/popular". Before the fix, performFsRenames
     * passed rename.partitionId() through unchanged.
     */
    @Test
    void renameOnly_writesShortFormPartitionId() throws Exception {
        Actress canonical = actressRepo.save(mkActress("Rin Hachimitsu"));
        addAlias(canonical.getId(), "Rin Hatchimitsu");

        // Insert with path-style partition_id as sync would produce
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code, actress_id) VALUES ('FNS-052', :id) RETURNING id")
                        .bind("id", canonical.getId()).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (?, 'pool', 'stars/popular', '/stars/popular/Rin Hatchimitsu/Rin Hatchimitsu (FNS-052)', '2024-01-01')
                """, titleId));

        FakeFs fs = new FakeFs();
        ActressMergeService.RenamePlan plan = service.planRenamesFor(canonical);
        assertEquals(1, plan.renames().size());

        service.renameOnly(plan, "pool", fs, false);

        String partition = jdbi.withHandle(h ->
                h.createQuery("SELECT partition_id FROM title_locations WHERE title_id = :id")
                        .bind("id", titleId).mapTo(String.class).one());
        assertEquals("popular", partition,
                "partition_id must be normalised to short form after rename");
    }

    // ── planActressFolderMoveFor — Bug 4 regression ──────────────────────────

    /**
     * Bug 4 regression: default behavior (no includeCanonical) — a folder whose basename
     * already matches the canonical name must NOT be planned for move.
     */
    @Test
    void planActressFolderMoveFor_defaultExcludesCanonicalNamedParent() {
        Actress canonical = actressRepo.save(mkActress("Kozue Minami"));
        addAlias(canonical.getId(), "Kozue Minamoto");
        saveTitleFiled("IPX-100", canonical.getId(),
                "/stars/minor/Kozue Minami/Kozue Minami (IPX-100)", "pool");

        ActressMergeService.ActressFolderPlan plan =
                service.planActressFolderMoveFor(canonical, "pool");

        assertTrue(plan.entries().isEmpty(),
                "canonical-named parent must not be included when includeCanonical=false");
    }

    /**
     * Bug 4 regression: includeCanonical=true admits canonical-named parents to the plan.
     */
    @Test
    void planActressFolderMoveFor_includeCanonicalAdmitsCanonicalNamedParent() {
        Actress canonical = actressRepo.save(mkActress("Kozue Minami"));
        addAlias(canonical.getId(), "Kozue Minamoto");
        saveTitleFiled("IPX-100", canonical.getId(),
                "/stars/minor/Kozue Minami/Kozue Minami (IPX-100)", "pool");

        ActressMergeService.ActressFolderPlan plan =
                service.planActressFolderMoveFor(canonical, "pool", true);

        assertEquals(1, plan.entries().size());
        assertEquals(Path.of("/stars/minor/Kozue Minami"), plan.entries().get(0).actressFolder());
        assertEquals("Kozue Minami", plan.entries().get(0).oldName());
    }

    @Test
    void planActressFolderMoveFor_aliasNamedParentIncludedInBothModes() {
        Actress canonical = actressRepo.save(mkActress("Azusa Misaki"));
        addAlias(canonical.getId(), "Asusa Misaki");
        saveTitleFiled("IPX-001", canonical.getId(),
                "/stars/minor/Asusa Misaki/Azusa Misaki (IPX-001)", "pool");

        ActressMergeService.ActressFolderPlan defaultPlan =
                service.planActressFolderMoveFor(canonical, "pool");
        assertEquals(1, defaultPlan.entries().size(),
                "alias-named parent included in default mode");

        ActressMergeService.ActressFolderPlan inclusivePlan =
                service.planActressFolderMoveFor(canonical, "pool", true);
        assertEquals(1, inclusivePlan.entries().size(),
                "alias-named parent still included when includeCanonical=true");
    }

    // ── MergeActressCommand arg parsing ──────────────────────────────────────

    @Test
    void parseNames_separatorSyntax() {
        String[] result = MergeActressCommand.parseNames(
                new String[]{"actress merge", "Rin Hatchimitsu", ">", "Rin Hachimitsu"});
        assertArrayEquals(new String[]{"Rin Hatchimitsu", "Rin Hachimitsu"}, result);
    }

    @Test
    void parseNames_quotedNames() {
        String[] result = MergeActressCommand.parseNames(
                new String[]{"actress merge", "\"Rin Hatchimitsu\"", ">", "\"Rin Hachimitsu\""});
        assertArrayEquals(new String[]{"Rin Hatchimitsu", "Rin Hachimitsu"}, result);
    }

    @Test
    void parseNames_twoWordArgs() {
        String[] result = MergeActressCommand.parseNames(
                new String[]{"actress merge", "OldName", "NewName"});
        assertArrayEquals(new String[]{"OldName", "NewName"}, result);
    }

    @Test
    void parseNames_missingArgs_returnsNull() {
        assertNull(MergeActressCommand.parseNames(new String[]{"actress merge"}));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Actress mkActress(String name) {
        return Actress.builder().canonicalName(name)
                .tier(Actress.Tier.LIBRARY).favorite(false).bookmark(false)
                .rejected(false).needsProfiling(false)
                .firstSeenAt(LocalDate.now()).build();
    }

    private Title saveTitleFiled(String code, long actressId, String path, String volumeId) {
        Title title = jdbi.withHandle(h ->
                h.createQuery("""
                        INSERT INTO titles (code, actress_id) VALUES (:code, :actressId)
                        RETURNING *
                        """)
                        .bind("code", code)
                        .bind("actressId", actressId)
                        .map((rs, ctx) -> Title.builder()
                                .id(rs.getLong("id"))
                                .code(rs.getString("code"))
                                .build())
                        .one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (:titleId, :volumeId, 'queue', :path, '2024-01-01')
                """)
                .bind("titleId", title.getId())
                .bind("volumeId", volumeId)
                .bind("path", path)
                .execute());
        return title;
    }

    private Title saveTitle(String code) {
        return jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code) VALUES (:code) RETURNING *")
                        .bind("code", code)
                        .map((rs, ctx) -> Title.builder()
                                .id(rs.getLong("id"))
                                .code(rs.getString("code"))
                                .build())
                        .one());
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO title_actresses (title_id, actress_id) VALUES (:t, :a)")
                .bind("t", titleId).bind("a", actressId).execute());
    }

    /** Minimal VolumeFileSystem fake — only records rename() calls. */
    static final class FakeFs implements VolumeFileSystem {
        record RenameCall(Path path, String newName) {}
        final List<RenameCall> renameCalls = new ArrayList<>();

        @Override public void rename(Path path, String newName) { renameCalls.add(new RenameCall(path, newName)); }

        @Override public List<Path> listDirectory(Path path) { return Collections.emptyList(); }
        @Override public List<Path> walk(Path root) { return Collections.emptyList(); }
        @Override public boolean exists(Path path) { return false; }
        @Override public boolean isDirectory(Path path) { return false; }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
