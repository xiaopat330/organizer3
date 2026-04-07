package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
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
    private JdbiActressRepository actressRepo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        // Insert a volume row to satisfy the FK constraint on titles
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi);
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
        titleRepo.save(title("ABP-001", aya.getId()));
        titleRepo.save(title("ABP-002", aya.getId()));
        titleRepo.save(title("SSIS-001", hibiki.getId()));

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
        titleRepo.save(title("ABP-001", aya.getId()));
        titleRepo.save(title("ABP-002", aya.getId()));
        titleRepo.save(title("SSIS-001", hibiki.getId()));

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
        titleRepo.save(title("ABP-001", aya.getId()));

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

        titleRepo.save(title("ABP-001", aya.getId()));
        titleRepo.save(title("ABP-002", haruka.getId()));  // stranded on orphan record

        List<Title> results = titleRepo.findByActressIncludingAliases(aya.getId());
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-001")));
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-002")));
    }

    @Test
    void findByActressIncludingAliasesDoesNotReturnUnrelatedTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));
        titleRepo.save(title("ABP-001", aya.getId()));
        titleRepo.save(title("SSIS-001", hibiki.getId()));

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

        titleRepo.save(title("ABP-001", aya.getId()));     // canonical — should NOT appear
        titleRepo.save(title("ABP-002", haruka.getId()));  // stranded — should appear

        List<Title> results = titleRepo.findByAliasesOnly(aya.getId());
        assertEquals(1, results.size());
        assertEquals("ABP-002", results.get(0).getCode());
    }

    @Test
    void findByAliasesOnlyReturnsEmptyWhenNoOrphanRecords() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        actressRepo.saveAlias(new ActressAlias(aya.getId(), "Haruka Suzumiya"));
        titleRepo.save(title("ABP-001", aya.getId()));

        // No separate actress record for "Haruka Suzumiya", so no stranded titles
        assertTrue(titleRepo.findByAliasesOnly(aya.getId()).isEmpty());
    }

    @Test
    void findByAliasesOnlyReturnsEmptyWhenNoAliasesDefined() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        titleRepo.save(title("ABP-001", aya.getId()));
        assertTrue(titleRepo.findByAliasesOnly(aya.getId()).isEmpty());
    }

    // --- findRecent ---

    @Test
    void findRecentReturnsTitlesNewestFirst() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        titleRepo.save(titleWithDate("ABP-001", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(titleWithDate("ABP-002", aya.getId(), LocalDate.of(2024, 3, 1)));
        titleRepo.save(titleWithDate("ABP-003", aya.getId(), LocalDate.of(2024, 2, 1)));

        List<Title> results = titleRepo.findRecent(10, 0);
        assertEquals(3, results.size());
        assertEquals("ABP-002", results.get(0).getCode()); // newest first
        assertEquals("ABP-003", results.get(1).getCode());
        assertEquals("ABP-001", results.get(2).getCode());
    }

    @Test
    void findRecentRespectsLimitAndOffset() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        titleRepo.save(titleWithDate("ABP-001", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(titleWithDate("ABP-002", aya.getId(), LocalDate.of(2024, 2, 1)));
        titleRepo.save(titleWithDate("ABP-003", aya.getId(), LocalDate.of(2024, 3, 1)));

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
        titleRepo.save(titleWithDate("ABP-DATED", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(title("ABP-NODATE", aya.getId())); // no added_date

        List<Title> results = titleRepo.findRecent(10, 0);
        assertEquals(2, results.size());
        assertEquals("ABP-DATED", results.get(0).getCode()); // dated titles sort before null
        assertEquals("ABP-NODATE", results.get(1).getCode());
    }

    @Test
    void findRecentExcludesUnorganizedTitles() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        titleRepo.save(titleWithDate("ABP-ORGANIZED", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(titleWithDate("ABP-QUEUE", null, LocalDate.of(2025, 1, 1))); // no actress

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
        titleRepo.save(titleWithDate("ABP-001", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(titleWithDate("ABP-002", aya.getId(), LocalDate.of(2024, 3, 1)));
        titleRepo.save(titleWithDate("ABP-003", aya.getId(), LocalDate.of(2024, 2, 1)));

        List<Title> results = titleRepo.findByActressPaged(aya.getId(), 10, 0);
        assertEquals(3, results.size());
        assertEquals("ABP-002", results.get(0).getCode());
        assertEquals("ABP-003", results.get(1).getCode());
        assertEquals("ABP-001", results.get(2).getCode());
    }

    @Test
    void findByActressPagedRespectsLimitAndOffset() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        titleRepo.save(titleWithDate("ABP-001", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(titleWithDate("ABP-002", aya.getId(), LocalDate.of(2024, 2, 1)));
        titleRepo.save(titleWithDate("ABP-003", aya.getId(), LocalDate.of(2024, 3, 1)));

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
        titleRepo.save(titleWithDate("ABP-001", aya.getId(), LocalDate.of(2024, 1, 1)));
        titleRepo.save(titleWithDate("SSIS-001", hibiki.getId(), LocalDate.of(2024, 2, 1)));

        List<Title> results = titleRepo.findByActressPaged(aya.getId(), 10, 0);
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    // --- findDominantActressForLabel ---

    @Test
    void findDominantActressForLabelReturnsMostFrequentActress() {
        Actress aya    = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));

        // Insert titles with explicit label via titleWithLabel helper
        titleRepo.save(titleWithLabel("ABP-001", "ABP", aya.getId()));
        titleRepo.save(titleWithLabel("ABP-002", "ABP", aya.getId()));
        titleRepo.save(titleWithLabel("ABP-003", "ABP", hibiki.getId()));

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
        titleRepo.save(titleInPartition("ABP-001", "queue")); // actress_id = null
        assertTrue(titleRepo.findDominantActressForLabel("ABP").isEmpty());
    }

    // --- findByVolumeAndPartition ---

    @Test
    void findByVolumeAndPartitionReturnsOnlyMatchingTitles() {
        titleRepo.save(titleInPartition("ABP-001", "queue"));
        titleRepo.save(titleInPartition("ABP-002", "queue"));
        titleRepo.save(titleInPartition("ABP-003", "stars/library"));

        List<Title> results = titleRepo.findByVolumeAndPartition("vol-a", "queue", 10, 0);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(t -> "queue".equals(t.getPartitionId())));
    }

    @Test
    void findByVolumeAndPartitionRespectsOffsetAndLimit() {
        titleRepo.save(titleInPartitionWithDate("ABP-001", "queue", LocalDate.of(2024, 3, 1)));
        titleRepo.save(titleInPartitionWithDate("ABP-002", "queue", LocalDate.of(2024, 2, 1)));
        titleRepo.save(titleInPartitionWithDate("ABP-003", "queue", LocalDate.of(2024, 1, 1)));

        List<Title> page1 = titleRepo.findByVolumeAndPartition("vol-a", "queue", 2, 0);
        List<Title> page2 = titleRepo.findByVolumeAndPartition("vol-a", "queue", 2, 2);
        assertEquals(2, page1.size());
        assertEquals(1, page2.size());
        assertEquals("ABP-001", page1.get(0).getCode());
        assertEquals("ABP-003", page2.get(0).getCode());
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
        return titleWithDate(code, actressId, null);
    }

    private static Title titleWithDate(String code, Long actressId, LocalDate addedDate) {
        return Title.builder()
                .code(code)
                .volumeId("vol-a").partitionId("stars/library").actressId(actressId)
                .path(Path.of("/mnt/vol-a/stars/library/" + code))
                .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(addedDate)
                .build();
    }

    private static Title titleWithLabel(String code, String label, Long actressId) {
        return Title.builder()
                .code(code).label(label)
                .volumeId("vol-a").partitionId("stars/library").actressId(actressId)
                .path(Path.of("/mnt/vol-a/stars/library/" + code))
                .lastSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title titleInPartition(String code, String partitionId) {
        return titleInPartitionWithDate(code, partitionId, null);
    }

    private static Title titleInPartitionWithDate(String code, String partitionId, LocalDate addedDate) {
        return Title.builder()
                .code(code)
                .volumeId("vol-a").partitionId(partitionId)
                .path(Path.of("/mnt/vol-a/" + partitionId + "/" + code))
                .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(addedDate)
                .build();
    }
}
