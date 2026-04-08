package com.organizer3.repository.jdbi;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;
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
}
