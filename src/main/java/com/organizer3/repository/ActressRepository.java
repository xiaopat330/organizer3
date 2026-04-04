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
     * Insert a new actress or update an existing one (matched by id).
     * Returns the actress with its generated id populated.
     */
    Actress save(Actress actress);

    void updateTier(long actressId, Actress.Tier tier);

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
