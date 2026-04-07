package com.organizer3.repository;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@link Actress} records and their alias mappings.
 *
 * <p>Name resolution always goes through {@link #resolveByName(String)} — callers should never
 * need to query canonical names and aliases separately.
 */
public interface ActressRepository {

    Optional<Actress> findById(long id);

    Optional<Actress> findByCanonicalName(String name);

    /**
     * Resolve any name — canonical or alias — to the canonical {@link Actress}.
     * Returns empty if no actress or alias matches.
     */
    Optional<Actress> resolveByName(String name);

    List<Actress> findAll();

    /**
     * Find all actresses where any name token (first name, last name, etc.) starts with
     * the given prefix, case-insensitively.
     */
    List<Actress> searchByNamePrefix(String prefix);

    /**
     * Find all actresses whose canonical name starts with {@code prefix} (case-insensitive).
     * Since names are stored given-name-first, this effectively filters by first name prefix.
     */
    List<Actress> findByFirstNamePrefix(String prefix);

    List<Actress> findByTier(Actress.Tier tier);

    /**
     * Find all actresses that have at least one title located on any of the given volumes.
     */
    List<Actress> findByVolumeIds(List<String> volumeIds);

    List<Actress> findFavorites();

    /** Returns a random sample of at most {@code limit} actresses. */
    List<Actress> findRandom(int limit);

    /**
     * Insert a new actress or update an existing one (matched by id).
     * Returns the actress with its generated id populated.
     */
    Actress save(Actress actress);

    void setStageName(long actressId, String stageName);

    void updateTier(long actressId, Actress.Tier tier);

    void toggleFavorite(long actressId, boolean favorite);

    void toggleBookmark(long actressId, boolean bookmark);

    void setGrade(long actressId, Actress.Grade grade);

    void toggleRejected(long actressId, boolean rejected);

    // --- Alias operations ---

    List<ActressAlias> findAliases(long actressId);

    void saveAlias(ActressAlias alias);

    void deleteAlias(long actressId, String aliasName);

    /** Replace all aliases for an actress atomically. */
    void replaceAllAliases(long actressId, List<String> aliasNames);

    /**
     * Import actress and alias data from a parsed aliases.yaml.
     * For each entry: ensures the canonical actress exists (creates with LIBRARY tier if new),
     * then replaces all her alias mappings with the ones from the file.
     * Runs in a single transaction.
     */
    void importFromYaml(List<AliasYamlEntry> entries);
}
