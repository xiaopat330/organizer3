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
