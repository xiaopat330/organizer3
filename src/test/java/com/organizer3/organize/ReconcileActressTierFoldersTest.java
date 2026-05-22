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

class ReconcileActressTierFoldersTest {

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

    // ── move UP ─────────────────────────────────────────────────────────────

    @Test
    void moveUp_libraryToSuperstar() throws Exception {
        long aid = actressRepo.save(mkActress("Yuma Asami", Actress.Tier.SUPERSTAR)).getId();
        // Folder stranded at library; DB says SUPERSTAR
        for (int i = 1; i <= 3; i++) {
            Title t = titleRepo.save(mkTitle("ABP-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/library/Yuma Asami/Yuma Asami (ABP-%03d)".formatted(i), "library");
            createFolder("/stars/library/Yuma Asami/Yuma Asami (ABP-%03d)".formatted(i));
        }

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.MOVED, r.outcome());
        assertEquals("library", r.fromTier());
        assertEquals("superstar", r.toTier());
        assertTrue(fs.exists(Path.of("/stars/superstar/Yuma Asami")));
        assertFalse(fs.exists(Path.of("/stars/library/Yuma Asami")));

        List<TitleLocation> locs = locationRepo.findByVolume("a");
        for (TitleLocation l : locs) {
            assertEquals("superstar", l.getPartitionId(), "partition_id updated");
            assertTrue(l.getPath().startsWith(Path.of("/stars/superstar/Yuma Asami")),
                    "path rewritten: " + l.getPath());
        }
    }

    // ── move DOWN ────────────────────────────────────────────────────────────

    @Test
    void moveDown_popularToLibrary() throws Exception {
        // DB says LIBRARY; folder stranded at popular (DB was demoted but folder stayed)
        long aid = actressRepo.save(mkActress("Nana Ogura", Actress.Tier.LIBRARY)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("IPX-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/popular/Nana Ogura/Nana Ogura (IPX-%03d)".formatted(i), "popular");
            createFolder("/stars/popular/Nana Ogura/Nana Ogura (IPX-%03d)".formatted(i));
        }

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.MOVED, r.outcome());
        assertEquals("popular", r.fromTier());
        assertEquals("library", r.toTier());
        assertTrue(fs.exists(Path.of("/stars/library/Nana Ogura")));
        assertFalse(fs.exists(Path.of("/stars/popular/Nana Ogura")));

        List<TitleLocation> locs = locationRepo.findByVolume("a");
        for (TitleLocation l : locs) {
            assertEquals("library", l.getPartitionId());
            assertTrue(l.getPath().startsWith(Path.of("/stars/library/Nana Ogura")));
        }
    }

    // ── already correct ──────────────────────────────────────────────────────

    @Test
    void alreadyAtCorrectTier_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Sora Aoi", Actress.Tier.POPULAR)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("GVG-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/popular/Sora Aoi/Sora Aoi (GVG-%03d)".formatted(i), "popular");
            createFolder("/stars/popular/Sora Aoi/Sora Aoi (GVG-%03d)".formatted(i));
        }

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("already at correct tier"));
    }

    // ── straggler tolerance ───────────────────────────────────────────────────

    @Test
    void stragglerTolerance_queueAndCompLocationsLeftUntouched() throws Exception {
        // DB SUPERSTAR; folder at minor; some titles also in queue and comps — those stay put.
        long aid = actressRepo.save(mkActress("Ai Uehara", Actress.Tier.SUPERSTAR)).getId();

        // The tier folder to be moved: minor
        for (int i = 1; i <= 3; i++) {
            Title t = titleRepo.save(mkTitle("MIDE-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/minor/Ai Uehara/Ai Uehara (MIDE-%03d)".formatted(i), "minor");
            createFolder("/stars/minor/Ai Uehara/Ai Uehara (MIDE-%03d)".formatted(i));
        }
        // Straggler in queue — must not be touched
        Title queueTitle = titleRepo.save(mkTitle("MIDE-999", aid));
        saveLoc(queueTitle.getId(), "/queue/Ai Uehara (MIDE-999)", "queue");

        // Straggler in comps partition — must not be touched
        Title compTitle = titleRepo.save(mkTitle("HMPD-001", aid));
        saveLoc(compTitle.getId(), "/stars/popular/Various Artists/HMPD-001", "popular");

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.MOVED, r.outcome());
        assertEquals("minor", r.fromTier());
        assertEquals("superstar", r.toTier());

        // The tier folder moved
        assertTrue(fs.exists(Path.of("/stars/superstar/Ai Uehara")));
        assertFalse(fs.exists(Path.of("/stars/minor/Ai Uehara")));

        // Only the 3 title_locations under /stars/minor/Ai Uehara were rewritten
        List<TitleLocation> locs = locationRepo.findByVolume("a");
        for (TitleLocation l : locs) {
            String p = l.getPath().toString();
            if (p.startsWith("/stars/minor/Ai Uehara") || p.startsWith("/stars/superstar/Ai Uehara")) {
                // These were the moved ones — expect superstar now
                assertEquals("superstar", l.getPartitionId(), "tier loc should be rewritten: " + p);
                assertTrue(l.getPath().startsWith(Path.of("/stars/superstar/Ai Uehara")));
            } else if (p.startsWith("/queue/")) {
                assertEquals("queue", l.getPartitionId(), "queue loc must be untouched");
                assertEquals(Path.of("/queue/Ai Uehara (MIDE-999)"), l.getPath());
            } else {
                // The comp straggler in /stars/popular/Various Artists
                assertEquals("popular", l.getPartitionId(), "comp straggler must be untouched");
                assertEquals(Path.of("/stars/popular/Various Artists/HMPD-001"), l.getPath());
            }
        }
    }

    // ── multi-tier skip ───────────────────────────────────────────────────────

    @Test
    void multiTier_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Akari Mitani", Actress.Tier.POPULAR)).getId();
        // Folder exists at both library AND minor (pathological)
        createFolder("/stars/library/Akari Mitani");
        createFolder("/stars/minor/Akari Mitani");

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("multiple tiers"), "reason: " + r.reason());
    }

    // ── collision ─────────────────────────────────────────────────────────────

    @Test
    void collision_targetAlreadyExists_failed() throws Exception {
        long aid = actressRepo.save(mkActress("Yua Mikami", Actress.Tier.GODDESS)).getId();
        // Folder stranded at superstar, but goddess folder also exists
        createFolder("/stars/superstar/Yua Mikami");
        createFolder("/stars/goddess/Yua Mikami");  // collision

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.FAILED, r.outcome());
        assertTrue(r.reason().contains("collision"), "reason: " + r.reason());
    }

    // ── folder not found ─────────────────────────────────────────────────────

    @Test
    void folderNotFound_skipped() throws Exception {
        long aid = actressRepo.save(mkActress("Ghost Actress", Actress.Tier.POPULAR)).getId();
        // No folder created

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("not found"), "reason: " + r.reason());
    }

    // ── dryRun ────────────────────────────────────────────────────────────────

    @Test
    void dryRun_leavesFilesAndDbUntouched() throws Exception {
        long aid = actressRepo.save(mkActress("Rina Ishihara", Actress.Tier.POPULAR)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("RBD-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/minor/Rina Ishihara/Rina Ishihara (RBD-%03d)".formatted(i), "minor");
            createFolder("/stars/minor/Rina Ishihara/Rina Ishihara (RBD-%03d)".formatted(i));
        }

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, true);

        assertEquals(ActressClassifierService.Outcome.WOULD_MOVE, r.outcome());
        assertEquals("minor", r.fromTier());
        assertEquals("popular", r.toTier());

        // File not moved
        assertTrue(fs.exists(Path.of("/stars/minor/Rina Ishihara")));
        assertFalse(fs.exists(Path.of("/stars/popular/Rina Ishihara")));

        // DB not changed
        List<TitleLocation> locs = locationRepo.findByVolume("a");
        for (TitleLocation l : locs) {
            assertEquals("minor", l.getPartitionId());
        }
    }

    // ── batch mode ────────────────────────────────────────────────────────────

    @Test
    void batch_reconcilesMismatchedActresses_skipsCorrect() throws Exception {
        // Actress 1: DB POPULAR, folder at minor — should be moved
        long aid1 = actressRepo.save(mkActress("Actress One", Actress.Tier.POPULAR)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("A1-%03d".formatted(i), aid1));
            saveLoc(t.getId(), "/stars/minor/Actress One/Actress One (A1-%03d)".formatted(i), "minor");
            createFolder("/stars/minor/Actress One/Actress One (A1-%03d)".formatted(i));
        }

        // Actress 2: DB LIBRARY, folder at library — already correct, should NOT appear in results
        long aid2 = actressRepo.save(mkActress("Actress Two", Actress.Tier.LIBRARY)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("A2-%03d".formatted(i), aid2));
            saveLoc(t.getId(), "/stars/library/Actress Two/Actress Two (A2-%03d)".formatted(i), "library");
            createFolder("/stars/library/Actress Two/Actress Two (A2-%03d)".formatted(i));
        }

        // Actress 3: DB SUPERSTAR, folder at popular — should be moved
        long aid3 = actressRepo.save(mkActress("Actress Three", Actress.Tier.SUPERSTAR)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("A3-%03d".formatted(i), aid3));
            saveLoc(t.getId(), "/stars/popular/Actress Three/Actress Three (A3-%03d)".formatted(i), "popular");
            createFolder("/stars/popular/Actress Three/Actress Three (A3-%03d)".formatted(i));
        }

        List<ActressClassifierService.Result> results =
                svc.reconcileTierFoldersOnVolume(fs, volumeA(), jdbi, false);

        // Actress Two is already correct — not included
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.outcome() == ActressClassifierService.Outcome.MOVED));

        // Actress One: minor → popular
        ActressClassifierService.Result r1 = results.stream()
                .filter(r -> r.actressName().equals("Actress One")).findFirst().orElseThrow();
        assertEquals("minor", r1.fromTier());
        assertEquals("popular", r1.toTier());

        // Actress Three: popular → superstar
        ActressClassifierService.Result r3 = results.stream()
                .filter(r -> r.actressName().equals("Actress Three")).findFirst().orElseThrow();
        assertEquals("popular", r3.fromTier());
        assertEquals("superstar", r3.toTier());

        // Verify file moves happened
        assertTrue(fs.exists(Path.of("/stars/popular/Actress One")));
        assertFalse(fs.exists(Path.of("/stars/minor/Actress One")));
        assertTrue(fs.exists(Path.of("/stars/superstar/Actress Three")));
        assertFalse(fs.exists(Path.of("/stars/popular/Actress Three")));
    }

    @Test
    void batch_dryRun_makesNoChanges() throws Exception {
        long aid = actressRepo.save(mkActress("Dry Run Actress", Actress.Tier.GODDESS)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("DRY-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/superstar/Dry Run Actress/Dry Run Actress (DRY-%03d)".formatted(i), "superstar");
            createFolder("/stars/superstar/Dry Run Actress/Dry Run Actress (DRY-%03d)".formatted(i));
        }

        List<ActressClassifierService.Result> results =
                svc.reconcileTierFoldersOnVolume(fs, volumeA(), jdbi, true);

        assertEquals(1, results.size());
        assertEquals(ActressClassifierService.Outcome.WOULD_MOVE, results.get(0).outcome());

        // Files unchanged
        assertTrue(fs.exists(Path.of("/stars/superstar/Dry Run Actress")));
        assertFalse(fs.exists(Path.of("/stars/goddess/Dry Run Actress")));

        // DB unchanged
        List<TitleLocation> locs = locationRepo.findByVolume("a");
        for (TitleLocation l : locs) assertEquals("superstar", l.getPartitionId());
    }

    @Test
    void batch_perActressFailures_doNotAbortBatch() throws Exception {
        // Actress with collision — should produce FAILED but not abort.
        // (Needs a title_location so the DB-derived prefilter discovers her.)
        long aidC = actressRepo.save(mkActress("Collision Actress", Actress.Tier.GODDESS)).getId();
        Title ct = titleRepo.save(mkTitle("COL-001", aidC));
        saveLoc(ct.getId(), "/stars/superstar/Collision Actress/Collision Actress (COL-001)", "superstar");
        createFolder("/stars/superstar/Collision Actress");
        createFolder("/stars/goddess/Collision Actress");  // collision

        // Actress that needs a clean move — should succeed despite the earlier failure
        long aid2 = actressRepo.save(mkActress("Clean Actress", Actress.Tier.MINOR)).getId();
        for (int i = 1; i <= 2; i++) {
            Title t = titleRepo.save(mkTitle("CLN-%03d".formatted(i), aid2));
            saveLoc(t.getId(), "/stars/library/Clean Actress/Clean Actress (CLN-%03d)".formatted(i), "library");
            createFolder("/stars/library/Clean Actress/Clean Actress (CLN-%03d)".formatted(i));
        }

        List<ActressClassifierService.Result> results =
                svc.reconcileTierFoldersOnVolume(fs, volumeA(), jdbi, false);

        assertEquals(2, results.size());
        long failed = results.stream().filter(r -> r.outcome() == ActressClassifierService.Outcome.FAILED).count();
        long moved  = results.stream().filter(r -> r.outcome() == ActressClassifierService.Outcome.MOVED).count();
        assertEquals(1, failed);
        assertEquals(1, moved);
    }

    // ── prefilter coverage ─────────────────────────────────────────────────────

    @Test
    void batch_prefilter_mixedFixture_matchesExpectedResultSet() throws Exception {
        // 1. Real mismatch — DB POPULAR, folder at minor → WOULD_MOVE
        long aidMove = actressRepo.save(mkActress("Move Me", Actress.Tier.POPULAR)).getId();
        Title tm = titleRepo.save(mkTitle("MV-001", aidMove));
        saveLoc(tm.getId(), "/stars/minor/Move Me/Move Me (MV-001)", "minor");
        createFolder("/stars/minor/Move Me/Move Me (MV-001)");

        // 2. Already correct — DB LIBRARY, folder at library → no Result
        long aidOk = actressRepo.save(mkActress("Already Ok", Actress.Tier.LIBRARY)).getId();
        Title to = titleRepo.save(mkTitle("OK-001", aidOk));
        saveLoc(to.getId(), "/stars/library/Already Ok/Already Ok (OK-001)", "library");
        createFolder("/stars/library/Already Ok/Already Ok (OK-001)");

        // 3. Ambiguous canonical name — two phantom-dup actresses differing only in case
        //    (canonical_name UNIQUE is case-sensitive; the NOCASE map collapses them) → SKIPPED, no move
        long aidDup = actressRepo.save(mkActress("Dup Name", Actress.Tier.POPULAR)).getId();
        actressRepo.save(mkActress("dup name", Actress.Tier.SUPERSTAR));
        Title td = titleRepo.save(mkTitle("DUP-001", aidDup));
        saveLoc(td.getId(), "/stars/minor/Dup Name/Dup Name (DUP-001)", "minor");
        createFolder("/stars/minor/Dup Name/Dup Name (DUP-001)");

        // 4. Pool-only folder — must never be proposed
        long aidPool = actressRepo.save(mkActress("Pool Lady", Actress.Tier.POPULAR)).getId();
        Title tp = titleRepo.save(mkTitle("PL-001", aidPool));
        saveLoc(tp.getId(), "/stars/pool/Pool Lady/Pool Lady (PL-001)", "pool");

        // 5. Documented gap — folder exists on disk but no title_location on volume → no Result
        actressRepo.save(mkActress("No Locs", Actress.Tier.SUPERSTAR));
        createFolder("/stars/minor/No Locs");

        List<ActressClassifierService.Result> results =
                svc.reconcileTierFoldersOnVolume(fs, volumeA(), jdbi, true);

        // Exactly two Results: the mismatch (WOULD_MOVE) and the ambiguous name (SKIPPED).
        assertEquals(2, results.size(), "results: " + results);

        ActressClassifierService.Result move = results.stream()
                .filter(r -> r.actressName().equals("Move Me")).findFirst().orElseThrow();
        assertEquals(ActressClassifierService.Outcome.WOULD_MOVE, move.outcome());
        assertEquals("minor", move.fromTier());
        assertEquals("popular", move.toTier());

        ActressClassifierService.Result amb = results.stream()
                .filter(r -> r.actressName().equals("Dup Name")).findFirst().orElseThrow();
        assertEquals(ActressClassifierService.Outcome.SKIPPED, amb.outcome());
        assertTrue(amb.reason().contains("ambiguous canonical name"), "reason: " + amb.reason());

        // No Result for the already-correct, pool-only, or no-locs actresses.
        assertTrue(results.stream().noneMatch(r -> r.actressName().equals("Already Ok")));
        assertTrue(results.stream().noneMatch(r -> r.actressName().equals("Pool Lady")));
        assertTrue(results.stream().noneMatch(r -> r.actressName().equals("No Locs")));
    }

    @Test
    void batch_ambiguousName_isSkipped_neverProposesMove() throws Exception {
        // Two phantom-dup actresses share a canonical name (case-only difference); folder is mis-tiered.
        long aidAmbi = actressRepo.save(mkActress("Ambi Star", Actress.Tier.POPULAR)).getId();
        actressRepo.save(mkActress("ambi star", Actress.Tier.GODDESS));
        Title t = titleRepo.save(mkTitle("AMB-001", aidAmbi));
        saveLoc(t.getId(), "/stars/minor/Ambi Star/Ambi Star (AMB-001)", "minor");
        createFolder("/stars/minor/Ambi Star/Ambi Star (AMB-001)");

        List<ActressClassifierService.Result> results =
                svc.reconcileTierFoldersOnVolume(fs, volumeA(), jdbi, false);

        assertEquals(1, results.size());
        ActressClassifierService.Result r = results.get(0);
        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertEquals("Ambi Star", r.actressName());
        assertEquals(-1L, r.actressId());
        assertTrue(r.reason().contains("ambiguous canonical name"), "reason: " + r.reason());

        // No move proposed and nothing on disk changed.
        assertTrue(results.stream().noneMatch(x ->
                x.outcome() == ActressClassifierService.Outcome.WOULD_MOVE
                        || x.outcome() == ActressClassifierService.Outcome.MOVED));
        assertTrue(fs.exists(Path.of("/stars/minor/Ambi Star")));
    }

    // ── zero-location / case-mismatch guard ───────────────────────────────────

    @Test
    void caseMismatch_folderFoundButNoLocationsRebase_skipped() throws Exception {
        // DB canonical is "FooBar"; on-disk folder + location use a different case ("Foobar").
        // findAllDiskTiers (case-insensitive, like SMB) matches the folder, but the case-SENSITIVE
        // Path.startsWith filter rebases zero rows — applying would orphan every location.
        long aid = actressRepo.save(mkActress("FooBar", Actress.Tier.MINOR)).getId();
        Title t = titleRepo.save(mkTitle("FB-001", aid));
        saveLoc(t.getId(), "/stars/library/Foobar/Foobar (FB-001)", "library");
        createFolder("/stars/library/FooBar");   // canonical case → findAllDiskTiers matches on any FS

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("no title_locations rebase under it"), "reason: " + r.reason());
        // Nothing moved; the location row is untouched.
        assertFalse(fs.exists(Path.of("/stars/minor/FooBar")));
        List<TitleLocation> locs = locationRepo.findByVolume("a");
        assertEquals(1, locs.size());
        assertEquals(Path.of("/stars/library/Foobar/Foobar (FB-001)"), locs.get(0).getPath());
        assertEquals("library", locs.get(0).getPartitionId());
    }

    // ── rejected-actress guard ─────────────────────────────────────────────────

    @Test
    void rejectedActress_skipped_evenWithRealMismatch() throws Exception {
        // Genuine mismatch (folder at library, DB says MINOR) but actress is rejected → skip, no move.
        long aid = actressRepo.save(
                Actress.builder()
                        .canonicalName("Rejected Lady")
                        .tier(Actress.Tier.MINOR)
                        .rejected(true)
                        .firstSeenAt(LocalDate.of(2024, 1, 1))
                        .build()).getId();
        for (int i = 1; i <= 3; i++) {
            Title t = titleRepo.save(mkTitle("RJ-%03d".formatted(i), aid));
            saveLoc(t.getId(), "/stars/library/Rejected Lady/Rejected Lady (RJ-%03d)".formatted(i), "library");
            createFolder("/stars/library/Rejected Lady/Rejected Lady (RJ-%03d)".formatted(i));
        }

        ActressClassifierService.Result r = svc.reconcileTierFolders(fs, volumeA(), jdbi, aid, false);

        assertEquals(ActressClassifierService.Outcome.SKIPPED, r.outcome());
        assertTrue(r.reason().contains("rejected"), "reason: " + r.reason());
        assertTrue(fs.exists(Path.of("/stars/library/Rejected Lady")));
        assertFalse(fs.exists(Path.of("/stars/minor/Rejected Lady")));
    }

    // ── findAllDiskTiers ─────────────────────────────────────────────────────

    @Test
    void findAllDiskTiers_returnsAllMatches() throws Exception {
        createFolder("/stars/library/Multi Tier Actress");
        createFolder("/stars/popular/Multi Tier Actress");
        List<String> found = svc.findAllDiskTiers(fs, "Multi Tier Actress");
        assertEquals(List.of("library", "popular"), found);
    }

    @Test
    void findAllDiskTiers_returnsEmpty_whenNoFolder() {
        List<String> found = svc.findAllDiskTiers(fs, "Nonexistent Actress");
        assertTrue(found.isEmpty());
    }

    @Test
    void findAllDiskTiers_excludesPool() throws Exception {
        // pool is not a /stars tier — should never match
        createFolder("/stars/library/Pool Test");
        List<String> found = svc.findAllDiskTiers(fs, "Pool Test");
        assertEquals(List.of("library"), found);
        assertFalse(found.contains("pool"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static VolumeConfig volumeA() {
        return new VolumeConfig("a", "//h/a", "conventional", "h", null, List.of("A"));
    }

    private static Actress mkActress(String name, Actress.Tier tier) {
        return Actress.builder()
                .canonicalName(name)
                .tier(tier)
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
