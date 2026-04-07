package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
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

/**
 * Integration tests for JdbiTitleRepository using an in-memory SQLite database.
 * Each test gets a fresh schema — no shared state between tests.
 */
class JdbiTitleRepositoryTest {

    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiActressRepository actressRepo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        // Insert a volume row to satisfy the FK constraint on title_locations
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo = new JdbiActressRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- findByActress ---

    @Test
    void findByActressReturnsOnlyTitlesForThatActress() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");
        saveWithLocation(title("SSIS-001", hibiki.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SSIS-001");

        List<Title> results = titleRepo.findByActress(aya.getId());
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(t -> t.getActressId().equals(aya.getId())));
    }

    @Test
    void findByActressReturnsEmptyWhenNoTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        assertTrue(titleRepo.findByActress(aya.getId()).isEmpty());
    }

    // --- countByActress ---

    @Test
    void countByActressReturnsCorrectCount() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");
        saveWithLocation(title("SSIS-001", hibiki.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SSIS-001");

        assertEquals(2, titleRepo.countByActress(aya.getId()));
        assertEquals(1, titleRepo.countByActress(hibiki.getId()));
    }

    @Test
    void countByActressReturnsZeroWhenNoTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        assertEquals(0, titleRepo.countByActress(aya.getId()));
    }

    // --- findByActressIncludingAliases ---

    @Test
    void findByActressIncludingAliasesReturnsTitlesUnderCanonicalName() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");

        List<Title> results = titleRepo.findByActressIncludingAliases(aya.getId());
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    @Test
    void findByActressIncludingAliasesIncludesTitlesOnOrphanAliasRecord() {
        // Aya Sazanami is the canonical actress
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        // "Haruka Suzumiya" is a separate actress record created before the alias was configured
        Actress haruka = actressRepo.save(actress("Haruka Suzumiya"));
        // Now register "Haruka Suzumiya" as an alias of Aya
        actressRepo.saveAlias(new ActressAlias(aya.getId(), "Haruka Suzumiya"));

        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("ABP-002", haruka.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");

        List<Title> results = titleRepo.findByActressIncludingAliases(aya.getId());
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-001")));
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-002")));
    }

    @Test
    void findByActressIncludingAliasesDoesNotReturnUnrelatedTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("SSIS-001", hibiki.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SSIS-001");

        List<Title> results = titleRepo.findByActressIncludingAliases(aya.getId());
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    // --- findByAliasesOnly ---

    @Test
    void findByAliasesOnlyReturnsOnlyStrandedTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress haruka = actressRepo.save(actress("Haruka Suzumiya"));
        actressRepo.saveAlias(new ActressAlias(aya.getId(), "Haruka Suzumiya"));

        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("ABP-002", haruka.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");

        List<Title> results = titleRepo.findByAliasesOnly(aya.getId());
        assertEquals(1, results.size());
        assertEquals("ABP-002", results.get(0).getCode());
    }

    @Test
    void findByAliasesOnlyReturnsEmptyWhenNoOrphanRecords() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        actressRepo.saveAlias(new ActressAlias(aya.getId(), "Haruka Suzumiya"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");

        // No separate actress record for "Haruka Suzumiya", so no stranded titles
        assertTrue(titleRepo.findByAliasesOnly(aya.getId()).isEmpty());
    }

    @Test
    void findByAliasesOnlyReturnsEmptyWhenNoAliasesDefined() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        assertTrue(titleRepo.findByAliasesOnly(aya.getId()).isEmpty());
    }

    // --- findRecent ---

    @Test
    void findRecentReturnsTitlesNewestFirst() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 3, 1));
        saveWithLocation(title("ABP-003", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 2, 1));

        List<Title> results = titleRepo.findRecent(10, 0);
        assertEquals(3, results.size());
        assertEquals("ABP-002", results.get(0).getCode()); // newest first
        assertEquals("ABP-003", results.get(1).getCode());
        assertEquals("ABP-001", results.get(2).getCode());
    }

    @Test
    void findRecentRespectsLimitAndOffset() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 2, 1));
        saveWithLocation(title("ABP-003", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 3, 1));

        List<Title> page1 = titleRepo.findRecent(2, 0);
        List<Title> page2 = titleRepo.findRecent(2, 2);

        assertEquals(2, page1.size());
        assertEquals("ABP-003", page1.get(0).getCode());
        assertEquals("ABP-002", page1.get(1).getCode());

        assertEquals(1, page2.size());
        assertEquals("ABP-001", page2.get(0).getCode());
    }

    @Test
    void findRecentPlacesNullDatesLast() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-DATED", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-DATED", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-NODATE", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-NODATE", null);

        List<Title> results = titleRepo.findRecent(10, 0);
        assertEquals(2, results.size());
        assertEquals("ABP-DATED", results.get(0).getCode()); // dated titles sort before null
        assertEquals("ABP-NODATE", results.get(1).getCode());
    }

    @Test
    void findRecentExcludesUnorganizedTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-ORGANIZED", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-ORGANIZED", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-QUEUE", null), "vol-a", "queue", "/mnt/vol-a/queue/ABP-QUEUE", LocalDate.of(2025, 1, 1));

        List<Title> results = titleRepo.findRecent(10, 0);
        assertEquals(1, results.size());
        assertEquals("ABP-ORGANIZED", results.get(0).getCode());
    }

    @Test
    void findRecentReturnsEmptyWhenNoTitles() {
        assertTrue(titleRepo.findRecent(10, 0).isEmpty());
    }

    // --- findByActressPaged ---

    @Test
    void findByActressPagedReturnsNewestFirst() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 3, 1));
        saveWithLocation(title("ABP-003", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 2, 1));

        List<Title> results = titleRepo.findByActressPaged(aya.getId(), 10, 0);
        assertEquals(3, results.size());
        assertEquals("ABP-002", results.get(0).getCode());
        assertEquals("ABP-003", results.get(1).getCode());
        assertEquals("ABP-001", results.get(2).getCode());
    }

    @Test
    void findByActressPagedRespectsLimitAndOffset() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 2, 1));
        saveWithLocation(title("ABP-003", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 3, 1));

        List<Title> page1 = titleRepo.findByActressPaged(aya.getId(), 2, 0);
        List<Title> page2 = titleRepo.findByActressPaged(aya.getId(), 2, 2);

        assertEquals(2, page1.size());
        assertEquals("ABP-003", page1.get(0).getCode());
        assertEquals("ABP-002", page1.get(1).getCode());

        assertEquals(1, page2.size());
        assertEquals("ABP-001", page2.get(0).getCode());
    }

    @Test
    void findByActressPagedExcludesOtherActresses() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("SSIS-001", hibiki.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SSIS-001", LocalDate.of(2024, 2, 1));

        List<Title> results = titleRepo.findByActressPaged(aya.getId(), 10, 0);
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    // --- findDominantActressForLabel ---

    @Test
    void findDominantActressForLabelReturnsMostFrequentActress() {
        Actress aya    = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));

        saveWithLocation(titleWithLabel("ABP-001", "ABP", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(titleWithLabel("ABP-002", "ABP", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");
        saveWithLocation(titleWithLabel("ABP-003", "ABP", hibiki.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003");

        Optional<Long> result = titleRepo.findDominantActressForLabel("ABP");
        assertTrue(result.isPresent());
        assertEquals(aya.getId(), result.get());
    }

    @Test
    void findDominantActressForLabelReturnsEmptyWhenNoAttributedTitles() {
        assertTrue(titleRepo.findDominantActressForLabel("XYZ").isEmpty());
    }

    @Test
    void findDominantActressForLabelIgnoresNullActressId() {
        saveWithLocation(titleInPartition("ABP-001", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-001");
        assertTrue(titleRepo.findDominantActressForLabel("ABP").isEmpty());
    }

    // --- findByVolumeAndPartition ---

    @Test
    void findByVolumeAndPartitionReturnsOnlyMatchingTitles() {
        saveWithLocation(titleInPartition("ABP-001", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-001");
        saveWithLocation(titleInPartition("ABP-002", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-002");
        saveWithLocation(titleInPartition("ABP-003", "stars/library"), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003");

        List<Title> results = titleRepo.findByVolumeAndPartition("vol-a", "queue", 10, 0);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(t -> "queue".equals(t.getPartitionId())));
    }

    @Test
    void findByVolumeAndPartitionRespectsOffsetAndLimit() {
        saveWithLocation(titleInPartition("ABP-001", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-001", LocalDate.of(2024, 3, 1));
        saveWithLocation(titleInPartition("ABP-002", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-002", LocalDate.of(2024, 2, 1));
        saveWithLocation(titleInPartition("ABP-003", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-003", LocalDate.of(2024, 1, 1));

        List<Title> page1 = titleRepo.findByVolumeAndPartition("vol-a", "queue", 2, 0);
        List<Title> page2 = titleRepo.findByVolumeAndPartition("vol-a", "queue", 2, 2);
        assertEquals(2, page1.size());
        assertEquals(1, page2.size());
        assertEquals("ABP-001", page1.get(0).getCode());
        assertEquals("ABP-003", page2.get(0).getCode());
    }

    // --- findOrCreateByCode ---

    @Test
    void findOrCreateByCodeCreatesNewTitleWhenNotFound() {
        Title template = Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .build();

        Title result = titleRepo.findOrCreateByCode(template);
        assertNotNull(result.getId());
        assertEquals("ABP-001", result.getCode());
    }

    @Test
    void findOrCreateByCodeReturnsExistingTitle() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Title existing = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .actressId(aya.getId())
                .build());

        Title template = Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .build();

        Title result = titleRepo.findOrCreateByCode(template);
        assertEquals(existing.getId(), result.getId());
        assertEquals(aya.getId(), result.getActressId()); // retains existing actress
    }

    @Test
    void findOrCreateByCodeUpdatesActressWhenExistingHasNone() {
        Title existing = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .build());

        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Title template = Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .actressId(aya.getId())
                .build();

        Title result = titleRepo.findOrCreateByCode(template);
        assertEquals(existing.getId(), result.getId());
        assertEquals(aya.getId(), result.getActressId());
    }

    // --- deleteOrphaned ---

    @Test
    void deleteOrphanedRemovesTitlesWithNoLocations() {
        // Title with a location -- should survive
        Title withLocation = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .build());
        locationRepo.save(TitleLocation.builder()
                .titleId(withLocation.getId())
                .volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/ABP-001"))
                .lastSeenAt(LocalDate.now())
                .build());

        // Title without a location -- should be deleted
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2)
                .build());

        titleRepo.deleteOrphaned();

        assertTrue(titleRepo.findById(withLocation.getId()).isPresent());
        assertTrue(titleRepo.findById(orphan.getId()).isEmpty());
    }

    @Test
    void deleteOrphanedDoesNothingWhenAllTitlesHaveLocations() {
        Title t = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .build());
        locationRepo.save(TitleLocation.builder()
                .titleId(t.getId())
                .volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/ABP-001"))
                .lastSeenAt(LocalDate.now())
                .build());

        titleRepo.deleteOrphaned();

        assertTrue(titleRepo.findById(t.getId()).isPresent());
    }

    // --- helpers ---

    private static Actress actress(String canonicalName) {
        return Actress.builder()
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, Long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static Title titleWithLabel(String code, String label, Long actressId) {
        return Title.builder()
                .code(code).label(label)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static Title titleInPartition(String code, String partitionId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .build();
    }

    /** Save a title and create a TitleLocation for it (no addedDate). */
    private Title saveWithLocation(Title titleTemplate, String volumeId, String partitionId, String path) {
        return saveWithLocation(titleTemplate, volumeId, partitionId, path, null);
    }

    /** Save a title and create a TitleLocation for it. */
    private Title saveWithLocation(Title titleTemplate, String volumeId, String partitionId, String path, LocalDate addedDate) {
        Title saved = titleRepo.save(titleTemplate);
        locationRepo.save(TitleLocation.builder()
                .titleId(saved.getId())
                .volumeId(volumeId)
                .partitionId(partitionId)
                .path(Path.of(path))
                .lastSeenAt(LocalDate.of(2024, 1, 1))
                .addedDate(addedDate)
                .build());
        return saved;
    }
}
