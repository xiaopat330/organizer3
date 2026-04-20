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
        service = new UnsortedEditorService(
                new JdbiUnsortedEditorRepository(jdbi),
                actressRepo,
                coverPath,
                smbFactory,
                VOL);
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

    // ── helpers ──────────────────────────────────────────────────────────

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
