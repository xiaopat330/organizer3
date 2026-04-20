package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.UnsortedEditorRepository.AssignedActress;
import com.organizer3.repository.UnsortedEditorRepository.EligibleTitle;
import com.organizer3.repository.UnsortedEditorRepository.TitleDetail;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbiUnsortedEditorRepositoryTest {

    private JdbiUnsortedEditorRepository repo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private Connection connection;
    private Jdbi jdbi;

    private static final String VOL = "unsorted";

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('" + VOL + "', 'queue')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo = new JdbiActressRepository(jdbi);
        videoRepo = new JdbiVideoRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        repo = new JdbiUnsortedEditorRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void listEligibleIncludesTitlesWithCodeInParensAndVideoInChildSubfolder() {
        long id = seedEligibleTitle("ONED-123", "Some Title (ONED-123)", "video/part1.mp4", LocalDate.now());

        List<EligibleTitle> result = repo.listEligible(VOL);

        assertEquals(1, result.size());
        EligibleTitle e = result.get(0);
        assertEquals(id, e.titleId());
        assertEquals("ONED-123", e.code());
        assertEquals("Some Title (ONED-123)", e.folderName());
    }

    @Test
    void listEligibleAcceptsH265AndFourKSubfolders() {
        seedEligibleTitle("ONED-001", "Alpha (ONED-001)", "h265/a.mkv", LocalDate.now());
        seedEligibleTitle("ONED-002", "Beta (ONED-002)",  "4K/b.mkv",    LocalDate.now());

        assertEquals(2, repo.listEligible(VOL).size());
    }

    @Test
    void listEligibleExcludesTitlesWhereVideosSitAtFolderBase() {
        seedTitle("ONED-111", "Bare (ONED-111)", "bare.mp4", LocalDate.now());

        assertTrue(repo.listEligible(VOL).isEmpty());
    }

    @Test
    void listEligibleExcludesTitlesWhoseFolderDoesNotContainCodeInParens() {
        seedTitle("ONED-222", "Some Title ONED-222 no parens", "video/a.mp4", LocalDate.now());

        assertTrue(repo.listEligible(VOL).isEmpty());
    }

    @Test
    void listEligibleExcludesTitlesOnOtherVolumes() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('other', 'conventional')"));
        seedTitleOnVolume("ONED-333", "Right (ONED-333)", "video/x.mp4", "other", LocalDate.now());

        assertTrue(repo.listEligible(VOL).isEmpty());
    }

    @Test
    void listEligibleOrdersFifoByAddedDate() {
        seedEligibleTitle("ONED-100", "One (ONED-100)", "video/a.mp4", LocalDate.of(2024, 1, 3));
        seedEligibleTitle("ONED-200", "Two (ONED-200)", "video/a.mp4", LocalDate.of(2024, 1, 1));
        seedEligibleTitle("ONED-300", "Three (ONED-300)", "video/a.mp4", LocalDate.of(2024, 1, 2));

        List<EligibleTitle> result = repo.listEligible(VOL);
        assertEquals(List.of("ONED-200", "ONED-300", "ONED-100"),
                result.stream().map(EligibleTitle::code).toList());
    }

    @Test
    void findEligibleByIdLoadsActressesWithPrimaryMarked() {
        long titleId = seedEligibleTitle("ONED-400", "Four (ONED-400)", "video/a.mp4", LocalDate.now());
        Actress aika = saveActress("Aika");
        Actress yui = saveActress("Yui Hatano");
        titleActressRepo.linkAll(titleId, List.of(aika.getId(), yui.getId()));
        jdbi.useHandle(h -> h.createUpdate("UPDATE titles SET actress_id = :a WHERE id = :t")
                .bind("a", yui.getId()).bind("t", titleId).execute());

        Optional<TitleDetail> detail = repo.findEligibleById(titleId, VOL);

        assertTrue(detail.isPresent());
        List<AssignedActress> actresses = detail.get().actresses();
        assertEquals(2, actresses.size());
        AssignedActress yuiRow = actresses.stream().filter(a -> a.actressId() == yui.getId())
                .findFirst().orElseThrow();
        AssignedActress aikaRow = actresses.stream().filter(a -> a.actressId() == aika.getId())
                .findFirst().orElseThrow();
        assertTrue(yuiRow.primary());
        assertFalse(aikaRow.primary());
    }

    @Test
    void replaceActressesUpdatesJunctionAndPrimary() {
        long titleId = seedEligibleTitle("ONED-500", "Five (ONED-500)", "video/a.mp4", LocalDate.now());
        Actress a = saveActress("Alpha");
        Actress b = saveActress("Beta");
        titleActressRepo.linkAll(titleId, List.of(a.getId()));

        repo.replaceActresses(titleId, List.of(a.getId(), b.getId()), b.getId());

        assertEquals(2, titleActressRepo.findActressIdsByTitle(titleId).size());
        Long primary = jdbi.withHandle(h -> h.createQuery("SELECT actress_id FROM titles WHERE id = :t")
                .bind("t", titleId).mapTo(Long.class).findFirst().orElse(null));
        assertEquals(b.getId(), primary);
    }

    @Test
    void createDraftActressFlagsNeedsProfiling() {
        long id = jdbi.inTransaction(h -> repo.createDraftActress(h, "Brand New"));

        Actress a = actressRepo.findById(id).orElseThrow();
        assertTrue(a.isNeedsProfiling());
        assertEquals(Actress.Tier.LIBRARY, a.getTier());
        assertEquals("Brand New", a.getCanonicalName());
    }

    @Test
    void hasLocationInVolumeReflectsState() {
        long titleId = seedEligibleTitle("ONED-600", "Six (ONED-600)", "video/a.mp4", LocalDate.now());
        assertTrue(repo.hasLocationInVolume(titleId, VOL));
        assertFalse(repo.hasLocationInVolume(titleId, "somewhere-else"));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private long seedEligibleTitle(String code, String folderName, String videoRelPath, LocalDate addedDate) {
        return seedTitle(code, folderName, videoRelPath, addedDate);
    }

    private long seedTitle(String code, String folderName, String videoRelPath, LocalDate addedDate) {
        return seedTitleOnVolume(code, folderName, videoRelPath, VOL, addedDate);
    }

    private long seedTitleOnVolume(String code, String folderName, String videoRelPath,
                                    String volumeId, LocalDate addedDate) {
        Title title = titleRepo.save(Title.builder().code(code).baseCode(code).label(code.split("-")[0]).build());
        String folderPath = "/root/" + folderName;
        locationRepo.save(TitleLocation.builder()
                .titleId(title.getId())
                .volumeId(volumeId)
                .partitionId("queue")
                .path(Path.of(folderPath))
                .lastSeenAt(addedDate)
                .addedDate(addedDate)
                .build());
        videoRepo.save(Video.builder()
                .titleId(title.getId())
                .volumeId(volumeId)
                .filename(videoRelPath.substring(videoRelPath.lastIndexOf('/') + 1))
                .path(Path.of(folderPath + "/" + videoRelPath))
                .lastSeenAt(addedDate)
                .build());
        return title.getId();
    }

    private Actress saveActress(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build());
    }
}
