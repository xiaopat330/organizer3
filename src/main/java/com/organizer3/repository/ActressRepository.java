package com.organizer3.repository;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence operations for {@link Actress} records and their alias mappings.
 *
 * <p>Name resolution always goes through {@link #resolveByName(String)} — callers should never
 * need to query canonical names and aliases separately.
 */
public interface ActressRepository {

    Optional<Actress> findById(long id);

    /** Fetch multiple actresses by id in one query. Order of results is unspecified. */
    List<Actress> findByIds(List<Long> ids);

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
     * Paginated name search, case-insensitive.
     *
     * <p>Two forms:
     * <ul>
     *   <li><b>Single token</b> ("aya"): match first-name-starts-with OR any-word-starts-with.</li>
     *   <li><b>Compound</b> ("aya sa"): first name must start with the first token AND some
     *       later name word must start with the second token.</li>
     * </ul>
     *
     * <p>Results are ordered favorites first, bookmarks second, then alphabetically by
     * canonical name.
     */
    List<Actress> searchByNamePrefixPaged(String prefix, int limit, int offset);

    /**
     * Find all actresses whose canonical name starts with {@code prefix} (case-insensitive).
     * Since names are stored given-name-first, this effectively filters by first name prefix.
     */
    List<Actress> findByFirstNamePrefix(String prefix);

    /**
     * Paginated version: find actresses whose canonical name starts with {@code prefix}
     * (single letter), optionally filtered by tier.
     */
    List<Actress> findByFirstNamePrefixPaged(String prefix, Actress.Tier tier, int limit, int offset);

    /**
     * Returns the count of actresses per tier whose canonical name starts with {@code prefix}.
     * Tiers with zero actresses are omitted from the result.
     */
    Map<String, Integer> countByFirstNamePrefixGroupedByTier(String prefix);

    List<Actress> findByTier(Actress.Tier tier);

    /** Paginated version: actresses at the given tier, ordered by canonical name. */
    List<Actress> findByTierPaged(Actress.Tier tier, int limit, int offset);

    /** Paginated: all actresses ordered by canonical name. */
    List<Actress> findAllPaged(int limit, int offset);

    /** Paginated: only favorite actresses ordered by canonical name. */
    List<Actress> findFavoritesPaged(int limit, int offset);

    /** Paginated: only bookmarked actresses ordered by canonical name. */
    List<Actress> findBookmarksPaged(int limit, int offset);

    /**
     * Find all actresses that have at least one title located on any of the given volumes.
     */
    List<Actress> findByVolumeIds(List<String> volumeIds);

    /**
     * Paginated version: find actresses on any of the given volumes, ordered by canonical name.
     */
    List<Actress> findByVolumeIdsPaged(List<String> volumeIds, int limit, int offset);

    List<Actress> findFavorites();

    /** Returns a random sample of at most {@code limit} actresses. */
    List<Actress> findRandom(int limit);

    /** Returns the most recently visited actresses (visit_count &gt; 0), ordered by last_visited_at DESC. */
    List<Actress> findLastVisited(int limit);

    /** Returns the most-visited actresses (visit_count &gt; 0), ordered by visit_count DESC. */
    List<Actress> findMostVisited(int limit);

    /**
     * Insert a new actress or update an existing one (matched by id).
     * Returns the actress with its generated id populated.
     */
    Actress save(Actress actress);

    void setStageName(long actressId, String stageName);

    /**
     * Overwrite all enrichment profile fields for an actress.
     * Leaves operational fields (tier, favorite, bookmark, grade, rejected) unchanged.
     * Called by the {@code load actress} command.
     */
    void updateProfile(long actressId, String stageName, java.time.LocalDate dateOfBirth,
                       String birthplace, String bloodType, Integer heightCm,
                       Integer bust, Integer waist, Integer hip, String cup,
                       java.time.LocalDate activeFrom, java.time.LocalDate activeTo,
                       String biography, String legacy);

    void updateTier(long actressId, Actress.Tier tier);

    /**
     * Recalculates and persists the tier for every actress based on her current title count
     * across all volumes. Applies the standard thresholds: GODDESS ≥100, SUPERSTAR ≥50,
     * POPULAR ≥20, MINOR ≥5, LIBRARY &lt;5. Returns the number of rows updated.
     */
    int recalcTiers();

    void toggleFavorite(long actressId, boolean favorite);

    void toggleBookmark(long actressId, boolean bookmark);

    void setGrade(long actressId, Actress.Grade grade);

    void toggleRejected(long actressId, boolean rejected);

    /**
     * Increment the visit counter and update last_visited_at to now for an actress.
     * No-op if the actress does not exist.
     */
    void recordVisit(long actressId);

    /**
     * Atomically overwrite the three mutually-interacting flags (favorite, bookmark, rejected)
     * for an actress in a single UPDATE. Callers are responsible for enforcing any
     * mutual-exclusion rules (e.g. rejected implies !favorite and !bookmark).
     */
    void setFlags(long actressId, boolean favorite, boolean bookmark, boolean rejected);

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
