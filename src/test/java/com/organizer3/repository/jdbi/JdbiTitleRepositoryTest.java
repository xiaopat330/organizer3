package com.organizer3.repository.jdbi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.TitleSortSpec;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiTitleRepository using an in-memory SQLite database.
 * Each test gets a fresh schema — no shared state between tests.
 */
class JdbiTitleRepositoryTest {

    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private EnrichmentHistoryRepository enrichmentHistoryRepo;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        // Insert volume rows to satisfy the FK constraint on title_locations
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-collections', 'collections')");
        });
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        enrichmentHistoryRepo = new EnrichmentHistoryRepository(jdbi, new ObjectMapper());
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo, enrichmentHistoryRepo, reviewQueueRepo);
        actressRepo = new JdbiActressRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
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
    void findByActressPagedReturnsFavoritesFirstThenBookmarksThenCode() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Title t1 = title("ABP-001", aya.getId());
        Title t2 = Title.builder().code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(1).actressId(aya.getId()).favorite(true).build();
        Title t3 = Title.builder().code("ABP-003").baseCode("ABP-00003").label("ABP").seqNum(1).actressId(aya.getId()).bookmark(true).build();
        saveWithLocation(t1, "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(t2, "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 3, 1));
        saveWithLocation(t3, "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 2, 1));

        List<Title> results = titleRepo.findByActressPaged(aya.getId(), 10, 0, TitleSortSpec.DEFAULT);
        assertEquals(3, results.size());
        assertEquals("ABP-002", results.get(0).getCode()); // favorite first
        assertEquals("ABP-003", results.get(1).getCode()); // bookmark second
        assertEquals("ABP-001", results.get(2).getCode()); // plain last
    }

    @Test
    void findByActressPagedRespectsLimitAndOffset() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 2, 1));
        saveWithLocation(title("ABP-003", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 3, 1));

        List<Title> page1 = titleRepo.findByActressPaged(aya.getId(), 2, 0, TitleSortSpec.DEFAULT);
        List<Title> page2 = titleRepo.findByActressPaged(aya.getId(), 2, 2, TitleSortSpec.DEFAULT);

        assertEquals(2, page1.size());
        assertEquals("ABP-003", page1.get(0).getCode()); // null release_date → tiebreak by id DESC
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

        List<Title> results = titleRepo.findByActressPaged(aya.getId(), 10, 0, TitleSortSpec.DEFAULT);
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    // --- findByActressTagsFiltered with enrichment tags ---

    @Test
    void findByActressTagsFilteredEnrichmentTagIdsFiltersCorrectly() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Actress hibiki = actressRepo.save(actress("Hibiki Otsuki"));
        Title t1 = saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        Title t2 = saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");
        saveWithLocation(title("SSIS-001", hibiki.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SSIS-001");

        // Insert enrichment tag definition and wire t1 to it
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO enrichment_tag_definitions (id, name, title_count, surface) VALUES (10, 'big-tits', 1, 1)");
            h.execute("INSERT INTO enrichment_tag_definitions (id, name, title_count, surface) VALUES (11, 'cosplay', 1, 1)");
            // Link title_actresses (needed for findEnrichmentTagsForActress)
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t1.getId() + ", " + aya.getId() + ")");
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t2.getId() + ", " + aya.getId() + ")");
            // t1 has tag 10; t2 has tags 10 and 11
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t1.getId() + ", 10)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2.getId() + ", 10)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2.getId() + ", 11)");
        });

        // Filter by tag 10 only → both t1 and t2
        List<Title> results = titleRepo.findByActressTagsFiltered(aya.getId(), List.of(), List.of(), List.of(10L), 10, 0, TitleSortSpec.DEFAULT);
        assertEquals(2, results.size());

        // Filter by tags 10 AND 11 → only t2
        results = titleRepo.findByActressTagsFiltered(aya.getId(), List.of(), List.of(), List.of(10L, 11L), 10, 0, TitleSortSpec.DEFAULT);
        assertEquals(1, results.size());
        assertEquals("ABP-002", results.get(0).getCode());

        // Hibiki's title not returned
        results = titleRepo.findByActressTagsFiltered(hibiki.getId(), List.of(), List.of(), List.of(10L), 10, 0, TitleSortSpec.DEFAULT);
        assertEquals(0, results.size());
    }

    @Test
    void findByActressTagsFilteredEmptyEnrichmentTagIdsBehavesLikePaged() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        saveWithLocation(title("ABP-001", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("ABP-002", aya.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");

        List<Title> filtered = titleRepo.findByActressTagsFiltered(aya.getId(), List.of(), List.of(), List.of(), 10, 0, TitleSortSpec.DEFAULT);
        List<Title> paged    = titleRepo.findByActressPaged(aya.getId(), 10, 0, TitleSortSpec.DEFAULT);
        assertEquals(paged.size(), filtered.size());
    }

    // --- findByCodePrefixPaged ---

    @Test
    void findByCodePrefixMatchesLabelPrefix() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-001");
        saveWithLocation(titleFull("SNIS-002", "SNIS", 2), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-002");
        saveWithLocation(titleFull("SSNI-100", "SSNI", 100), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SSNI-100");
        saveWithLocation(titleFull("ABP-010", "ABP", 10), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-010");

        // "S" prefix matches all three S-prefixed labels
        List<Title> results = titleRepo.findByCodePrefixPaged("S", "", 10, 0);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(t -> t.getLabel().startsWith("S")));

        // "SN" prefix matches only SNIS
        results = titleRepo.findByCodePrefixPaged("SN", "", 10, 0);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(t -> t.getLabel().startsWith("SN")));

        // "SNIS" is exact label match
        results = titleRepo.findByCodePrefixPaged("SNIS", "", 10, 0);
        assertEquals(2, results.size());
    }

    @Test
    void findByCodePrefixMatchesSeqPrefixIgnoringLeadingZeros() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1),   "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-001");
        saveWithLocation(titleFull("SNIS-010", "SNIS", 10),  "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-010");
        saveWithLocation(titleFull("SNIS-100", "SNIS", 100), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-100");
        saveWithLocation(titleFull("SNIS-123", "SNIS", 123), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-123");
        saveWithLocation(titleFull("SNIS-234", "SNIS", 234), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-234");

        // seq prefix "1" matches 1, 10, 100, 123
        List<Title> results = titleRepo.findByCodePrefixPaged("SNIS", "1", 10, 0);
        assertEquals(4, results.size());
        assertTrue(results.stream().map(Title::getSeqNum).toList().containsAll(List.of(1, 10, 100, 123)));

        // seq prefix "12" matches only 123
        results = titleRepo.findByCodePrefixPaged("SNIS", "12", 10, 0);
        assertEquals(1, results.size());
        assertEquals(123, results.get(0).getSeqNum());
    }

    @Test
    void findByCodePrefixOrdersFavoritesThenBookmarksThenLabelSeq() {
        // plain (ABP-001), favorite (SNIS-003), bookmark (SNIS-002), plain (SNIS-001)
        saveWithLocation(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(Title.builder().code("SNIS-001").baseCode("SNIS-00001").label("SNIS").seqNum(1).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-001");
        saveWithLocation(Title.builder().code("SNIS-002").baseCode("SNIS-00002").label("SNIS").seqNum(2).bookmark(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-002");
        saveWithLocation(Title.builder().code("SNIS-003").baseCode("SNIS-00003").label("SNIS").seqNum(3).favorite(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-003");

        List<Title> results = titleRepo.findByCodePrefixPaged("S", "", 10, 0);
        assertEquals(3, results.size());
        assertEquals("SNIS-003", results.get(0).getCode()); // favorite first
        assertEquals("SNIS-002", results.get(1).getCode()); // bookmark second
        assertEquals("SNIS-001", results.get(2).getCode()); // plain last
    }

    @Test
    void findByCodePrefixIsCaseInsensitive() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-001");
        List<Title> results = titleRepo.findByCodePrefixPaged("snis", "", 10, 0);
        assertEquals(1, results.size());
    }

    @Test
    void findByCodePrefixRespectsLimitAndOffset() {
        for (int i = 1; i <= 5; i++) {
            saveWithLocation(titleFull("SNIS-00" + i, "SNIS", i),
                    "vol-a", "stars/library", "/mnt/vol-a/stars/library/SNIS-00" + i);
        }
        List<Title> page1 = titleRepo.findByCodePrefixPaged("SNIS", "", 2, 0);
        List<Title> page2 = titleRepo.findByCodePrefixPaged("SNIS", "", 2, 2);
        assertEquals(2, page1.size());
        assertEquals(2, page2.size());
        assertEquals(1, page1.get(0).getSeqNum());
        assertEquals(3, page2.get(0).getSeqNum());
    }

    // --- findFavoritesPaged / findBookmarksPaged ---

    @Test
    void findFavoritesPagedReturnsOnlyFavoritedTitles() {
        saveWithLocation(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).favorite(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(Title.builder().code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2).favorite(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 2, 1));
        saveWithLocation(Title.builder().code("ABP-003").baseCode("ABP-00003").label("ABP").seqNum(3).bookmark(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-003", LocalDate.of(2024, 3, 1));
        saveWithLocation(title("ABP-004", null),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-004", LocalDate.of(2024, 4, 1));

        List<Title> results = titleRepo.findFavoritesPaged(10, 0);
        assertEquals(2, results.size());
        // Ordered newest-first by added_date
        assertEquals("ABP-002", results.get(0).getCode());
        assertEquals("ABP-001", results.get(1).getCode());
    }

    @Test
    void findBookmarksPagedReturnsOnlyBookmarkedTitles() {
        saveWithLocation(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).bookmark(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001", LocalDate.of(2024, 1, 1));
        saveWithLocation(Title.builder().code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2).favorite(true).build(),
                "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002", LocalDate.of(2024, 2, 1));

        List<Title> results = titleRepo.findBookmarksPaged(10, 0);
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

    @Test
    void findByVolumeAndPartitionExcludesUnderscorePrefixedFolders() {
        saveWithLocation(titleInPartition("ABP-001", "pool"), "vol-pool", "pool", "/mnt/pool/ABP-001");
        saveWithLocation(titleInPartition("ABP-002", "pool"), "vol-pool", "pool", "/mnt/pool/_sandbox");
        saveWithLocation(titleInPartition("ABP-003", "pool"), "vol-pool", "pool", "/mnt/pool/_trash/ABP-003");

        List<Title> results = titleRepo.findByVolumeAndPartition("vol-pool", "pool", 10, 0);
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    @Test
    void findByVolumeAndPartitionFilteredExcludesUnderscorePrefixedFolders() {
        saveWithLocation(titleInPartition("ABP-001", "pool"), "vol-pool", "pool", "/mnt/pool/ABP-001");
        saveWithLocation(titleInPartition("ABP-002", "pool"), "vol-pool", "pool", "/mnt/pool/_sandbox");

        List<Title> results = titleRepo.findByVolumeAndPartitionFiltered("vol-pool", "pool", List.of(), List.of(), 10, 0);
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    // --- findByVolumePaged ---

    @Test
    void findByVolumePagedReturnsAllPartitionsForVolume() {
        saveWithLocation(titleInPartition("ABP-001", "archive"), "vol-collections", "archive", "/mnt/col/archive/ABP-001");
        saveWithLocation(titleInPartition("ABP-002", "converted"), "vol-collections", "converted", "/mnt/col/converted/ABP-002");
        saveWithLocation(titleInPartition("ABP-003", "queue"), "vol-a", "queue", "/mnt/vol-a/queue/ABP-003");

        List<Title> results = titleRepo.findByVolumePaged("vol-collections", 10, 0);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-001")));
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-002")));
    }

    @Test
    void findByVolumePagedRespectsOffsetAndLimit() {
        saveWithLocation(titleInPartition("ABP-001", "archive"), "vol-collections", "archive", "/mnt/col/archive/ABP-001", LocalDate.of(2024, 3, 1));
        saveWithLocation(titleInPartition("ABP-002", "archive"), "vol-collections", "archive", "/mnt/col/archive/ABP-002", LocalDate.of(2024, 2, 1));
        saveWithLocation(titleInPartition("ABP-003", "converted"), "vol-collections", "converted", "/mnt/col/converted/ABP-003", LocalDate.of(2024, 1, 1));

        List<Title> page1 = titleRepo.findByVolumePaged("vol-collections", 2, 0);
        List<Title> page2 = titleRepo.findByVolumePaged("vol-collections", 2, 2);
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

    // --- findLabelCodesWithPrefix ---

    @Test
    void findLabelCodesWithPrefixReturnsMatchingCodes() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("SSNI-001", "SSNI", 1), "vol-a", "stars/library", "/s2");
        saveWithLocation(titleFull("ABP-001",  "ABP",  1), "vol-a", "stars/library", "/s3");

        List<String> results = titleRepo.findLabelCodesWithPrefix("S");
        assertEquals(2, results.size());
        assertTrue(results.containsAll(List.of("SNIS", "SSNI")));
    }

    @Test
    void findLabelCodesWithPrefixIsCaseInsensitive() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        List<String> results = titleRepo.findLabelCodesWithPrefix("sn");
        assertEquals(1, results.size());
        assertEquals("SNIS", results.get(0));
    }

    @Test
    void findLabelCodesWithPrefixReturnsDistinctCodes() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("SNIS-002", "SNIS", 2), "vol-a", "stars/library", "/s2");
        List<String> results = titleRepo.findLabelCodesWithPrefix("SNIS");
        assertEquals(1, results.size());
    }

    @Test
    void findLabelCodesWithPrefixReturnsEmptyForBlankPrefix() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        assertTrue(titleRepo.findLabelCodesWithPrefix("").isEmpty());
        assertTrue(titleRepo.findLabelCodesWithPrefix(null).isEmpty());
    }

    @Test
    void findLabelCodesWithPrefixIsOrderedAlphabetically() {
        saveWithLocation(titleFull("SSNI-001", "SSNI", 1), "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s2");
        saveWithLocation(titleFull("SONE-001", "SONE", 1), "vol-a", "stars/library", "/s3");
        List<String> results = titleRepo.findLabelCodesWithPrefix("S");
        assertEquals(List.of("SNIS", "SONE", "SSNI"), results);
    }

    // --- findLibraryPaged ---

    @Test
    void findLibraryPagedWithNoFiltersReturnsAllTitles() {
        saveWithLocation(titleFull("ABP-001", "ABP", 1),   "vol-a", "stars/library", "/a1");
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");

        List<Title> results = titleRepo.findLibraryPaged("", "", List.of(), List.of(), null, false, 10, 0);
        assertEquals(2, results.size());
    }

    @Test
    void findLibraryPagedFiltersByLabelPrefix() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("SSNI-001", "SSNI", 1), "vol-a", "stars/library", "/s2");
        saveWithLocation(titleFull("ABP-001",  "ABP",  1), "vol-a", "stars/library", "/a1");

        List<Title> results = titleRepo.findLibraryPaged("SN", "", List.of(), List.of(), null, false, 10, 0);
        assertEquals(1, results.size());
        assertEquals("SNIS-001", results.get(0).getCode());
    }

    @Test
    void findLibraryPagedFiltersByExactLabelAndSeq() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1),  "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("SNIS-042", "SNIS", 42), "vol-a", "stars/library", "/s2");
        saveWithLocation(titleFull("SNIS-421", "SNIS", 421),"vol-a", "stars/library", "/s3");

        // seq prefix "42" matches 42 and 421
        List<Title> results = titleRepo.findLibraryPaged("SNIS", "42", List.of(), List.of(), null, false, 10, 0);
        assertEquals(2, results.size());
        assertTrue(results.stream().map(Title::getSeqNum).toList().containsAll(List.of(42, 421)));
    }

    @Test
    void findLibraryPagedFiltersByCompanyLabels() {
        saveWithLocation(titleFull("ABP-001",  "ABP",  1), "vol-a", "stars/library", "/a1");
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");

        // Only ABP
        List<Title> results = titleRepo.findLibraryPaged("", "", List.of("ABP"), List.of(), null, false, 10, 0);
        assertEquals(1, results.size());
        assertEquals("ABP-001", results.get(0).getCode());
    }

    @Test
    void findLibraryPagedSortsByProductCodeAscending() {
        saveWithLocation(titleFull("SNIS-003", "SNIS", 3), "vol-a", "stars/library", "/s3");
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("ABP-001",  "ABP",  1), "vol-a", "stars/library", "/a1");

        List<Title> results = titleRepo.findLibraryPaged("", "", List.of(), List.of(), "productCode", true, 10, 0);
        assertEquals("ABP-001",  results.get(0).getCode());
        assertEquals("SNIS-001", results.get(1).getCode());
        assertEquals("SNIS-003", results.get(2).getCode());
    }

    @Test
    void findLibraryPagedSortsByProductCodeDescending() {
        saveWithLocation(titleFull("SNIS-001", "SNIS", 1), "vol-a", "stars/library", "/s1");
        saveWithLocation(titleFull("ABP-001",  "ABP",  1), "vol-a", "stars/library", "/a1");

        List<Title> results = titleRepo.findLibraryPaged("", "", List.of(), List.of(), "productCode", false, 10, 0);
        assertEquals("SNIS-001", results.get(0).getCode());
        assertEquals("ABP-001",  results.get(1).getCode());
    }

    @Test
    void findLibraryPagedRespectsLimitAndOffset() {
        for (int i = 1; i <= 5; i++) {
            saveWithLocation(titleFull("ABP-00" + i, "ABP", i), "vol-a", "stars/library", "/a" + i);
        }
        List<Title> page1 = titleRepo.findLibraryPaged("ABP", "", List.of(), List.of(), "productCode", true, 2, 0);
        List<Title> page2 = titleRepo.findLibraryPaged("ABP", "", List.of(), List.of(), "productCode", true, 2, 2);
        assertEquals(2, page1.size());
        assertEquals(2, page2.size());
        assertNotEquals(page1.get(0).getCode(), page2.get(0).getCode());
    }

    @Test
    void findLibraryPagedFiltersOnEnrichmentTagIds() {
        Title t1 = saveWithLocation(titleFull("ABP-001", "ABP", 1), "vol-a", "stars/library", "/a1");
        Title t2 = saveWithLocation(titleFull("ABP-002", "ABP", 2), "vol-a", "stars/library", "/a2");
        saveWithLocation(titleFull("ABP-003", "ABP", 3), "vol-a", "stars/library", "/a3"); // no enrichment tags
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO enrichment_tag_definitions (id, name) VALUES (10, 'vibrator')");
            h.execute("INSERT INTO enrichment_tag_definitions (id, name) VALUES (11, 'cosplay')");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t1.getId() + ", 10)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2.getId() + ", 10)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2.getId() + ", 11)");
        });

        // Filter by tag 10 (vibrator) — should return t1 and t2
        List<Title> results = titleRepo.findLibraryPaged("", "", List.of(), List.of(), List.of(10L), null, false, 10, 0);
        assertEquals(2, results.size());
        var codes = results.stream().map(Title::getCode).toList();
        assertTrue(codes.contains("ABP-001") && codes.contains("ABP-002"));

        // Filter by both tags (AND semantics) — only t2 has both
        List<Title> andResults = titleRepo.findLibraryPaged("", "", List.of(), List.of(), List.of(10L, 11L), null, false, 10, 0);
        assertEquals(1, andResults.size());
        assertEquals("ABP-002", andResults.get(0).getCode());
    }

    @Test
    void getTagCountsReturnsCountsFromEffectiveTags() {
        Title t1 = saveWithLocation(titleFull("ABP-001", "ABP", 1), "vol-a", "stars/library", "/a1");
        Title t2 = saveWithLocation(titleFull("ABP-002", "ABP", 2), "vol-a", "stars/library", "/a2");
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO tags (name, category) VALUES ('4K', 'format'), ('POV', 'production_style')");
            h.execute("INSERT INTO title_effective_tags (title_id, tag, source) VALUES (" + t1.getId() + ", '4K', 'direct')");
            h.execute("INSERT INTO title_effective_tags (title_id, tag, source) VALUES (" + t2.getId() + ", '4K', 'direct')");
            h.execute("INSERT INTO title_effective_tags (title_id, tag, source) VALUES (" + t1.getId() + ", 'POV', 'direct')");
        });
        var counts = titleRepo.getTagCounts();
        assertEquals(2L, counts.get("4K"));
        assertEquals(1L, counts.get("POV"));
        assertFalse(counts.containsKey("nonexistent"));
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

    @Test
    void deleteOrphanedReturnsNumberRemoved() {
        // One with location, two without
        Title keep = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).build());
        locationRepo.save(TitleLocation.builder().titleId(keep.getId())
                .volumeId("vol-a").partitionId("queue").path(Path.of("/queue/ABP-001"))
                .lastSeenAt(LocalDate.now()).build());
        titleRepo.save(Title.builder().code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2).build());
        titleRepo.save(Title.builder().code("ABP-003").baseCode("ABP-00003").label("ABP").seqNum(3).build());

        assertEquals(2, titleRepo.deleteOrphaned().deleted());
        assertEquals(0, titleRepo.deleteOrphaned().deleted()); // idempotent — nothing left to drop
    }

    // --- cascade guard: CatastrophicDeleteException ---

    /**
     * Regression for 2026-04-23 incident. A buggy predicate wiped every title_locations
     * row; the next sync's {@code deleteOrphaned()} would then have dropped every title.
     * The guard must refuse to run when the orphan count exceeds the plausibility
     * threshold, leaving all rows intact for a human to investigate.
     */
    @Test
    void deleteOrphanedRefusesWhenEveryTitleIsOrphaned() {
        int count = com.organizer3.repository.jdbi.JdbiTitleRepository.ORPHAN_DELETE_FLOOR + 10;
        for (int i = 0; i < count; i++) {
            String code = String.format("ABP-%04d", i + 1);
            String base = String.format("ABP-%05d", i + 1);
            titleRepo.save(Title.builder().code(code).baseCode(base).label("ABP").seqNum(i + 1).build());
        }
        // No locations at all → every title is "orphaned". A naive deleteOrphaned would
        // drop all of them. The guard must throw, and NOTHING must be deleted.

        com.organizer3.repository.CatastrophicDeleteException ex =
                assertThrows(com.organizer3.repository.CatastrophicDeleteException.class,
                        () -> titleRepo.deleteOrphaned());
        assertEquals(count, ex.wouldDelete());
        assertEquals(count, ex.total());

        // Nothing was deleted — the transaction rolled back on throw.
        assertEquals(count, countAllTitles());
    }

    /**
     * Boundary: orphan count at the 500 floor must still delete (just barely plausible).
     * This is the false-positive side of the guard — a legitimately large cleanup on a
     * still-modest library should not be blocked.
     */
    @Test
    void deleteOrphanedAllowsUpToFloor() {
        int floor = com.organizer3.repository.jdbi.JdbiTitleRepository.ORPHAN_DELETE_FLOOR;
        // Seed enough titles-with-locations that the 25% ratio gate isn't the binding
        // constraint (total = 4*floor → ratio threshold = floor). Then add exactly floor
        // more orphans: orphans(floor) <= threshold(floor), guard must allow.
        int keepers = 3 * floor;
        for (int i = 0; i < keepers; i++) {
            String code = String.format("ABP-%04d", i + 1);
            String base = String.format("ABP-%05d", i + 1);
            Title kept = titleRepo.save(Title.builder().code(code).baseCode(base).label("ABP").seqNum(i + 1).build());
            locationRepo.save(TitleLocation.builder().titleId(kept.getId())
                    .volumeId("vol-a").partitionId("queue").path(Path.of("/queue/" + code))
                    .lastSeenAt(LocalDate.now()).build());
        }
        for (int i = 0; i < floor; i++) {
            String code = String.format("CJD-%04d", i + 1);
            String base = String.format("CJD-%05d", i + 1);
            titleRepo.save(Title.builder().code(code).baseCode(base).label("CJD").seqNum(i + 1).build());
        }

        assertEquals(floor, titleRepo.deleteOrphaned().deleted());
        assertEquals(keepers, countAllTitles());
    }

    private int countAllTitles() {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM titles")
                .mapTo(Integer.class).one());
    }

    // --- deleteOrphaned: enriched orphan → flagged not deleted (4B Phase 1) ---

    /**
     * An orphan WITH a title_javdb_enrichment row must NOT be deleted. Instead it gets
     * a review-queue row (reason='orphan_enriched') so the user can confirm the delete.
     * The title and all its enrichment data must remain intact.
     */
    @Test
    void deleteOrphaned_enrichedOrphan_isFlaggedNotDeleted() {
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-010").baseCode("ABP-00010").label("ABP").seqNum(10).build());
        long id = orphan.getId();

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'abp-010', '2026-01-01T00:00:00')", id);
        });

        var result = titleRepo.deleteOrphaned();

        assertEquals(0, result.deleted(), "enriched orphan must not be deleted");
        assertEquals(1, result.flagged(), "enriched orphan must be flagged");
        assertTrue(titleRepo.findById(id).isPresent(), "title must still exist");
        assertEquals(1, countRows("title_javdb_enrichment", id), "enrichment must be intact");
        assertEquals(1, countRows("enrichment_review_queue", id), "queue row must be inserted");
    }

    /**
     * An orphan WITHOUT a title_javdb_enrichment row must be deleted immediately
     * with the 4A cascade: tag rows and pending rows cleaned, no history written.
     */
    @Test
    void deleteOrphaned_unenrichedOrphan_isDeleted() {
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-020").baseCode("ABP-00020").label("ABP").seqNum(20).build());
        long id = orphan.getId();

        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions (id, name) VALUES (1, 'test-tag')");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (?, 1)", id);
            h.execute("INSERT INTO revalidation_pending (title_id, reason) VALUES (?, 'slug_changed')", id);
        });

        var result = titleRepo.deleteOrphaned();

        assertEquals(1, result.deleted());
        assertEquals(0, result.flagged());
        assertTrue(titleRepo.findById(id).isEmpty(), "unenriched orphan must be deleted");
        assertEquals(0, countRows("title_enrichment_tags", id));
        assertEquals(0, countRows("revalidation_pending", id));
        assertEquals(0, enrichmentHistoryRepo.countForTitle(id), "no history when no enrichment row");
    }

    /**
     * Mixed set: one enriched orphan + one unenriched orphan. Verify the split counts
     * and that each is handled correctly.
     */
    @Test
    void deleteOrphaned_mixedOrphans_splitCorrectly() {
        Title unenriched = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).build());
        Title enriched = titleRepo.save(Title.builder()
                .code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2).build());
        // keeper with location
        Title keeper = titleRepo.save(Title.builder()
                .code("ABP-003").baseCode("ABP-00003").label("ABP").seqNum(3).build());
        locationRepo.save(TitleLocation.builder().titleId(keeper.getId())
                .volumeId("vol-a").partitionId("queue").path(Path.of("/queue/ABP-003"))
                .lastSeenAt(LocalDate.now()).build());

        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'abp-002', '2026-01-01T00:00:00')",
                enriched.getId()));

        var result = titleRepo.deleteOrphaned();

        assertEquals(1, result.deleted(), "unenriched deleted");
        assertEquals(1, result.flagged(), "enriched flagged");
        assertTrue(titleRepo.findById(unenriched.getId()).isEmpty());
        assertTrue(titleRepo.findById(enriched.getId()).isPresent());
        assertTrue(titleRepo.findById(keeper.getId()).isPresent());
        assertEquals(1, countRows("enrichment_review_queue", enriched.getId()));
    }

    /**
     * Idempotent flagging: calling deleteOrphaned() a second time with the same enriched
     * orphan must not create a duplicate queue row (INSERT OR IGNORE via partial unique index).
     */
    @Test
    void deleteOrphaned_enrichedOrphan_flagIdempotent() {
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-010").baseCode("ABP-00010").label("ABP").seqNum(10).build());
        long id = orphan.getId();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'abp-010', '2026-01-01T00:00:00')", id));

        titleRepo.deleteOrphaned();
        titleRepo.deleteOrphaned(); // second call

        assertEquals(1, countRows("enrichment_review_queue", id), "only one queue row allowed");
    }

    /**
     * Enriched orphan flagged then resolved as 'marked_moved': on the next sync
     * it must be re-flagged (the prior queue row is resolved, so INSERT OR IGNORE inserts
     * a fresh open row). This is correct behavior — the safety rail nags until the user
     * uses recode_title to reconcile.
     */
    @Test
    void deleteOrphaned_enrichedOrphan_reflaggedAfterMarkedMoved() {
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-010").baseCode("ABP-00010").label("ABP").seqNum(10).build());
        long id = orphan.getId();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'abp-010', '2026-01-01T00:00:00')", id));

        titleRepo.deleteOrphaned();
        // Simulate user resolving the queue row as 'marked_moved'
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-05-01T00:00:00', resolution = 'marked_moved' WHERE title_id = ?", id));

        titleRepo.deleteOrphaned(); // next sync cycle

        // A new open row must have been inserted
        assertEquals(1, reviewQueueRepo.countOpen("orphan_enriched"),
                "re-flag must insert a new open row after prior one is resolved");
    }

    // --- deleteOrphaned: cascade on unenriched orphan (4A pattern preserved) ---

    /**
     * Unenriched orphan with satellite rows (tag, pending): tag and pending rows are deleted;
     * any pre-existing queue rows are left as orphan rows (tolerated via LEFT JOIN).
     * History is NOT written — appendIfExists no-ops without enrichment row.
     */
    @Test
    void deleteOrphanedCascadesEnrichmentTablesAndSnapshotsHistory() {
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-030").baseCode("ABP-00030").label("ABP").seqNum(30).build());
        long id = orphan.getId();

        // Seed satellite rows WITHOUT an enrichment row
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions (id, name) VALUES (1, 'test-tag')");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (?, 1)", id);
            h.execute("INSERT INTO revalidation_pending (title_id, reason) VALUES (?, 'slug_changed')", id);
        });

        titleRepo.deleteOrphaned();

        assertTrue(titleRepo.findById(id).isEmpty(), "title must be deleted");
        assertEquals(0, countRows("title_enrichment_tags", id));
        assertEquals(0, countRows("revalidation_pending", id));
        assertEquals(0, enrichmentHistoryRepo.countForTitle(id), "no history — no enrichment row");
    }

    /**
     * Catastrophic-delete guard with enrichment rows present. Guard must throw before any
     * delete executes — none of the 5 tables may be modified.
     */
    @Test
    void deleteOrphanedGuardLeavesEnrichmentTablesIntact() {
        int count = com.organizer3.repository.jdbi.JdbiTitleRepository.ORPHAN_DELETE_FLOOR + 10;
        long firstId = -1;
        for (int i = 0; i < count; i++) {
            String code = String.format("ABP-%04d", i + 1);
            String base = String.format("ABP-%05d", i + 1);
            Title t = titleRepo.save(Title.builder().code(code).baseCode(base).label("ABP").seqNum(i + 1).build());
            if (i == 0) firstId = t.getId();
        }
        // Seed enrichment rows for the first orphan
        final long seedId = firstId;
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions (id, name) VALUES (1, 'test-tag')");
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'abp-0001', '2026-01-01T00:00:00')", seedId);
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (?, 1)", seedId);
            h.execute("INSERT INTO enrichment_review_queue (title_id, reason, created_at) VALUES (?, 'no_match', '2026-01-01T00:00:00')", seedId);
            h.execute("INSERT INTO revalidation_pending (title_id, reason) VALUES (?, 'slug_changed')", seedId);
        });

        assertThrows(com.organizer3.repository.CatastrophicDeleteException.class,
                () -> titleRepo.deleteOrphaned());

        // Nothing deleted or flagged from any of the 5 tables
        assertEquals(count, countAllTitles());
        assertEquals(1, countRows("title_javdb_enrichment", seedId));
        assertEquals(1, countRows("title_enrichment_tags", seedId));
        assertEquals(1, countRows("enrichment_review_queue", seedId));
        assertEquals(1, countRows("revalidation_pending", seedId));
        assertEquals(0, enrichmentHistoryRepo.countForTitle(seedId));
    }

    /**
     * Orphan with NO enrichment row. appendIfExists must write nothing, but the title
     * and any tag/queue/pending rows for the id are still cleaned.
     */
    @Test
    void deleteOrphanedWithNoEnrichmentRowWritesNoHistory() {
        Title orphan = titleRepo.save(Title.builder()
                .code("ABP-020").baseCode("ABP-00020").label("ABP").seqNum(20).build());
        long id = orphan.getId();

        // Tag and queue rows without an enrichment row (FK enforcement off)
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions (id, name) VALUES (1, 'test-tag')");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (?, 1)", id);
            h.execute("INSERT INTO revalidation_pending (title_id, reason) VALUES (?, 'slug_changed')", id);
        });

        titleRepo.deleteOrphaned();

        assertTrue(titleRepo.findById(id).isEmpty(), "title should be deleted");
        assertEquals(0, countRows("title_enrichment_tags", id));
        assertEquals(0, countRows("revalidation_pending", id));
        assertEquals(0, enrichmentHistoryRepo.countForTitle(id), "no history when no enrichment row");
    }

    // --- deleteOne ---

    @Test
    void deleteOne_deletesEnrichedTitleWithCascadeAndHistory() {
        Title t = titleRepo.save(Title.builder()
                .code("ABP-099").baseCode("ABP-00099").label("ABP").seqNum(99).build());
        long id = t.getId();
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions (id, name) VALUES (1, 'test-tag')");
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'abp-099', '2026-01-01T00:00:00')", id);
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (?, 1)", id);
            h.execute("INSERT INTO revalidation_pending (title_id, reason) VALUES (?, 'slug_changed')", id);
        });

        titleRepo.deleteOne(id);

        assertTrue(titleRepo.findById(id).isEmpty(), "title must be deleted");
        assertEquals(0, countRows("title_javdb_enrichment", id));
        assertEquals(0, countRows("title_enrichment_tags", id));
        assertEquals(0, countRows("revalidation_pending", id));
        // History snapshot must have been written
        assertEquals(1, enrichmentHistoryRepo.countForTitle(id));
        assertEquals("title_deleted", enrichmentHistoryRepo.recentForTitle(id, 1).get(0).reason());
    }

    private int countRows(String table, long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + table + " WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(Integer.class)
                        .one());
    }

    // --- cross-volume: findByActress and countByActress via title_actresses ---

    @Test
    void findByActressIncludesCollectionsTitleLinkedViaJunctionTable() {
        Actress aika = actressRepo.save(actress("Aika"));

        // Collections title: actress_id is null, actress linked via junction table
        Title collectionsTitle = saveWithLocation(
                Title.builder().code("HMN-102").baseCode("HMN-00102").label("HMN").seqNum(102).build(),
                "vol-collections", "archive", "/mnt/col/archive/HMN-102");
        titleActressRepo.link(collectionsTitle.getId(), aika.getId());

        List<Title> results = titleRepo.findByActress(aika.getId());

        assertEquals(1, results.size());
        assertEquals("HMN-102", results.get(0).getCode());
    }

    @Test
    void findByActressReturnsBothConventionalAndCollectionsTitles() {
        Actress aika = actressRepo.save(actress("Aika"));

        // Conventional title: actress_id set directly
        Title conventional = saveWithLocation(
                title("ABP-001", aika.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");

        // Collections title: actress_id null, linked via junction table
        Title collections = saveWithLocation(
                Title.builder().code("HMN-102").baseCode("HMN-00102").label("HMN").seqNum(102).build(),
                "vol-collections", "archive", "/mnt/col/archive/HMN-102");
        titleActressRepo.link(collections.getId(), aika.getId());

        List<Title> results = titleRepo.findByActress(aika.getId());

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("ABP-001")));
        assertTrue(results.stream().anyMatch(t -> t.getCode().equals("HMN-102")));
    }

    @Test
    void countByActressCountsBothConventionalAndCollectionsTitles() {
        Actress aika = actressRepo.save(actress("Aika"));

        saveWithLocation(title("ABP-001", aika.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        saveWithLocation(title("ABP-002", aika.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-002");

        Title col1 = saveWithLocation(
                Title.builder().code("HMN-102").baseCode("HMN-00102").label("HMN").seqNum(102).build(),
                "vol-collections", "archive", "/mnt/col/archive/HMN-102");
        titleActressRepo.link(col1.getId(), aika.getId());

        assertEquals(3, titleRepo.countByActress(aika.getId()));
    }

    @Test
    void countByActressDoesNotDoubleCountWhenActressIsInBothActressIdAndJunctionTable() {
        // If a title has actress_id = X AND a row in title_actresses for X,
        // it should still be counted only once.
        Actress aika = actressRepo.save(actress("Aika"));
        Title t = saveWithLocation(title("ABP-001", aika.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        titleActressRepo.link(t.getId(), aika.getId()); // double-link same title

        assertEquals(1, titleRepo.countByActress(aika.getId()));
    }

    @Test
    void findByActressDoesNotDoubleCountWhenActressIsInBothActressIdAndJunctionTable() {
        Actress aika = actressRepo.save(actress("Aika"));
        Title t = saveWithLocation(title("ABP-001", aika.getId()), "vol-a", "stars/library", "/mnt/vol-a/stars/library/ABP-001");
        titleActressRepo.link(t.getId(), aika.getId());

        List<Title> results = titleRepo.findByActress(aika.getId());
        assertEquals(1, results.size());
    }

    @Test
    void findByActressExcludesCollectionsTitlesForOtherActresses() {
        Actress aika = actressRepo.save(actress("Aika"));
        Actress yui  = actressRepo.save(actress("Yui Hatano"));

        Title aikaTitle = saveWithLocation(
                Title.builder().code("HMN-102").baseCode("HMN-00102").label("HMN").seqNum(102).build(),
                "vol-collections", "archive", "/mnt/col/archive/HMN-102");
        titleActressRepo.link(aikaTitle.getId(), aika.getId());
        titleActressRepo.link(aikaTitle.getId(), yui.getId()); // duo title

        Title yuiOnly = saveWithLocation(
                Title.builder().code("HMN-200").baseCode("HMN-00200").label("HMN").seqNum(200).build(),
                "vol-collections", "archive", "/mnt/col/archive/HMN-200");
        titleActressRepo.link(yuiOnly.getId(), yui.getId());

        // aika appears in HMN-102 only
        List<Title> aikaResults = titleRepo.findByActress(aika.getId());
        assertEquals(1, aikaResults.size());
        assertEquals("HMN-102", aikaResults.get(0).getCode());

        // yui appears in both
        List<Title> yuiResults = titleRepo.findByActress(yui.getId());
        assertEquals(2, yuiResults.size());
    }

    // --- recordVisit ---

    @Test
    void recordVisitIncrementsCountFromZero() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Title saved = titleRepo.save(title("ABP-001", aya.getId()));
        assertEquals(0, titleRepo.findById(saved.getId()).get().getVisitCount());

        titleRepo.recordVisit(saved.getId());

        Title updated = titleRepo.findById(saved.getId()).get();
        assertEquals(1, updated.getVisitCount());
        assertNotNull(updated.getLastVisitedAt());
    }

    @Test
    void recordVisitAccumulatesAcrossMultipleCalls() {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Title saved = titleRepo.save(title("ABP-001", aya.getId()));

        titleRepo.recordVisit(saved.getId());
        titleRepo.recordVisit(saved.getId());
        titleRepo.recordVisit(saved.getId());

        assertEquals(3, titleRepo.findById(saved.getId()).get().getVisitCount());
    }

    @Test
    void recordVisitUpdatesLastVisitedAt() throws Exception {
        Actress aya = actressRepo.save(actress("Aya Sazanami"));
        Title saved = titleRepo.save(title("ABP-001", aya.getId()));
        titleRepo.recordVisit(saved.getId());
        var first = titleRepo.findById(saved.getId()).get().getLastVisitedAt();

        Thread.sleep(10);
        titleRepo.recordVisit(saved.getId());
        var second = titleRepo.findById(saved.getId()).get().getLastVisitedAt();

        assertTrue(second.isAfter(first));
    }

    // --- countTitlesByCompanies ---

    @Test
    void countTitlesByCompaniesGroupsByLabelCompany() {
        insertLabel("MIDA", "Moodyz");
        insertLabel("MIAA", "Moodyz");
        insertLabel("SSIS", "S1");
        insertLabel("XXX",  "Outside");

        saveWithLocation(titleFull("MIDA-001", "MIDA", 1), "vol-a", "stars/library", "/p/MIDA-001");
        saveWithLocation(titleFull("MIDA-002", "MIDA", 2), "vol-a", "stars/library", "/p/MIDA-002");
        saveWithLocation(titleFull("MIAA-001", "MIAA", 1), "vol-a", "stars/library", "/p/MIAA-001");
        saveWithLocation(titleFull("SSIS-001", "SSIS", 1), "vol-a", "stars/library", "/p/SSIS-001");
        saveWithLocation(titleFull("XXX-001",  "XXX",  1), "vol-a", "stars/library", "/p/XXX-001");

        var counts = titleRepo.countTitlesByCompanies(List.of("Moodyz", "S1"));
        assertEquals(2, counts.size());
        assertEquals(3L, counts.get("Moodyz")); // MIDA-001, MIDA-002, MIAA-001
        assertEquals(1L, counts.get("S1"));
        assertNull(counts.get("Outside"));
    }

    @Test
    void countTitlesByCompaniesOmitsCompaniesWithZeroTitles() {
        insertLabel("MIDA", "Moodyz");
        saveWithLocation(titleFull("MIDA-001", "MIDA", 1), "vol-a", "stars/library", "/p/MIDA-001");

        var counts = titleRepo.countTitlesByCompanies(List.of("Moodyz", "Madonna"));
        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("Moodyz"));
        assertNull(counts.get("Madonna"));
    }

    @Test
    void countTitlesByCompaniesReturnsEmptyForNullOrEmptyInput() {
        insertLabel("MIDA", "Moodyz");
        saveWithLocation(titleFull("MIDA-001", "MIDA", 1), "vol-a", "stars/library", "/p/MIDA-001");

        assertTrue(titleRepo.countTitlesByCompanies(List.of()).isEmpty());
        assertTrue(titleRepo.countTitlesByCompanies(null).isEmpty());
    }

    @Test
    void countTitlesByCompaniesIsCaseInsensitiveOnLabelCode() {
        insertLabel("MIDA", "Moodyz");
        // Title stored with lowercase label code — join is upper(l.code) = upper(t.label).
        saveWithLocation(titleFull("mida-001", "mida", 1), "vol-a", "stars/library", "/p/mida-001");

        var counts = titleRepo.countTitlesByCompanies(List.of("Moodyz"));
        assertEquals(1L, counts.get("Moodyz"));
    }

    // --- allBaseCodes ---

    @Test
    void allBaseCodesReturnsEveryBaseCodeFromTitlesTable() {
        titleRepo.save(Title.builder().code("ABP-123").baseCode("ABP-00123").label("ABP").seqNum(123).build());
        titleRepo.save(Title.builder().code("XYZ-1").baseCode("XYZ-00001").label("XYZ").seqNum(1).build());

        Set<String> codes = titleRepo.allBaseCodes();
        assertEquals(Set.of("ABP-00123", "XYZ-00001"), codes);
    }

    @Test
    void allBaseCodesReturnsEmptyWhenNoTitles() {
        assertTrue(titleRepo.allBaseCodes().isEmpty());
    }

    // --- findByActressIds ---

    @Test
    void findByActressIdsReturnsEmptyMapForEmptyInput() {
        assertTrue(titleRepo.findByActressIds(List.of()).isEmpty());
    }

    @Test
    void findByActressIdsGroupsTitlesByActressId() {
        Title t1 = titleRepo.save(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).actressId(10L).build());
        Title t2 = titleRepo.save(Title.builder().code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2).actressId(10L).build());
        Title t3 = titleRepo.save(Title.builder().code("SSIS-001").baseCode("SSIS-00001").label("SSIS").seqNum(1).actressId(20L).build());

        Map<Long, List<Title>> result = titleRepo.findByActressIds(List.of(10L, 20L));

        assertEquals(2, result.get(10L).size());
        assertEquals(1, result.get(20L).size());
        assertFalse(result.containsKey(99L));
    }

    @Test
    void findByActressIdsIncludesTitleActressesJunctionRows() {
        Title t = titleRepo.save(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).build());
        // Link via junction table, not actress_id FK
        try (var stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t.getId() + ", 30)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<Long, List<Title>> result = titleRepo.findByActressIds(List.of(30L));

        assertEquals(1, result.get(30L).size());
        assertEquals("ABP-001", result.get(30L).get(0).getCode());
    }

    @Test
    void findByActressIdsExcludesActressesNotInInput() {
        titleRepo.save(Title.builder().code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1).actressId(10L).build());

        Map<Long, List<Title>> result = titleRepo.findByActressIds(List.of(20L));

        assertFalse(result.containsKey(10L));
    }

    // --- helpers ---

    /** Insert a label row mapping a label code to a company. */
    private void insertLabel(String code, String company) {
        try (var stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO labels (code, company) VALUES ('" + code + "', '" + company + "')");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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

    /** Build a title with explicit label and seqNum (no actress attribution). */
    private static Title titleFull(String code, String label, int seqNum) {
        return Title.builder()
                .code(code)
                .baseCode(String.format("%s-%05d", label, seqNum))
                .label(label)
                .seqNum(seqNum)
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

    // --- grade source methods ---

    @Test
    void setGradeFromEnrichmentWritesGradeAndSource() {
        Title t = titleRepo.save(titleFull("GRD-001", "GRD", 1));
        titleRepo.setGradeFromEnrichment(t.getId(), Actress.Grade.A_PLUS);

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t.getId()).mapToMap().one());
        assertEquals("A+", row.get("grade"));
        assertEquals("enrichment", row.get("grade_source"));
    }

    @Test
    void setGradeFromEnrichmentIsNoOpWhenManual() {
        Title t = titleRepo.save(titleFull("GRD-002", "GRD", 2));
        // Set manual grade first
        titleRepo.setGradeManual(t.getId(), Actress.Grade.SSS);
        // Enrichment should not overwrite
        titleRepo.setGradeFromEnrichment(t.getId(), Actress.Grade.B);

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t.getId()).mapToMap().one());
        assertEquals("SSS", row.get("grade"), "manual grade must not be overwritten by enrichment");
        assertEquals("manual", row.get("grade_source"));
    }

    @Test
    void setGradeManualAlwaysWins() {
        Title t = titleRepo.save(titleFull("GRD-003", "GRD", 3));
        titleRepo.setGradeFromEnrichment(t.getId(), Actress.Grade.A);
        titleRepo.setGradeManual(t.getId(), Actress.Grade.SSS);

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t.getId()).mapToMap().one());
        assertEquals("SSS", row.get("grade"));
        assertEquals("manual", row.get("grade_source"));
    }

    @Test
    void clearEnrichmentGradeClearsOnlyEnrichmentRows() {
        Title t1 = titleRepo.save(titleFull("GRD-004", "GRD", 4));
        Title t2 = titleRepo.save(titleFull("GRD-005", "GRD", 5));
        titleRepo.setGradeFromEnrichment(t1.getId(), Actress.Grade.S);
        titleRepo.setGradeManual(t2.getId(), Actress.Grade.SS);

        titleRepo.clearEnrichmentGrade(t1.getId());
        titleRepo.clearEnrichmentGrade(t2.getId()); // no-op: grade_source='manual'

        Map<String, Object> r1 = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t1.getId()).mapToMap().one());
        assertNull(r1.get("grade"), "enrichment grade should be cleared");
        assertNull(r1.get("grade_source"));

        Map<String, Object> r2 = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t2.getId()).mapToMap().one());
        assertEquals("SS", r2.get("grade"), "manual grade must not be touched by clearEnrichmentGrade");
        assertEquals("manual", r2.get("grade_source"));
    }

    @Test
    void enrichTitleSetsGradeSourceAi() {
        Title t = titleRepo.save(titleFull("GRD-006", "GRD", 6));
        titleRepo.enrichTitle(t.getId(), "タイトル", null, null, null, Actress.Grade.A_MINUS);

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t.getId()).mapToMap().one());
        assertEquals("A-", row.get("grade"));
        assertEquals("ai", row.get("grade_source"));
    }

    @Test
    void enrichTitleNullGradeDoesNotChangeGradeSource() {
        Title t = titleRepo.save(titleFull("GRD-007", "GRD", 7));
        titleRepo.setGradeFromEnrichment(t.getId(), Actress.Grade.A);
        // enrich with null grade — should not clobber grade_source
        titleRepo.enrichTitle(t.getId(), "タイトル", null, null, null, null);

        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT grade, grade_source FROM titles WHERE id = ?")
                        .bind(0, t.getId()).mapToMap().one());
        assertNull(row.get("grade"), "null grade should clear the grade column");
        assertEquals("enrichment", row.get("grade_source"), "grade_source must not change when enrichTitle grade is null");
    }
}
