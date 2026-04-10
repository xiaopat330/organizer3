package com.organizer3.repository.jdbi;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiActressRepository using an in-memory SQLite database.
 * Each test gets a fresh schema — no shared state between tests.
 */
class JdbiActressRepositoryTest {

    private JdbiActressRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        // Bind JDBI to a single connection so the in-memory SQLite DB persists across handles
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiActressRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- save / findById ---

    @Test
    void saveNewActressAssignsId() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertNotNull(saved.getId());
        assertTrue(saved.getId() > 0);
    }

    @Test
    void findByIdReturnsActress() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        Optional<Actress> found = repo.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Aya Sazanami", found.get().getCanonicalName());
    }

    @Test
    void findByIdReturnsEmptyForMissingId() {
        assertTrue(repo.findById(999L).isEmpty());
    }

    // --- findByCanonicalName ---

    @Test
    void findByCanonicalNameMatchesExactName() {
        repo.save(actress("Hibiki Otsuki"));
        assertTrue(repo.findByCanonicalName("Hibiki Otsuki").isPresent());
        assertTrue(repo.findByCanonicalName("Eri Ando").isEmpty());
    }

    // --- resolveByName ---

    @Test
    void resolveByNameFindsCanonicalDirectly() {
        repo.save(actress("Aya Sazanami"));
        Optional<Actress> result = repo.resolveByName("Aya Sazanami");
        assertTrue(result.isPresent());
        assertEquals("Aya Sazanami", result.get().getCanonicalName());
    }

    @Test
    void resolveByNameFollowsAliasToCanonical() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.saveAlias(new ActressAlias(saved.getId(), "Haruka Suzumiya"));

        Optional<Actress> result = repo.resolveByName("Haruka Suzumiya");
        assertTrue(result.isPresent());
        assertEquals("Aya Sazanami", result.get().getCanonicalName());
    }

    @Test
    void resolveByNameReturnsEmptyForUnknownName() {
        assertTrue(repo.resolveByName("Nobody Known").isEmpty());
    }

    // --- updateTier ---

    @Test
    void updateTierPersistsTierChange() {
        Actress saved = repo.save(actress("Yua Mikami"));
        repo.updateTier(saved.getId(), Actress.Tier.GODDESS);
        Actress reloaded = repo.findById(saved.getId()).orElseThrow();
        assertEquals(Actress.Tier.GODDESS, reloaded.getTier());
    }

    // --- alias operations ---

    @Test
    void findAliasesReturnsAllAliasesForActress() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.saveAlias(new ActressAlias(saved.getId(), "Haruka Suzumiya"));
        repo.saveAlias(new ActressAlias(saved.getId(), "Aya Konami"));

        List<ActressAlias> aliases = repo.findAliases(saved.getId());
        assertEquals(2, aliases.size());
    }

    @Test
    void deleteAliasRemovesOnlyTargetAlias() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.saveAlias(new ActressAlias(saved.getId(), "Haruka Suzumiya"));
        repo.saveAlias(new ActressAlias(saved.getId(), "Aya Konami"));

        repo.deleteAlias(saved.getId(), "Haruka Suzumiya");

        List<ActressAlias> aliases = repo.findAliases(saved.getId());
        assertEquals(1, aliases.size());
        assertEquals("Aya Konami", aliases.get(0).aliasName());
    }

    @Test
    void replaceAllAliasesSwapsAliasesAtomically() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.saveAlias(new ActressAlias(saved.getId(), "Old Alias"));

        repo.replaceAllAliases(saved.getId(), List.of("Haruka Suzumiya", "Aya Konami"));

        List<ActressAlias> aliases = repo.findAliases(saved.getId());
        assertEquals(2, aliases.size());
        assertTrue(aliases.stream().noneMatch(a -> a.aliasName().equals("Old Alias")));
    }

    // --- importFromYaml ---

    @Test
    void importFromYamlCreatesNewActressesAndAliases() {
        List<AliasYamlEntry> entries = List.of(
                new AliasYamlEntry("Aya Sazanami", List.of("Haruka Suzumiya", "Aya Konami")),
                new AliasYamlEntry("Hibiki Otsuki", List.of("Eri Ando"))
        );

        repo.importFromYaml(entries);

        assertTrue(repo.findByCanonicalName("Aya Sazanami").isPresent());
        assertTrue(repo.findByCanonicalName("Hibiki Otsuki").isPresent());
        assertEquals("Aya Sazanami", repo.resolveByName("Haruka Suzumiya").orElseThrow().getCanonicalName());
        assertEquals("Hibiki Otsuki", repo.resolveByName("Eri Ando").orElseThrow().getCanonicalName());
    }

    @Test
    void importFromYamlReplacesExistingAliasesOnReimport() {
        repo.importFromYaml(List.of(
                new AliasYamlEntry("Aya Sazanami", List.of("Old Alias"))
        ));
        repo.importFromYaml(List.of(
                new AliasYamlEntry("Aya Sazanami", List.of("Haruka Suzumiya", "Aya Konami"))
        ));

        List<ActressAlias> aliases = repo.findAliases(
                repo.findByCanonicalName("Aya Sazanami").orElseThrow().getId());
        assertEquals(2, aliases.size());
        assertTrue(aliases.stream().noneMatch(a -> a.aliasName().equals("Old Alias")));
    }

    // --- findByTier ---

    @Test
    void findByTierReturnsOnlyMatchingTier() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));
        repo.save(actress("Yua Mikami", Actress.Tier.GODDESS));
        repo.save(actress("Hibiki Otsuki", Actress.Tier.GODDESS));

        List<Actress> goddesses = repo.findByTier(Actress.Tier.GODDESS);
        assertEquals(2, goddesses.size());
        assertTrue(goddesses.stream().anyMatch(a -> a.getCanonicalName().equals("Yua Mikami")));
        assertTrue(goddesses.stream().anyMatch(a -> a.getCanonicalName().equals("Hibiki Otsuki")));
    }

    @Test
    void findByTierReturnsEmptyWhenNoneMatch() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));
        assertTrue(repo.findByTier(Actress.Tier.SUPERSTAR).isEmpty());
    }

    @Test
    void findByTierResultsOrderedByName() {
        repo.save(actress("Yua Mikami", Actress.Tier.GODDESS));
        repo.save(actress("Aya Sazanami", Actress.Tier.GODDESS));

        List<Actress> result = repo.findByTier(Actress.Tier.GODDESS);
        assertEquals("Aya Sazanami", result.get(0).getCanonicalName());
        assertEquals("Yua Mikami", result.get(1).getCanonicalName());
    }

    // --- findByFirstNamePrefixPaged ---

    @Test
    void findByFirstNamePrefixPagedReturnsMatchingActresses() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));
        repo.save(actress("Airi Suzumura", Actress.Tier.GODDESS));
        repo.save(actress("Yua Mikami", Actress.Tier.GODDESS));

        List<Actress> result = repo.findByFirstNamePrefixPaged("A", null, 10, 0);
        assertEquals(2, result.size());
        assertEquals("Airi Suzumura", result.get(0).getCanonicalName());
        assertEquals("Aya Sazanami", result.get(1).getCanonicalName());
    }

    @Test
    void findByFirstNamePrefixPagedFiltersByTier() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));
        repo.save(actress("Airi Suzumura", Actress.Tier.GODDESS));
        repo.save(actress("Aino Kishi", Actress.Tier.POPULAR));

        List<Actress> goddesses = repo.findByFirstNamePrefixPaged("A", Actress.Tier.GODDESS, 10, 0);
        assertEquals(1, goddesses.size());
        assertEquals("Airi Suzumura", goddesses.get(0).getCanonicalName());
    }

    @Test
    void findByFirstNamePrefixPagedRespectsLimitAndOffset() {
        repo.save(actress("Aino Kishi", Actress.Tier.LIBRARY));
        repo.save(actress("Airi Suzumura", Actress.Tier.LIBRARY));
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));

        List<Actress> page1 = repo.findByFirstNamePrefixPaged("A", null, 2, 0);
        assertEquals(2, page1.size());
        assertEquals("Aino Kishi", page1.get(0).getCanonicalName());

        List<Actress> page2 = repo.findByFirstNamePrefixPaged("A", null, 2, 2);
        assertEquals(1, page2.size());
        assertEquals("Aya Sazanami", page2.get(0).getCanonicalName());
    }

    @Test
    void findByFirstNamePrefixPagedReturnsEmptyWhenNoTierMatch() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));
        repo.save(actress("Airi Suzumura", Actress.Tier.POPULAR));

        assertTrue(repo.findByFirstNamePrefixPaged("A", Actress.Tier.GODDESS, 10, 0).isEmpty());
    }

    // --- countByFirstNamePrefixGroupedByTier ---

    @Test
    void countByPrefixGroupedByTierReturnsTierCounts() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));
        repo.save(actress("Airi Suzumura", Actress.Tier.GODDESS));
        repo.save(actress("Aino Kishi", Actress.Tier.GODDESS));
        repo.save(actress("Yua Mikami", Actress.Tier.GODDESS));

        Map<String, Integer> counts = repo.countByFirstNamePrefixGroupedByTier("A");
        assertEquals(1, counts.get("LIBRARY"));
        assertEquals(2, counts.get("GODDESS"));
        assertFalse(counts.containsKey("SUPERSTAR"));
    }

    @Test
    void countByPrefixGroupedByTierReturnsEmptyForUnmatchedPrefix() {
        repo.save(actress("Aya Sazanami", Actress.Tier.LIBRARY));

        Map<String, Integer> counts = repo.countByFirstNamePrefixGroupedByTier("Z");
        assertTrue(counts.isEmpty());
    }

    // --- toggleBookmark ---

    @Test
    void toggleBookmarkMarksBookmark() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertFalse(repo.findById(saved.getId()).orElseThrow().isBookmark());

        repo.toggleBookmark(saved.getId(), true);
        assertTrue(repo.findById(saved.getId()).orElseThrow().isBookmark());
    }

    @Test
    void toggleBookmarkUnmarksBookmark() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.toggleBookmark(saved.getId(), true);
        repo.toggleBookmark(saved.getId(), false);
        assertFalse(repo.findById(saved.getId()).orElseThrow().isBookmark());
    }

    @Test
    void savedActressDefaultsToNotBookmark() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertFalse(saved.isBookmark());
    }

    @Test
    void toggleBookmarkStampsBookmarkedAtWhenSettingTrue() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());

        repo.toggleBookmark(saved.getId(), true);

        Actress after = repo.findById(saved.getId()).orElseThrow();
        assertNotNull(after.getBookmarkedAt());
        // Stamped within the last few seconds of "now".
        assertTrue(after.getBookmarkedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    void toggleBookmarkClearsBookmarkedAtWhenSettingFalse() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.toggleBookmark(saved.getId(), true);
        assertNotNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());

        repo.toggleBookmark(saved.getId(), false);

        assertNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());
    }

    @Test
    void setFlagsStampsBookmarkedAtWhenBookmarkBecomesTrue() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());

        repo.setFlags(saved.getId(), true, true, false);

        assertNotNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());
    }

    @Test
    void setFlagsClearsBookmarkedAtWhenBookmarkBecomesFalse() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.setFlags(saved.getId(), false, true, false);
        assertNotNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());

        repo.setFlags(saved.getId(), false, false, false);

        assertNull(repo.findById(saved.getId()).orElseThrow().getBookmarkedAt());
    }

    // --- setGrade ---

    @Test
    void setGradePersistsGrade() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertNull(repo.findById(saved.getId()).orElseThrow().getGrade());

        repo.setGrade(saved.getId(), Actress.Grade.A_PLUS);
        assertEquals(Actress.Grade.A_PLUS, repo.findById(saved.getId()).orElseThrow().getGrade());
    }

    @Test
    void setGradeClearsGrade() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.setGrade(saved.getId(), Actress.Grade.S);
        repo.setGrade(saved.getId(), null);
        assertNull(repo.findById(saved.getId()).orElseThrow().getGrade());
    }

    @Test
    void setGradeAllGradesRoundTrip() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        for (Actress.Grade grade : Actress.Grade.values()) {
            repo.setGrade(saved.getId(), grade);
            assertEquals(grade, repo.findById(saved.getId()).orElseThrow().getGrade());
        }
    }

    @Test
    void savedActressDefaultsToNullGrade() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertNull(saved.getGrade());
    }

    // --- toggleRejected ---

    @Test
    void toggleRejectedMarksRejected() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertFalse(repo.findById(saved.getId()).orElseThrow().isRejected());

        repo.toggleRejected(saved.getId(), true);
        assertTrue(repo.findById(saved.getId()).orElseThrow().isRejected());
    }

    @Test
    void toggleRejectedUnmarksRejected() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.toggleRejected(saved.getId(), true);
        repo.toggleRejected(saved.getId(), false);
        assertFalse(repo.findById(saved.getId()).orElseThrow().isRejected());
    }

    @Test
    void savedActressDefaultsToNotRejected() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertFalse(saved.isRejected());
    }

    // --- setFlags ---

    @Test
    void setFlagsWritesAllThreeFlagsInOneUpdate() {
        Actress saved = repo.save(actress("Aya Sazanami"));

        repo.setFlags(saved.getId(), true, true, false);

        Actress after = repo.findById(saved.getId()).orElseThrow();
        assertTrue(after.isFavorite());
        assertTrue(after.isBookmark());
        assertFalse(after.isRejected());
    }

    @Test
    void setFlagsCanClearAllThreeFlags() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.setFlags(saved.getId(), true, true, false);

        repo.setFlags(saved.getId(), false, false, true);

        Actress after = repo.findById(saved.getId()).orElseThrow();
        assertFalse(after.isFavorite());
        assertFalse(after.isBookmark());
        assertTrue(after.isRejected());
    }

    // --- toggleFavorite / findFavorites ---

    @Test
    void toggleFavoriteMarksFavorite() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertFalse(repo.findById(saved.getId()).orElseThrow().isFavorite());

        repo.toggleFavorite(saved.getId(), true);
        assertTrue(repo.findById(saved.getId()).orElseThrow().isFavorite());
    }

    @Test
    void toggleFavoriteUnmarksFavorite() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.toggleFavorite(saved.getId(), true);
        repo.toggleFavorite(saved.getId(), false);
        assertFalse(repo.findById(saved.getId()).orElseThrow().isFavorite());
    }

    @Test
    void findFavoritesReturnsOnlyFavoritedActresses() {
        Actress aya = repo.save(actress("Aya Sazanami"));
        repo.save(actress("Hibiki Otsuki"));
        repo.toggleFavorite(aya.getId(), true);

        List<Actress> favorites = repo.findFavorites();
        assertEquals(1, favorites.size());
        assertEquals("Aya Sazanami", favorites.get(0).getCanonicalName());
    }

    @Test
    void findFavoritesReturnsEmptyWhenNoneFavorited() {
        repo.save(actress("Aya Sazanami"));
        assertTrue(repo.findFavorites().isEmpty());
    }

    @Test
    void savedActressDefaultsToNotFavorite() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertFalse(saved.isFavorite());
    }

    // --- searchByNamePrefix ---

    @Test
    void searchByNamePrefixMatchesFirstName() {
        repo.save(actress("Aya Sazanami"));
        repo.save(actress("Aino Kishi"));
        repo.save(actress("Yua Mikami"));

        List<Actress> results = repo.searchByNamePrefix("ay");
        assertEquals(1, results.size());
        assertEquals("Aya Sazanami", results.get(0).getCanonicalName());
    }

    @Test
    void searchByNamePrefixMatchesLastName() {
        repo.save(actress("Aya Sazanami"));
        repo.save(actress("Hibiki Otsuki"));

        List<Actress> results = repo.searchByNamePrefix("saz");
        assertEquals(1, results.size());
        assertEquals("Aya Sazanami", results.get(0).getCanonicalName());
    }

    @Test
    void searchByNamePrefixIsCaseInsensitive() {
        repo.save(actress("Aya Sazanami"));

        assertEquals(1, repo.searchByNamePrefix("AY").size());
        assertEquals(1, repo.searchByNamePrefix("ay").size());
        assertEquals(1, repo.searchByNamePrefix("Ay").size());
        assertEquals(1, repo.searchByNamePrefix("SAZ").size());
    }

    @Test
    void searchByNamePrefixMatchesMultiple() {
        repo.save(actress("Aino Kishi"));    // first name starts with "ai"
        repo.save(actress("Hibiki Aizawa")); // last name starts with "ai"
        repo.save(actress("Yua Mikami"));

        List<Actress> results = repo.searchByNamePrefix("ai");
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(a -> a.getCanonicalName().equals("Aino Kishi")));
        assertTrue(results.stream().anyMatch(a -> a.getCanonicalName().equals("Hibiki Aizawa")));
    }

    @Test
    void searchByNamePrefixReturnsEmptyWhenNoMatch() {
        repo.save(actress("Aya Sazanami"));
        assertTrue(repo.searchByNamePrefix("xyz").isEmpty());
    }

    @Test
    void searchByNamePrefixResultsOrderedByName() {
        repo.save(actress("Hibiki Aizawa")); // last name starts with "ai"
        repo.save(actress("Aino Kishi"));    // first name starts with "ai"

        List<Actress> results = repo.searchByNamePrefix("ai");
        assertEquals("Aino Kishi", results.get(0).getCanonicalName());
        assertEquals("Hibiki Aizawa", results.get(1).getCanonicalName());
    }

    // --- searchByNamePrefixPaged ---

    @Test
    void searchByNamePrefixPagedMatchesFirstAndLastNames() {
        repo.save(actress("Aino Kishi"));    // first name starts with "ai"
        repo.save(actress("Hibiki Aizawa")); // last name  starts with "ai"
        repo.save(actress("Yua Mikami"));    // no match

        List<Actress> results = repo.searchByNamePrefixPaged("ai", 10, 0);
        assertEquals(2, results.size());
        assertEquals("Aino Kishi", results.get(0).getCanonicalName());
        assertEquals("Hibiki Aizawa", results.get(1).getCanonicalName());
    }

    @Test
    void searchByNamePrefixPagedHonorsLimitAndOffset() {
        repo.save(actress("Aya Sazanami"));
        repo.save(actress("Aino Kishi"));
        repo.save(actress("Ayumi Shinoda"));

        List<Actress> page1 = repo.searchByNamePrefixPaged("a", 2, 0);
        List<Actress> page2 = repo.searchByNamePrefixPaged("a", 2, 2);

        assertEquals(2, page1.size());
        assertEquals(1, page2.size());
        assertEquals("Aino Kishi", page1.get(0).getCanonicalName());
        assertEquals("Aya Sazanami", page1.get(1).getCanonicalName());
        assertEquals("Ayumi Shinoda", page2.get(0).getCanonicalName());
    }

    @Test
    void searchByNamePrefixPagedIsCaseInsensitive() {
        repo.save(actress("Aya Sazanami"));
        assertEquals(1, repo.searchByNamePrefixPaged("AY",  10, 0).size());
        assertEquals(1, repo.searchByNamePrefixPaged("saz", 10, 0).size());
    }

    @Test
    void searchByNamePrefixPagedOrdersFavoritesFirstThenBookmarksThenName() {
        Actress aya   = repo.save(actress("Aya Sazanami"));
        repo.save(actress("Aino Kishi"));
        Actress ayumi = repo.save(actress("Ayumi Shinoda"));
        repo.save(actress("Akari Mitani"));

        repo.toggleFavorite(ayumi.getId(), true); // favorite → first
        repo.toggleBookmark(aya.getId(),   true); // bookmark → second
        // aino & akari: no flags → alphabetic within their group

        List<Actress> results = repo.searchByNamePrefixPaged("a", 10, 0);
        assertEquals(4, results.size());
        assertEquals("Ayumi Shinoda", results.get(0).getCanonicalName()); // favorite
        assertEquals("Aya Sazanami",  results.get(1).getCanonicalName()); // bookmark
        assertEquals("Aino Kishi",    results.get(2).getCanonicalName()); // plain, alpha
        assertEquals("Akari Mitani",  results.get(3).getCanonicalName());
    }

    @Test
    void searchByNamePrefixPagedCompoundMatchesFirstAndLastPrefix() {
        repo.save(actress("Aya Sazanami"));   // first=Aya, last starts with "Sa" ✓
        repo.save(actress("Aya Kishi"));      // first=Aya but last doesn't start with "Sa"
        repo.save(actress("Sakura Nomiya"));  // first starts with "Sa" but not "Ay"

        List<Actress> results = repo.searchByNamePrefixPaged("Ay Sa", 10, 0);
        assertEquals(1, results.size());
        assertEquals("Aya Sazanami", results.get(0).getCanonicalName());
    }

    @Test
    void searchByNamePrefixPagedCompoundMatchesAnyLaterWord() {
        // Compound should match any word *after* the first that starts with the second token
        repo.save(actress("Maria de la Cruz"));

        List<Actress> results = repo.searchByNamePrefixPaged("Ma de", 10, 0);
        assertEquals(1, results.size());
        assertEquals("Maria de la Cruz", results.get(0).getCanonicalName());
    }

    @Test
    void searchByNamePrefixPagedCompoundHonorsFavoritesOrdering() {
        repo.save(actress("Aya Sazanami"));
        Actress b = repo.save(actress("Aya Sato"));
        repo.toggleFavorite(b.getId(), true);

        List<Actress> results = repo.searchByNamePrefixPaged("Ay Sa", 10, 0);
        assertEquals(2, results.size());
        assertEquals("Aya Sato",       results.get(0).getCanonicalName()); // favorite first
        assertEquals("Aya Sazanami",   results.get(1).getCanonicalName());
    }

    @Test
    void searchByNamePrefixPagedTrailingSpaceFallsBackToSingleToken() {
        repo.save(actress("Aya Sazanami"));
        // "Aya " (trailing space) should behave like the single-token form.
        List<Actress> results = repo.searchByNamePrefixPaged("Aya ", 10, 0);
        assertEquals(1, results.size());
        assertEquals("Aya Sazanami", results.get(0).getCanonicalName());
    }

    // --- findBookmarksPaged ---

    @Test
    void findBookmarksPagedReturnsOnlyBookmarkedActresses() {
        Actress a = repo.save(actress("Aya Sazanami"));
        Actress b = repo.save(actress("Hibiki Otsuki"));
        repo.save(actress("Yua Mikami"));

        repo.toggleBookmark(a.getId(), true);
        repo.toggleBookmark(b.getId(), true);

        List<Actress> results = repo.findBookmarksPaged(10, 0);
        assertEquals(2, results.size());
        assertEquals("Aya Sazanami", results.get(0).getCanonicalName());
        assertEquals("Hibiki Otsuki", results.get(1).getCanonicalName());
    }

    @Test
    void findBookmarksPagedHonorsLimitAndOffset() {
        Actress a = repo.save(actress("Aya Sazanami"));
        Actress b = repo.save(actress("Hibiki Otsuki"));
        Actress c = repo.save(actress("Yua Mikami"));
        repo.toggleBookmark(a.getId(), true);
        repo.toggleBookmark(b.getId(), true);
        repo.toggleBookmark(c.getId(), true);

        List<Actress> page1 = repo.findBookmarksPaged(2, 0);
        List<Actress> page2 = repo.findBookmarksPaged(2, 2);
        assertEquals(2, page1.size());
        assertEquals(1, page2.size());
        assertEquals("Yua Mikami", page2.get(0).getCanonicalName());
    }

    @Test
    void findBookmarksPagedReturnsEmptyWhenNoneBookmarked() {
        repo.save(actress("Aya Sazanami"));
        assertTrue(repo.findBookmarksPaged(10, 0).isEmpty());
    }

    // --- updateProfile ---

    @Test
    void updateProfilePersistsEnrichmentFields() {
        Actress saved = repo.save(actress("Nana Ogura"));

        repo.updateProfile(saved.getId(), "小倉奈々",
                LocalDate.of(1990, 1, 10), "Kanagawa, Japan", "O",
                160, 88, 59, 90, "F",
                LocalDate.of(2010, 7, 9), LocalDate.of(2014, 7, 11),
                "Biography text.", "Legacy text.");

        Actress enriched = repo.findById(saved.getId()).orElseThrow();
        assertEquals("小倉奈々", enriched.getStageName());
        assertEquals(LocalDate.of(1990, 1, 10), enriched.getDateOfBirth());
        assertEquals("Kanagawa, Japan", enriched.getBirthplace());
        assertEquals("O", enriched.getBloodType());
        assertEquals(160, enriched.getHeightCm());
        assertEquals(88, enriched.getBust());
        assertEquals(59, enriched.getWaist());
        assertEquals(90, enriched.getHip());
        assertEquals("F", enriched.getCup());
        assertEquals(LocalDate.of(2010, 7, 9), enriched.getActiveFrom());
        assertEquals(LocalDate.of(2014, 7, 11), enriched.getActiveTo());
        assertEquals("Biography text.", enriched.getBiography());
        assertEquals("Legacy text.", enriched.getLegacy());
    }

    @Test
    void updateProfileDoesNotTouchOperationalFields() {
        Actress saved = repo.save(actress("Nana Ogura", Actress.Tier.SUPERSTAR));
        repo.setGrade(saved.getId(), Actress.Grade.SS);
        repo.toggleFavorite(saved.getId(), true);

        repo.updateProfile(saved.getId(), "小倉奈々",
                null, null, null, null, null, null, null, null,
                null, null, null, null);

        Actress after = repo.findById(saved.getId()).orElseThrow();
        assertEquals(Actress.Tier.SUPERSTAR, after.getTier());
        assertEquals(Actress.Grade.SS, after.getGrade());
        assertTrue(after.isFavorite());
    }

    // --- recalcTiers ---

    @Test
    void recalcTiersAssignsCorrectTierByTitleCount() throws Exception {
        Actress library  = repo.save(actress("Library Star"));    // 0 titles  → LIBRARY
        Actress minor    = repo.save(actress("Minor Star"));      // 5 titles  → MINOR
        Actress popular  = repo.save(actress("Popular Star"));    // 20 titles → POPULAR
        Actress superstar = repo.save(actress("Super Star"));     // 50 titles → SUPERSTAR
        Actress goddess  = repo.save(actress("Goddess Star"));    // 100 titles → GODDESS

        insertTitlesForActress(minor.getId(),     5);
        insertTitlesForActress(popular.getId(),  20);
        insertTitlesForActress(superstar.getId(), 50);
        insertTitlesForActress(goddess.getId(),  100);

        repo.recalcTiers();

        assertEquals(Actress.Tier.LIBRARY,   repo.findById(library.getId()).orElseThrow().getTier());
        assertEquals(Actress.Tier.MINOR,     repo.findById(minor.getId()).orElseThrow().getTier());
        assertEquals(Actress.Tier.POPULAR,   repo.findById(popular.getId()).orElseThrow().getTier());
        assertEquals(Actress.Tier.SUPERSTAR, repo.findById(superstar.getId()).orElseThrow().getTier());
        assertEquals(Actress.Tier.GODDESS,   repo.findById(goddess.getId()).orElseThrow().getTier());
    }

    @Test
    void recalcTiersReturnsRowCount() throws Exception {
        Actress a = repo.save(actress("Some Actress"));
        insertTitlesForActress(a.getId(), 10);
        int updated = repo.recalcTiers();
        assertTrue(updated >= 1);
    }

    /** Inserts {@code count} distinct title rows and links them to {@code actressId}. */
    private void insertTitlesForActress(long actressId, int count) throws Exception {
        try (var stmt = connection.createStatement()) {
            for (int i = 0; i < count; i++) {
                String code = "T-" + actressId + "-" + i;
                stmt.execute("INSERT INTO titles (code) VALUES ('" + code + "')");
                long titleId = stmt.getGeneratedKeys().getLong(1);
                stmt.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + titleId + ", " + actressId + ")");
            }
        }
    }

    // --- recordVisit ---

    @Test
    void recordVisitIncrementsCountFromZero() {
        Actress saved = repo.save(actress("Aya Sazanami"));
        assertEquals(0, repo.findById(saved.getId()).get().getVisitCount());

        repo.recordVisit(saved.getId());

        Actress updated = repo.findById(saved.getId()).get();
        assertEquals(1, updated.getVisitCount());
        assertNotNull(updated.getLastVisitedAt());
    }

    @Test
    void recordVisitAccumulatesAcrossMultipleCalls() {
        Actress saved = repo.save(actress("Hibiki Otsuki"));

        repo.recordVisit(saved.getId());
        repo.recordVisit(saved.getId());
        repo.recordVisit(saved.getId());

        assertEquals(3, repo.findById(saved.getId()).get().getVisitCount());
    }

    @Test
    void recordVisitUpdatesLastVisitedAt() throws Exception {
        Actress saved = repo.save(actress("Aya Sazanami"));
        repo.recordVisit(saved.getId());
        var first = repo.findById(saved.getId()).get().getLastVisitedAt();

        Thread.sleep(10);
        repo.recordVisit(saved.getId());
        var second = repo.findById(saved.getId()).get().getLastVisitedAt();

        assertTrue(second.isAfter(first));
    }

    // --- Dashboard module queries ----------------------------------------------

    // findSpotlightCandidates

    @Test
    void findSpotlightCandidatesIncludesFavoritesGradedAndElites() {
        Actress fav    = repo.save(actress("Fav Actress"));
        Actress graded = repo.save(actress("Graded Actress"));
        Actress goddess = repo.save(actress("Goddess", Actress.Tier.GODDESS));
        Actress plain  = repo.save(actress("Plain"));   // should NOT appear

        repo.toggleFavorite(fav.getId(), true);
        repo.setGrade(graded.getId(), Actress.Grade.A);

        List<Actress> result = repo.findSpotlightCandidates(
                java.util.Set.of(Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS), 100, java.util.Set.of());
        List<String> names = result.stream().map(Actress::getCanonicalName).toList();
        assertTrue(names.contains("Fav Actress"));
        assertTrue(names.contains("Graded Actress"));
        assertTrue(names.contains("Goddess"));
        assertFalse(names.contains("Plain"));
    }

    @Test
    void findSpotlightCandidatesExcludesRejected() {
        Actress fav = repo.save(actress("Fav"));
        repo.toggleFavorite(fav.getId(), true);
        repo.toggleRejected(fav.getId(), true);

        List<Actress> result = repo.findSpotlightCandidates(java.util.Set.of(), 10, java.util.Set.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void findSpotlightCandidatesRespectsExcludeIds() {
        Actress fav1 = repo.save(actress("F1"));
        Actress fav2 = repo.save(actress("F2"));
        repo.toggleFavorite(fav1.getId(), true);
        repo.toggleFavorite(fav2.getId(), true);

        List<Actress> result = repo.findSpotlightCandidates(
                java.util.Set.of(), 10, java.util.Set.of(fav1.getId()));
        List<Long> ids = result.stream().map(Actress::getId).toList();
        assertFalse(ids.contains(fav1.getId()));
        assertTrue(ids.contains(fav2.getId()));
    }

    // findBirthdaysToday

    @Test
    void findBirthdaysTodayMatchesByMonthDayIgnoringYear() {
        // Both same month-day, different years.
        Actress a = repo.save(actress("Birthday A"));
        Actress b = repo.save(actress("Birthday B"));
        Actress c = repo.save(actress("Other Day"));
        repo.save(actress("No DOB"));
        setDob(a.getId(), LocalDate.of(1990, 4, 10));
        setDob(b.getId(), LocalDate.of(1985, 4, 10));
        setDob(c.getId(), LocalDate.of(1990, 4, 11));

        List<Actress> result = repo.findBirthdaysToday(4, 10, 10);
        List<String> names = result.stream().map(Actress::getCanonicalName).toList();
        assertEquals(2, names.size());
        assertTrue(names.contains("Birthday A"));
        assertTrue(names.contains("Birthday B"));
    }

    @Test
    void findBirthdaysTodayExcludesRejected() {
        Actress a = repo.save(actress("Rejected B-day"));
        setDob(a.getId(), LocalDate.of(1990, 4, 10));
        repo.toggleRejected(a.getId(), true);

        assertTrue(repo.findBirthdaysToday(4, 10, 10).isEmpty());
    }

    @Test
    void findBirthdaysTodayOrdersFavoritesAndElitesFirst() {
        Actress plain = repo.save(actress("Plain"));
        Actress goddess = repo.save(actress("Goddess", Actress.Tier.GODDESS));
        Actress fav = repo.save(actress("Fav"));
        setDob(plain.getId(), LocalDate.of(1990, 4, 10));
        setDob(goddess.getId(), LocalDate.of(1990, 4, 10));
        setDob(fav.getId(), LocalDate.of(1990, 4, 10));
        repo.toggleFavorite(fav.getId(), true);

        List<Actress> result = repo.findBirthdaysToday(4, 10, 10);
        // Expected order: favorites first → then by tier (GODDESS before LIBRARY).
        assertEquals("Fav",     result.get(0).getCanonicalName());
        assertEquals("Goddess", result.get(1).getCanonicalName());
        assertEquals("Plain",   result.get(2).getCanonicalName());
    }

    // findNewFaces

    @Test
    void findNewFacesReturnsActressesAfterSinceOrderedByFirstSeenDesc() {
        repo.save(builder("Old").firstSeenAt(LocalDate.of(2024, 1, 1)).build());
        repo.save(builder("Recent").firstSeenAt(LocalDate.now().minusDays(5)).build());
        repo.save(builder("Fresh").firstSeenAt(LocalDate.now().minusDays(1)).build());

        List<Actress> result = repo.findNewFaces(LocalDate.now().minusDays(30), 10, java.util.Set.of());
        assertEquals(2, result.size());
        assertEquals("Fresh",  result.get(0).getCanonicalName());
        assertEquals("Recent", result.get(1).getCanonicalName());
    }

    @Test
    void findNewFacesRespectsExcludeIds() {
        Actress fresh = repo.save(builder("Fresh").firstSeenAt(LocalDate.now().minusDays(1)).build());
        repo.save(builder("Other").firstSeenAt(LocalDate.now().minusDays(2)).build());

        List<Actress> result = repo.findNewFaces(
                LocalDate.now().minusDays(30), 10, java.util.Set.of(fresh.getId()));
        assertEquals(1, result.size());
        assertEquals("Other", result.get(0).getCanonicalName());
    }

    @Test
    void findNewFacesFallbackReturnsNewestOverall() {
        repo.save(builder("A").firstSeenAt(LocalDate.of(2020, 1, 1)).build());
        repo.save(builder("B").firstSeenAt(LocalDate.of(2024, 1, 1)).build());
        repo.save(builder("C").firstSeenAt(LocalDate.of(2025, 1, 1)).build());

        List<Actress> result = repo.findNewFacesFallback(2, java.util.Set.of());
        assertEquals(2, result.size());
        assertEquals("C", result.get(0).getCanonicalName());
        assertEquals("B", result.get(1).getCanonicalName());
    }

    // findBookmarksOrderedByBookmarkedAt

    @Test
    void findBookmarksOrderedByBookmarkedAtReturnsMostRecentlyBookmarkedFirst() throws Exception {
        Actress a = repo.save(actress("First"));
        repo.toggleBookmark(a.getId(), true);
        Thread.sleep(10);
        Actress b = repo.save(actress("Second"));
        repo.toggleBookmark(b.getId(), true);
        Thread.sleep(10);
        Actress c = repo.save(actress("Third"));
        repo.toggleBookmark(c.getId(), true);

        List<Actress> result = repo.findBookmarksOrderedByBookmarkedAt(10, java.util.Set.of());
        assertEquals(3, result.size());
        assertEquals("Third",  result.get(0).getCanonicalName());
        assertEquals("Second", result.get(1).getCanonicalName());
        assertEquals("First",  result.get(2).getCanonicalName());
    }

    @Test
    void findBookmarksOrderedByBookmarkedAtExcludesNonBookmarkedAndRejected() {
        Actress a = repo.save(actress("Bookmarked"));
        repo.toggleBookmark(a.getId(), true);
        Actress b = repo.save(actress("Not bookmarked"));
        Actress c = repo.save(actress("Bookmarked but rejected"));
        repo.toggleBookmark(c.getId(), true);
        repo.toggleRejected(c.getId(), true);

        List<Actress> result = repo.findBookmarksOrderedByBookmarkedAt(10, java.util.Set.of());
        assertEquals(1, result.size());
        assertEquals("Bookmarked", result.get(0).getCanonicalName());
    }

    @Test
    void findBookmarksOrderedByBookmarkedAtSortsNullBookmarkedAtLast() {
        // Simulate a backfilled bookmark whose bookmarked_at got NULLed somehow:
        // we save+toggle but then null the column directly.
        Actress null1 = repo.save(actress("Null bm"));
        repo.toggleBookmark(null1.getId(), true);
        connection_execute("UPDATE actresses SET bookmarked_at = NULL WHERE id = " + null1.getId());

        Actress fresh = repo.save(actress("Fresh bm"));
        repo.toggleBookmark(fresh.getId(), true);

        List<Actress> result = repo.findBookmarksOrderedByBookmarkedAt(10, java.util.Set.of());
        assertEquals("Fresh bm", result.get(0).getCanonicalName());
        assertEquals("Null bm",  result.get(1).getCanonicalName());
    }

    // findUndiscoveredElites

    @Test
    void findUndiscoveredElitesReturnsEliteActressesWithLowVisitCount() {
        repo.save(actress("Library Star"));   // wrong tier
        repo.save(actress("Popular Unseen", Actress.Tier.POPULAR));
        Actress godVisited = repo.save(actress("Visited Goddess", Actress.Tier.GODDESS));
        // Visit her enough that she should NOT count.
        repo.recordVisit(godVisited.getId());
        repo.recordVisit(godVisited.getId());

        List<Actress> result = repo.findUndiscoveredElites(
                java.util.Set.of(Actress.Tier.POPULAR, Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS),
                2, 10, java.util.Set.of());
        List<String> names = result.stream().map(Actress::getCanonicalName).toList();
        assertTrue(names.contains("Popular Unseen"));
        assertFalse(names.contains("Library Star"));
        assertFalse(names.contains("Visited Goddess"));
    }

    @Test
    void findUndiscoveredElitesExcludesRejected() {
        Actress a = repo.save(actress("Rejected Goddess", Actress.Tier.GODDESS));
        repo.toggleRejected(a.getId(), true);

        List<Actress> result = repo.findUndiscoveredElites(
                java.util.Set.of(Actress.Tier.GODDESS), 2, 10, java.util.Set.of());
        assertTrue(result.isEmpty());
    }

    // findForgottenGemsCandidates

    @Test
    void findForgottenGemsCandidatesPicksAGradeAndFavoriteWithStaleLastVisit() throws Exception {
        Actress aGraded = repo.save(actress("A Graded"));
        repo.setGrade(aGraded.getId(), Actress.Grade.A);
        // Set last_visited_at to 200 days ago via raw SQL.
        connection_execute("UPDATE actresses SET last_visited_at = '" +
                java.time.LocalDateTime.now().minusDays(200) + "' WHERE id = " + aGraded.getId());

        Actress favRecent = repo.save(actress("Favorite Recent"));
        repo.toggleFavorite(favRecent.getId(), true);
        repo.recordVisit(favRecent.getId());   // last_visited_at is "now"

        Actress favNeverVisited = repo.save(actress("Favorite Never"));
        repo.toggleFavorite(favNeverVisited.getId(), true);
        // last_visited_at stays NULL → counts as forgotten

        Actress plain = repo.save(actress("Plain"));   // not graded ≥ A and not favorite

        List<Actress> result = repo.findForgottenGemsCandidates(
                java.util.Set.of(Actress.Grade.SSS, Actress.Grade.SS, Actress.Grade.S,
                                 Actress.Grade.A_PLUS, Actress.Grade.A),
                java.util.Set.of(),
                LocalDate.now().minusDays(90),
                10, java.util.Set.of());
        List<String> names = result.stream().map(Actress::getCanonicalName).toList();
        assertTrue(names.contains("A Graded"));
        assertTrue(names.contains("Favorite Never"));
        assertFalse(names.contains("Favorite Recent"));
        assertFalse(names.contains("Plain"));
    }

    @Test
    void findForgottenGemsCandidatesExcludesRejected() {
        Actress a = repo.save(actress("Rejected Fav"));
        repo.toggleFavorite(a.getId(), true);
        repo.toggleRejected(a.getId(), true);

        assertTrue(repo.findForgottenGemsCandidates(
                java.util.Set.of(), java.util.Set.of(),
                LocalDate.now().minusDays(90), 10, java.util.Set.of()).isEmpty());
    }

    // findResearchGapCandidates

    @Test
    void findResearchGapCandidatesReturnsQualifyingActressesMissingBio() {
        // Qualifying + missing bio → should appear
        Actress fav = repo.save(actress("Fav No Bio"));
        repo.toggleFavorite(fav.getId(), true);

        Actress graded = repo.save(actress("Graded No Bio"));
        repo.setGrade(graded.getId(), Actress.Grade.B);

        Actress goddess = repo.save(actress("Goddess No Bio", Actress.Tier.GODDESS));

        // Qualifying but HAS bio → should NOT appear
        Actress favWithBio = repo.save(actress("Fav With Bio"));
        repo.toggleFavorite(favWithBio.getId(), true);
        connection_execute("UPDATE actresses SET biography = 'bio text' WHERE id = " + favWithBio.getId());

        // Not qualifying → should NOT appear
        repo.save(actress("Plain"));

        List<Actress> result = repo.findResearchGapCandidates(
                java.util.Set.of(Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS), 10);
        List<String> names = result.stream().map(Actress::getCanonicalName).toList();
        assertTrue(names.contains("Fav No Bio"));
        assertTrue(names.contains("Graded No Bio"));
        assertTrue(names.contains("Goddess No Bio"));
        assertFalse(names.contains("Fav With Bio"));
        assertFalse(names.contains("Plain"));
    }

    @Test
    void findResearchGapCandidatesOrdersByTierWeightThenFavorite() {
        Actress goddessFav = repo.save(actress("Goddess Fav", Actress.Tier.GODDESS));
        repo.toggleFavorite(goddessFav.getId(), true);

        Actress popularFav = repo.save(actress("Popular Fav", Actress.Tier.POPULAR));
        repo.toggleFavorite(popularFav.getId(), true);

        Actress goddessOnly = repo.save(actress("Goddess Only", Actress.Tier.GODDESS));

        List<Actress> result = repo.findResearchGapCandidates(
                java.util.Set.of(Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS), 10);
        // Goddesses (tier weight 5) before Populars (weight 1).
        // Within goddesses, favorite ranks above non-favorite.
        assertEquals("Goddess Fav",  result.get(0).getCanonicalName());
        assertEquals("Goddess Only", result.get(1).getCanonicalName());
        assertEquals("Popular Fav",  result.get(2).getCanonicalName());
    }

    // computeActressLibraryStats

    @Test
    void computeActressLibraryStatsCountsAllBuckets() throws Exception {
        Actress fav = repo.save(actress("Fav"));
        repo.toggleFavorite(fav.getId(), true);

        Actress graded = repo.save(actress("Graded"));
        repo.setGrade(graded.getId(), Actress.Grade.B);
        connection_execute("UPDATE actresses SET biography = 'has bio' WHERE id = " + graded.getId());

        repo.save(actress("Goddess", Actress.Tier.GODDESS));
        repo.save(actress("Superstar", Actress.Tier.SUPERSTAR));
        repo.save(actress("Plain"));

        Actress rejected = repo.save(actress("Rejected"));
        repo.toggleRejected(rejected.getId(), true);

        ActressRepository.ActressLibraryStats stats = repo.computeActressLibraryStats();
        assertEquals(5, stats.totalActresses());     // excludes rejected
        assertEquals(1, stats.favorites());
        assertEquals(1, stats.graded());
        assertEquals(2, stats.elites());             // Goddess + Superstar
        // Research total = fav + graded + Goddess + Superstar = 4 (Plain is none of those).
        assertEquals(4, stats.researchTotal());
        // Only "Graded" has biography populated.
        assertEquals(1, stats.researchCovered());
    }

    // findActressLabelEngagements

    @Test
    void findActressLabelEngagementsReturnsOneRowPerActressLabelPair() throws Exception {
        Actress a = repo.save(actress("Multi-label"));
        repo.toggleFavorite(a.getId(), true);
        // 2 titles same label, 1 title different label → 2 distinct labels for this actress.
        insertTitleWithLabel(a.getId(), "ABC-001", "ABC");
        insertTitleWithLabel(a.getId(), "ABC-002", "ABC");
        insertTitleWithLabel(a.getId(), "DEF-001", "DEF");

        List<ActressRepository.ActressLabelEngagement> rows = repo.findActressLabelEngagements();
        assertEquals(2, rows.size());
        java.util.Set<String> labels = rows.stream()
                .map(ActressRepository.ActressLabelEngagement::labelCode)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("ABC", "DEF"), labels);
        rows.forEach(r -> {
            assertEquals(a.getId().longValue(), r.actressId());
            assertTrue(r.favorite());
        });
    }

    @Test
    void findActressLabelEngagementsSkipsRejectedActressesAndEmptyLabels() throws Exception {
        Actress good = repo.save(actress("Good"));
        Actress rej  = repo.save(actress("Rejected"));
        repo.toggleRejected(rej.getId(), true);

        insertTitleWithLabel(good.getId(), "ABC-001", "ABC");
        insertTitleWithLabel(rej.getId(),  "DEF-001", "DEF");
        insertTitleWithLabel(good.getId(), "NOLBL-1", null);   // empty label → skipped

        List<ActressRepository.ActressLabelEngagement> rows = repo.findActressLabelEngagements();
        assertEquals(1, rows.size());
        assertEquals("ABC", rows.get(0).labelCode());
        assertEquals(good.getId().longValue(), rows.get(0).actressId());
    }

    // --- helpers ---

    private static Actress actress(String canonicalName) {
        return actress(canonicalName, Actress.Tier.LIBRARY);
    }

    private static Actress actress(String canonicalName, Actress.Tier tier) {
        return Actress.builder()
                .canonicalName(canonicalName)
                .tier(tier)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    /** Builder seeded with sane defaults for tests that need to override DOB / first_seen / tier. */
    private static Actress.ActressBuilder builder(String canonicalName) {
        return Actress.builder()
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1));
    }

    /** Stamp a DOB on an actress directly — repo.save() does not persist enrichment fields. */
    private void setDob(long actressId, LocalDate dob) {
        connection_execute("UPDATE actresses SET date_of_birth = '" + dob + "' WHERE id = " + actressId);
    }

    /** Run a single SQL statement on the test connection. Used to set columns the repo doesn't expose. */
    private void connection_execute(String sql) {
        try (var stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Insert a title with a label and link it to an actress via title_actresses. */
    private void insertTitleWithLabel(long actressId, String code, String label) throws Exception {
        try (var stmt = connection.createStatement()) {
            String labelClause = label == null ? "NULL" : "'" + label + "'";
            stmt.execute("INSERT INTO titles (code, label) VALUES ('" + code + "', " + labelClause + ")");
            long titleId = stmt.getGeneratedKeys().getLong(1);
            stmt.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + titleId + ", " + actressId + ")");
        }
    }
}
