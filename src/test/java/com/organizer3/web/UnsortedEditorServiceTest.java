package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiUnsortedEditorRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.smb.SmbConnectionFactory;
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

class UnsortedEditorServiceTest {

    private static final String VOL = "unsorted";

    @TempDir Path dataDir;

    private Connection connection;
    private Jdbi jdbi;
    private UnsortedEditorService service;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private CoverPath coverPath;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('" + VOL + "', 'queue')"));
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        videoRepo = new JdbiVideoRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        coverPath = new CoverPath(dataDir);
        // Rename path is always triggered when primary name + code differs from the folder.
        // Wire up a fake SMB handle whose filesystem accepts rename as a no-op so the focus
        // stays on the DB/service behavior. A separate test exercises rename path explicitly.
        SmbConnectionFactory smbFactory = org.mockito.Mockito.mock(SmbConnectionFactory.class);
        SmbConnectionFactory.SmbShareHandle handle = org.mockito.Mockito.mock(SmbConnectionFactory.SmbShareHandle.class);
        com.organizer3.filesystem.VolumeFileSystem fs =
                org.mockito.Mockito.mock(com.organizer3.filesystem.VolumeFileSystem.class);
        org.mockito.Mockito.when(smbFactory.open(VOL)).thenReturn(handle);
        org.mockito.Mockito.when(handle.fileSystem()).thenReturn(fs);
        org.mockito.Mockito.when(fs.exists(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        TitleFolderRenamer renamer = new TitleFolderRenamer(smbFactory, jdbi, VOL);
        service = new UnsortedEditorService(
                new JdbiUnsortedEditorRepository(jdbi),
                actressRepo,
                coverPath,
                smbFactory,
                VOL,
                "//host.local/unsorted",
                java.util.Map.of(VOL, "//host.local/unsorted", "tz", "//pandora.local/jav_TZ"),
                renamer);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void listEligibleMarksCompleteWhenActressAndCoverBothPresent() throws Exception {
        long a = seedTitle("ONED-001", "A (ONED-001)");
        seedTitle("ONED-002", "B (ONED-002)");
        Actress actress = saveActress("Aika");
        titleActressRepo.link(a, actress.getId());
        writeDummyCover("ONED", "ONED-001");
        // b has no actress, no cover — incomplete

        var rows = service.listEligible();

        var rowA = rows.stream().filter(r -> r.code().equals("ONED-001")).findFirst().orElseThrow();
        var rowB = rows.stream().filter(r -> r.code().equals("ONED-002")).findFirst().orElseThrow();
        assertTrue(rowA.complete());
        assertFalse(rowB.complete());
        assertEquals(1, rowA.actressCount());
        assertTrue(rowA.hasCover());
    }

    @Test
    void replaceActressesTransactionallyCreatesDraftsFlaggedNeedsProfiling() {
        long titleId = seedTitle("ONED-003", "C (ONED-003)");
        Actress existing = saveActress("Existing Actress");

        var entries = List.of(
                new UnsortedEditorService.ActressEntry(existing.getId(), null),
                new UnsortedEditorService.ActressEntry(null, "Brand New Draft"));
        var primary = new UnsortedEditorService.ActressEntry(null, "Brand New Draft");

        var result = service.replaceActresses(titleId, entries, primary);

        assertEquals(2, result.actressIds().size());
        long draftId = result.actressIds().stream().filter(id -> !id.equals(existing.getId())).findFirst().orElseThrow();
        assertNotEquals(existing.getId(), result.primaryActressId());
        assertEquals(draftId, result.primaryActressId());
        Actress draft = actressRepo.findById(draftId).orElseThrow();
        assertTrue(draft.isNeedsProfiling());
        assertEquals("Brand New Draft", draft.getCanonicalName());
    }

    @Test
    void replaceActressesRejectsEmptyList() {
        long titleId = seedTitle("ONED-004", "D (ONED-004)");
        assertThrows(IllegalArgumentException.class, () ->
                service.replaceActresses(titleId, List.of(),
                        new UnsortedEditorService.ActressEntry(null, "X")));
    }

    @Test
    void replaceActressesRejectsPrimaryNotInList() {
        long titleId = seedTitle("ONED-005", "E (ONED-005)");
        Actress a = saveActress("A");
        Actress b = saveActress("B");
        var entries = List.of(new UnsortedEditorService.ActressEntry(a.getId(), null));
        var primary = new UnsortedEditorService.ActressEntry(b.getId(), null);

        assertThrows(IllegalArgumentException.class, () ->
                service.replaceActresses(titleId, entries, primary));
    }

    @Test
    void replaceActressesRejectsMixedIdAndName() {
        long titleId = seedTitle("ONED-006", "F (ONED-006)");
        Actress a = saveActress("A");
        var entries = List.of(new UnsortedEditorService.ActressEntry(a.getId(), "oops"));
        var primary = new UnsortedEditorService.ActressEntry(a.getId(), null);

        assertThrows(IllegalArgumentException.class, () ->
                service.replaceActresses(titleId, entries, primary));
    }

    @Test
    void replaceActressesReusesExistingCanonicalNameForDraft() {
        long titleId = seedTitle("ONED-007", "G (ONED-007)");
        Actress existing = saveActress("Aika Suzuki");

        var entries = List.of(new UnsortedEditorService.ActressEntry(null, "aika suzuki"));
        var primary = entries.get(0);

        var result = service.replaceActresses(titleId, entries, primary);

        assertEquals(existing.getId(), result.primaryActressId());
        // And no duplicate actress row should be created.
        long count = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM actresses WHERE canonical_name = 'Aika Suzuki' COLLATE NOCASE")
                .mapTo(Long.class).one());
        assertEquals(1, count);
    }

    @Test
    void validateDescriptorAcceptsAllowedCharacters() {
        assertEquals("",                       UnsortedEditorService.validateDescriptor(null));
        assertEquals("",                       UnsortedEditorService.validateDescriptor(""));
        assertEquals("",                       UnsortedEditorService.validateDescriptor("   "));
        assertEquals("Demosaiced",             UnsortedEditorService.validateDescriptor("Demosaiced"));
        assertEquals("4K Extended Cut",        UnsortedEditorService.validateDescriptor("  4K Extended Cut  "));
        assertEquals("v2 @ home #1, #2; +alt", UnsortedEditorService.validateDescriptor("v2 @ home #1, #2; +alt"));
        assertEquals("a_b=c",                  UnsortedEditorService.validateDescriptor("a_b=c"));
    }

    @Test
    void validateDescriptorRejectsHyphenAndReservedCharacters() {
        for (String bad : new String[]{
                "has-dash",                  // hyphen (delimiter)
                "slash/no",                  // /
                "back\\slash",               // \
                "co:lon",                    // :
                "aster*isk",                 // *
                "quest?ion",                 // ?
                "quo\"te",                   // "
                "lt<gt>",                    // < >
                "pi|pe",                     // |
                "with.dot",                  // . not in allowlist
                "paren(s)",                  // parens not in allowlist
                "naïve",                     // non-ASCII
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> UnsortedEditorService.validateDescriptor(bad),
                    "expected rejection for: " + bad);
        }
    }

    @Test
    void extractDescriptorPullsSuffixBetweenDashAndCode() {
        assertEquals("Demosaiced",
                UnsortedEditorService.extractDescriptor("Nao Wakana - Demosaiced (ABP-527)", "ABP-527"));
        assertEquals("4K Extended Cut",
                UnsortedEditorService.extractDescriptor("Yua Aida - 4K Extended Cut (ONED-125)", "ONED-125"));
        assertEquals("",
                UnsortedEditorService.extractDescriptor("Nami Nanami (RKI-738)", "RKI-738"));
        assertEquals("",
                UnsortedEditorService.extractDescriptor("(RKI-738)", "RKI-738"));
        assertEquals("",
                UnsortedEditorService.extractDescriptor("Something completely different", "CODE-1"));
    }

    @Test
    void sanitizeFolderNameStripsForbiddenCharacters() {
        assertEquals("Name (CODE-1)",       UnsortedEditorService.sanitizeFolderName("Name (CODE-1)"));
        assertEquals("A B (X-1)",           UnsortedEditorService.sanitizeFolderName("A/B (X-1)"));
        assertEquals("Foo Bar (Y-2)",       UnsortedEditorService.sanitizeFolderName("Foo:Bar (Y-2)"));
        assertEquals("Who (Z-3)",           UnsortedEditorService.sanitizeFolderName("Who?  (Z-3)"));
    }

    @Test
    void searchActressesReturnsAnnotatedAliasMatches() {
        Actress a = saveActress("Ai Uehara");
        actressRepo.saveAlias(new com.organizer3.model.ActressAlias(a.getId(), "Rio"));

        var results = service.searchActresses("Rio", 10);

        assertFalse(results.isEmpty());
        var hit = results.stream().filter(r -> r.id() == a.getId()).findFirst().orElseThrow();
        assertEquals("Rio", hit.matchedAlias());
        assertEquals("Ai Uehara", hit.canonicalName());
    }

    // ── Phase 3: processed / curated_at tests ────────────────────────────

    @Test
    void replaceActresses_stampsCuratedAtOnStagingLocation() {
        long titleId = seedTitle("ONED-020", "NoDraft (ONED-020)");
        Actress actress = saveActress("Actress NoDraft");

        var entries = List.of(new UnsortedEditorService.ActressEntry(actress.getId(), null));
        var primary = entries.get(0);
        service.replaceActresses(titleId, entries, primary);

        String curatedAt = jdbi.withHandle(h ->
                h.createQuery("SELECT curated_at FROM title_locations"
                        + " WHERE title_id = :id AND volume_id = :vol AND stale_since IS NULL")
                        .bind("id", titleId).bind("vol", VOL)
                        .mapTo(String.class).one());
        assertNotNull(curatedAt, "curated_at must be set after no-draft replaceActresses");
    }

    @Test
    void listEligible_processedTrueAfterNoDraftSave() {
        long titleId = seedTitle("ONED-021", "NoDraftP (ONED-021)");
        Actress actress = saveActress("Actress NoDraftP");

        var entries = List.of(new UnsortedEditorService.ActressEntry(actress.getId(), null));
        service.replaceActresses(titleId, entries, entries.get(0));

        var row = service.listEligible().stream()
                .filter(r -> r.titleId() == titleId).findFirst().orElseThrow();
        assertTrue(row.processed(), "processed must be true after no-draft replaceActresses");
    }

    @Test
    void findEligibleById_processedTrueAfterNoDraftSave() {
        long titleId = seedTitle("ONED-022", "NoDraftD (ONED-022)");
        Actress actress = saveActress("Actress NoDraftD");

        var entries = List.of(new UnsortedEditorService.ActressEntry(actress.getId(), null));
        service.replaceActresses(titleId, entries, entries.get(0));

        var view = service.findEligibleById(titleId).orElseThrow();
        assertTrue(view.processed(), "processed must be true after no-draft replaceActresses");
    }

    @Test
    void listEligible_processedAndCompleteAreDistinctFields() {
        // A title can be complete (hasCover + actressCount>0) without being processed.
        // And can be processed without being complete.
        long titleIdComplete = seedTitle("ONED-023", "Complete (ONED-023)");
        long titleIdProcessed = seedTitle("ONED-024", "Processed (ONED-024)");
        Actress actress = saveActress("Distinct Fields Actress");

        // Make complete: actress + cover
        titleActressRepo.link(titleIdComplete, actress.getId());
        try { writeDummyCover("ONED", "ONED-023"); } catch (Exception e) { throw new RuntimeException(e); }

        // Make processed: actress save (no cover)
        var entries = List.of(new UnsortedEditorService.ActressEntry(actress.getId(), null));
        service.replaceActresses(titleIdProcessed, entries, entries.get(0));

        var rows = service.listEligible();
        var completeRow  = rows.stream().filter(r -> r.titleId() == titleIdComplete).findFirst().orElseThrow();
        var processedRow = rows.stream().filter(r -> r.titleId() == titleIdProcessed).findFirst().orElseThrow();

        assertTrue(completeRow.complete(), "titleIdComplete should be complete");
        assertFalse(completeRow.processed(), "titleIdComplete should not be processed (no save called)");

        assertTrue(processedRow.processed(), "titleIdProcessed should be processed");
        assertFalse(processedRow.complete(), "titleIdProcessed should not be complete (no cover)");
    }

    // ── clearCoverPending (spec/PROPOSAL_COVER_CONFIRMATION.md Part 6) ────────

    /** A jdbi-wired service — the default {@code service} uses the null-jdbi ctor. */
    private UnsortedEditorService jdbiWiredService() {
        return new UnsortedEditorService(
                new JdbiUnsortedEditorRepository(jdbi), actressRepo, coverPath,
                null, VOL, "//host.local/unsorted", java.util.Map.of(), null, jdbi);
    }

    private void setPending(long titleId, String path, String since) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET cover_pending_since = :s"
                + " WHERE title_id = :t AND volume_id = :v AND path = :p")
                .bind("s", since).bind("t", titleId).bind("v", VOL).bind("p", path)
                .execute());
    }

    private String pendingOf(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT cover_pending_since FROM title_locations WHERE title_id = :t")
                        .bind("t", titleId).mapTo(String.class).findOne().orElse(null));
    }

    @Test
    void clearCoverPending_matchingTriple_clearsFlag() {
        long titleId = seedTitle("ONED-030", "Pending (ONED-030)");
        setPending(titleId, "/root/Pending (ONED-030)", "2026-07-10T00:00:00Z");
        assertNotNull(pendingOf(titleId), "precondition: flag is set");

        jdbiWiredService().clearCoverPending(titleId, VOL, "/root/Pending (ONED-030)");

        assertNull(pendingOf(titleId), "matching (titleId, volumeId, path) must clear the flag");
    }

    @Test
    void clearCoverPending_nonMatchingPath_leavesFlagSet() {
        long titleId = seedTitle("ONED-031", "Pending (ONED-031)");
        setPending(titleId, "/root/Pending (ONED-031)", "2026-07-10T00:00:00Z");

        // Wrong path → scoped no-op.
        jdbiWiredService().clearCoverPending(titleId, VOL, "/root/wrong-path");

        assertNotNull(pendingOf(titleId), "non-matching path must leave the flag untouched");
    }

    @Test
    void clearCoverPending_nullJdbi_isNoOp() {
        long titleId = seedTitle("ONED-032", "Pending (ONED-032)");
        setPending(titleId, "/root/Pending (ONED-032)", "2026-07-10T00:00:00Z");

        // The default `service` was built with the null-jdbi ctor → clearCoverPending is a no-op.
        assertDoesNotThrow(() ->
                service.clearCoverPending(titleId, VOL, "/root/Pending (ONED-032)"));
        assertNotNull(pendingOf(titleId), "null-jdbi service must not touch the flag");
    }

    // ── folderNasPath tests ──────────────────────────────────────────────

    @Test
    void findEligibleById_folderNasPathIsFullConcatenation() {
        long titleId = seedTitle("ONED-030", "Foo (ONED-030)");
        UnsortedEditorService.TitleDetailView view = service.findEligibleById(titleId).orElseThrow();
        // seedTitle stores path as /root/Foo (ONED-030); smbBase = //host.local/unsorted
        assertEquals("//host.local/unsorted/root/Foo (ONED-030)", view.folderNasPath(),
                "folderNasPath must be exact concatenation of smbBase + folderPath with no extra separator");
    }

    @Test
    void findEligibleById_folderNasPathIsNullWhenBaseIsNull() {
        // Construct a service with null SMB base (simulates volume not in config)
        TitleFolderRenamer renamer2 = new TitleFolderRenamer(
                org.mockito.Mockito.mock(SmbConnectionFactory.class), jdbi, VOL);
        // volumeSmbPaths deliberately OMITS VOL, so the per-title base lookup
        // (volumeSmbPaths.get(volumeId)) returns null — the volume-not-in-config case.
        UnsortedEditorService nullBaseService = new UnsortedEditorService(
                new JdbiUnsortedEditorRepository(jdbi),
                actressRepo,
                coverPath,
                org.mockito.Mockito.mock(SmbConnectionFactory.class),
                VOL,
                null,
                java.util.Map.of("tz", "//pandora.local/jav_TZ"),
                renamer2);
        long titleId = seedTitle("ONED-031", "Bar (ONED-031)");
        UnsortedEditorService.TitleDetailView view = nullBaseService.findEligibleById(titleId).orElseThrow();
        assertNull(view.folderNasPath(),
                "folderNasPath must be null when smbBase is null (null guard prevents literal \"null/...\" string)");
    }

    // ── otherLocations nasPath tests ─────────────────────────────────────

    @Test
    void findEligibleById_otherLocationNasPath_resolvesViaVolumeSmbMap() {
        // Title with a duplicate location on volume "tz" (present in the smb-path map).
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('tz', 'library')"));
        long titleId = seedTitle("IPX-633", "Dup (IPX-633)");
        String otherPath = "/stars/goddess/Tsumugi Akari/Tsumugi Akari - Demosaiced (IPX-633)";
        locationRepo.save(TitleLocation.builder().titleId(titleId).volumeId("tz")
                .partitionId("stars").path(Path.of(otherPath))
                .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now()).build());

        var view = service.findEligibleById(titleId).orElseThrow();
        assertTrue(view.duplicate(), "title with another live location must be flagged duplicate");
        var loc = view.otherLocations().stream()
                .filter(l -> "tz".equals(l.volumeId())).findFirst().orElseThrow();
        assertEquals(otherPath, loc.path());
        assertEquals("//pandora.local/jav_TZ" + otherPath, loc.nasPath(),
                "nasPath must be smbBase + share-relative path with no extra separator");
    }

    @Test
    void findEligibleById_otherLocationNasPath_nullWhenVolumeNotInMap() {
        // Duplicate location on a volume absent from the smb-path map → nasPath null.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('mystery', 'library')"));
        long titleId = seedTitle("IPX-634", "Dup2 (IPX-634)");
        String otherPath = "/stars/x/Foo (IPX-634)";
        locationRepo.save(TitleLocation.builder().titleId(titleId).volumeId("mystery")
                .partitionId("stars").path(Path.of(otherPath))
                .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now()).build());

        var view = service.findEligibleById(titleId).orElseThrow();
        var loc = view.otherLocations().stream()
                .filter(l -> "mystery".equals(l.volumeId())).findFirst().orElseThrow();
        assertNull(loc.nasPath(), "nasPath must be null when the volume is not in the smb-path map");
    }

    // ── Test D: sentinel mutual-exclusivity guard ─────────────────────────

    /** Inserts a sentinel actress row directly and returns its id. */
    private long insertSentinel(String name) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses(canonical_name, tier, first_seen_at, is_sentinel) " +
                "VALUES (:name, 'LIBRARY', '2024-01-01', 1)")
                .bind("name", name)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    @Test
    void replaceActresses_singleSentinel_isAccepted() {
        long titleId = seedTitle("SENT-001", "Sentinel (SENT-001)");
        long sentinelId = insertSentinel("Amateur");

        var entries = List.of(new UnsortedEditorService.ActressEntry(sentinelId, null));
        var primary = entries.get(0);

        // Single sentinel must be accepted.
        assertDoesNotThrow(() -> service.replaceActresses(titleId, entries, primary));
    }

    @Test
    void replaceActresses_sentinelPlusRealActress_isRejected() {
        long titleId = seedTitle("SENT-002", "SentinelMix (SENT-002)");
        long sentinelId = insertSentinel("Various");
        Actress real = saveActress("Real Actress");

        var entries = List.of(
                new UnsortedEditorService.ActressEntry(sentinelId, null),
                new UnsortedEditorService.ActressEntry(real.getId(), null));
        var primary = entries.get(0);

        assertThrows(IllegalArgumentException.class,
                () -> service.replaceActresses(titleId, entries, primary),
                "sentinel + real actress should be rejected");
    }

    @Test
    void replaceActresses_twoSentinels_isRejected() {
        long titleId = seedTitle("SENT-003", "TwoSentinels (SENT-003)");
        long s1 = insertSentinel("Amateur");
        long s2 = insertSentinel("Various");

        var entries = List.of(
                new UnsortedEditorService.ActressEntry(s1, null),
                new UnsortedEditorService.ActressEntry(s2, null));
        var primary = entries.get(0);

        assertThrows(IllegalArgumentException.class,
                () -> service.replaceActresses(titleId, entries, primary),
                "two sentinels should be rejected");
    }

    @Test
    void replaceActresses_emptyList_isRejectedRegression() {
        long titleId = seedTitle("SENT-004", "EmptyReg (SENT-004)");
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceActresses(titleId, List.of(),
                        new UnsortedEditorService.ActressEntry(null, "X")));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    // ── PostCommitSmbExecutor dispatch tests ─────────────────────────────

    @Test
    void replaceActresses_withExecutor_dispatchesRenameInsteadOfRunningInline() {
        long titleId = seedTitle("ONED-040", "Old Name (ONED-040)");
        Actress actress = saveActress("New Primary");

        PostCommitSmbExecutor mockExecutor = org.mockito.Mockito.mock(PostCommitSmbExecutor.class);
        TitleFolderRenamer mockRenamer = org.mockito.Mockito.mock(TitleFolderRenamer.class);
        UnsortedEditorService asyncService = new UnsortedEditorService(
                new JdbiUnsortedEditorRepository(jdbi),
                actressRepo,
                coverPath,
                org.mockito.Mockito.mock(SmbConnectionFactory.class),
                java.util.Set.of(VOL),
                java.util.Map.of(VOL, "//host.local/unsorted"),
                mockRenamer,
                jdbi,
                mockExecutor);

        var entries = List.of(new UnsortedEditorService.ActressEntry(actress.getId(), null));
        var primary = entries.get(0);

        var result = asyncService.replaceActresses(titleId, entries, primary);

        org.mockito.Mockito.verify(mockExecutor, org.mockito.Mockito.times(1))
                .submit(org.mockito.ArgumentMatchers.eq("unsorted:ONED-040"),
                        org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(mockRenamer);
        assertFalse(result.folderRenamed(), "async dispatch path must report renamed=false");
        assertEquals("/root/Old Name (ONED-040)", result.folderPath(),
                "async dispatch path must report the pre-rename folder path");
    }

    @Test
    void replaceActresses_withRealSynchronousExecutor_runsRenameAndReturnsOutcome() throws Exception {
        long titleId = seedTitle("ONED-041", "Old Name2 (ONED-041)");
        Actress actress = saveActress("New Primary2");

        SmbConnectionFactory smbFactory = org.mockito.Mockito.mock(SmbConnectionFactory.class);
        SmbConnectionFactory.SmbShareHandle handle = org.mockito.Mockito.mock(SmbConnectionFactory.SmbShareHandle.class);
        com.organizer3.filesystem.VolumeFileSystem fs =
                org.mockito.Mockito.mock(com.organizer3.filesystem.VolumeFileSystem.class);
        org.mockito.Mockito.when(smbFactory.open(VOL)).thenReturn(handle);
        org.mockito.Mockito.when(handle.fileSystem()).thenReturn(fs);
        org.mockito.Mockito.when(fs.exists(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        TitleFolderRenamer renamer2 = new TitleFolderRenamer(smbFactory, jdbi, VOL);

        // A "synchronous" PostCommitSmbExecutor (Runnable::run) proves the dispatched task still
        // performs the rename and the reconciler-facing behavior is unaffected — only the return
        // value's pre-rename semantics differ from the inline (null-executor) path.
        PostCommitSmbExecutor syncExecutor = new PostCommitSmbExecutor(Runnable::run);
        UnsortedEditorService asyncService = new UnsortedEditorService(
                new JdbiUnsortedEditorRepository(jdbi),
                actressRepo,
                coverPath,
                smbFactory,
                java.util.Set.of(VOL),
                java.util.Map.of(VOL, "//host.local/unsorted"),
                renamer2,
                jdbi,
                syncExecutor);

        var entries = List.of(new UnsortedEditorService.ActressEntry(actress.getId(), null));
        var primary = entries.get(0);

        var result = asyncService.replaceActresses(titleId, entries, primary);

        // Even though the executor ran the rename synchronously, the service's returned
        // SaveResult still reflects the pre-rename (renamed=false) contract for the dispatch
        // branch — it does not wait on/inspect the task's outcome.
        assertFalse(result.folderRenamed());
        assertEquals("/root/Old Name2 (ONED-041)", result.folderPath());
        // But the folder rename did actually happen on disk/DB (proving the Runnable ran).
        String newPath = jdbi.withHandle(h -> h.createQuery(
                        "SELECT path FROM title_locations WHERE title_id = :id AND stale_since IS NULL")
                        .bind("id", titleId).mapTo(String.class).one());
        assertNotEquals("/root/Old Name2 (ONED-041)", newPath,
                "the dispatched rename task should have actually renamed the folder in the DB");
    }

    private long seedTitle(String code, String folderName) {
        Title t = titleRepo.save(Title.builder().code(code).baseCode(code).label(code.split("-")[0]).build());
        String folderPath = "/root/" + folderName;
        locationRepo.save(TitleLocation.builder().titleId(t.getId()).volumeId(VOL)
                .partitionId("queue").path(Path.of(folderPath))
                .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now()).build());
        videoRepo.save(Video.builder().titleId(t.getId()).volumeId(VOL)
                .filename("a.mp4").path(Path.of(folderPath + "/video/a.mp4"))
                .lastSeenAt(LocalDate.now()).build());
        return t.getId();
    }

    private Actress saveActress(String name) {
        return actressRepo.save(Actress.builder().canonicalName(name)
                .tier(Actress.Tier.LIBRARY).firstSeenAt(LocalDate.now()).build());
    }

    private void writeDummyCover(String label, String baseCode) throws Exception {
        Path labelDir = dataDir.resolve("covers").resolve(label);
        Files.createDirectories(labelDir);
        Files.writeString(labelDir.resolve(baseCode + ".jpg"), "fake");
    }
}
