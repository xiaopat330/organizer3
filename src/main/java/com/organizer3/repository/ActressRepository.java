package com.organizer3.repository;

import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Actress;

import java.util.Collection;
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

    /**
     * Given a name that may be stored as an alias of another actress, return that primary actress.
     * Used to show "primarily known as" on alias actress profile pages.
     */
    Optional<Actress> findPrimaryForAlias(String aliasName);

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

    /** Paginated: actresses at the given tier whose titles match any of the given companies. */
    List<Actress> findByTierAndCompaniesPaged(Actress.Tier tier, List<String> companies, int limit, int offset);

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
     * Paginated version: find actresses on any of the given volumes.
     * Ordered: favorites/bookmarked first, then canonical name.
     */
    List<Actress> findByVolumeIdsPaged(List<String> volumeIds, int limit, int offset);

    /**
     * Paginated: find actresses who have ≥1 title on any of the given volumes AND ≥1 title
     * whose label belongs to a company in {@code companies}.
     * Ordered: favorites/bookmarked first, then canonical name.
     */
    List<Actress> findByVolumesAndCompaniesPaged(List<String> volumeIds, List<String> companies,
                                                 int limit, int offset);

    /**
     * Find all actresses (paginated) who own at least one title whose label belongs to a
     * company in {@code companies}. The mapping titles.label → labels.company is resolved
     * inside the query. Excludes rejected actresses. Ordered by tier rank (GODDESS first),
     * then canonical name.
     */
    List<Actress> findByStudioGroupCompaniesPaged(List<String> companies, int limit, int offset);

    /** Total count of actresses matching {@link #findByStudioGroupCompaniesPaged}. */
    long countByStudioGroupCompanies(List<String> companies);

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

    /**
     * Overwrite the extended profile fields that are not covered by {@link #updateProfile}:
     * the hiragana reading of the stage name, the retirement-announced date, and the
     * JSON-serialized list columns for alternate names, studio tenures, and awards.
     *
     * <p>Called by the YAML loader after {@code updateProfile}. Passing {@code null} or an
     * empty list clears the column.
     */
    void updateExtendedProfile(long actressId, String nameReading,
                               java.time.LocalDate retirementAnnounced,
                               List<Actress.AlternateName> alternateNames,
                               List<Actress.StudioTenure> primaryStudios,
                               List<Actress.Award> awards);

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

    /**
     * Set the actress's computed-grade triple (grade letter, raw shrunken score, sample size N).
     * Pass {@code null} for {@code grade} to clear the computed grade. Independent of the
     * manual {@link #setGrade(long, Actress.Grade)} field — never overwrites it.
     */
    void setComputedGrade(long actressId, Actress.Grade grade, Double score, Integer n);

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

    /**
     * Batch-load aliases for multiple actress IDs in a single query.
     * Returns an empty map when the input collection is empty.
     */
    Map<Long, List<ActressAlias>> findAliasesForActresses(Collection<Long> actressIds);

    /**
     * For each name in the collection that is the canonical name of an actress, returns
     * a map of that name → actress id. Used to resolve AliasDto.actressId in batch.
     * Returns an empty map when the input collection is empty.
     */
    Map<String, Long> findCanonicalNameIds(Collection<String> names);

    /**
     * For each canonical name in the collection that appears as an alias of another actress,
     * returns a map of canonical name → primary actress. Used to resolve "primarily known as"
     * relationships in batch. Returns an empty map when the input collection is empty.
     */
    Map<String, Actress> findPrimaryForAliases(Collection<String> canonicalNames);

    void saveAlias(ActressAlias alias);

    void deleteAlias(long actressId, String aliasName);

    /** Replace all aliases for an actress atomically. */
    void replaceAllAliases(long actressId, List<String> aliasNames);

    /**
     * Seed actress aliases from a parsed aliases.yaml. This is a one-time migration:
     * if the {@code actress_aliases} table already contains any rows, the call is a no-op.
     * Once seeded, the DB is the authoritative source and callers should use
     * {@link #replaceAllAliases} for individual edits or {@link #exportAliases} for backup.
     */
    void importFromYaml(List<AliasYamlEntry> entries);

    /**
     * Export all current alias data as a list of YAML-compatible entries, sorted by canonical
     * name. Actresses with no aliases are omitted. Use this to write a backup aliases.yaml.
     */
    List<AliasYamlEntry> exportAliases();

    // ── Name-check queries ───────────────────────────────────────────────────

    /**
     * Returns the combined title count for every actress, unioning both the
     * {@code titles.actress_id} FK column and the {@code title_actresses} many-to-many table.
     * Only actresses with at least one title are included.
     */
    Map<Long, Integer> countAllTitlesByActress();

    /**
     * Filing-title location record — one row per (title, location) for titles whose
     * actress is the "filing" actress (i.e. the folder on the server is named after her).
     */
    record FilingLocation(long actressId, String code, String volumeId, String path) {}

    /**
     * Returns all filing-title locations grouped by actress id.
     * Only covers titles linked via {@code titles.actress_id} (the FK), since those folder
     * names carry the actress name and may need renaming.
     */
    Map<Long, List<FilingLocation>> findFilingLocations();

    // ── Federated search ──────────────────────────────────────────────────────

    /** Lightweight actress projection for federated search results. */
    record FederatedActressResult(
            long id,
            String canonicalName,
            String stageName,
            String tier,
            String grade,
            boolean favorite,
            boolean bookmark,
            /** Non-null when the match was on an alias rather than the canonical name. */
            String matchedAlias,
            int titleCount,
            /**
             * Pipe-delimited list of up to 5 "label:baseCode" pairs from the actress's most
             * recent titles. {@code SearchService} tries each in order until a local cover file
             * is found, so stale/missing covers on any single title don't blank the result.
             */
            String coverCandidates,
            /**
             * Local profile avatar path (relative to dataDir, e.g. {@code actress-avatars/foo.jpg}),
             * or null. When present, {@code SearchService} prefers this over a cover candidate.
             */
            String localAvatarPath
    ) {}

    /**
     * Search actresses for the federated search overlay.
     * Matches against canonical_name and actress_aliases.alias_name.
     * Rejected actresses are excluded. Results ordered: favorites first, then bookmarks, then name.
     * When an alias is matched, {@link FederatedActressResult#matchedAlias()} is populated.
     */
    List<FederatedActressResult> searchForFederated(String query, boolean startsWith, int limit);

    /**
     * Variant of {@link #searchForFederated} used by the Title Editor typeahead. Identical
     * matching semantics, but does NOT apply the "≥ 2 titles" filter — the editor needs to
     * find newly-created draft actresses and low-title performers when attaching them to
     * titles. Rejected actresses are still excluded.
     */
    List<FederatedActressResult> searchForEditor(String query, boolean startsWith, int limit);

    // ── Dashboard module queries ─────────────────────────────────────────────

    /** Light projection for actress library stats. */
    record ActressLibraryStats(
            long totalActresses,
            long favorites,
            long graded,
            long elites,            // SUPERSTAR + GODDESS
            long newThisMonth,      // first_seen_at within current month
            long researchCovered,   // qualifying actresses (favorite/graded/elite) with biography populated
            long researchTotal      // qualifying actresses (favorite/graded/elite), populated or not
    ) {}

    /**
     * One row per (actress, distinct label) with engagement-component data.
     * Used by the service to aggregate Top Groups scores after mapping labels → groups via YAML.
     */
    record ActressLabelEngagement(long actressId, String labelCode, int visitCount, boolean favorite, boolean bookmark) {}

    /**
     * Find candidate actresses for the Spotlight module (one big card, weighted random pick).
     * Pool: favorited / bookmarked / graded ≥ A_PLUS / tier in {@code superstarTiers}.
     * Rejected actresses are excluded. Returns up to {@code limit} rows in random order
     * for the caller to weighted-sample.
     */
    List<Actress> findSpotlightCandidates(java.util.Set<Actress.Tier> superstarTiers,
                                          int limit,
                                          java.util.Set<Long> excludeIds);

    /**
     * Find actresses whose date_of_birth month-day matches the given month/day.
     * Excludes rejected. Sorted: favorites/bookmarks first, then tier DESC, then by canonical_name.
     */
    List<Actress> findBirthdaysToday(int month, int day, int limit);

    /**
     * Find actresses whose first_seen_at &gt;= {@code since}, ordered by first_seen_at DESC.
     * Excludes rejected and {@code excludeIds}.
     */
    List<Actress> findNewFaces(java.time.LocalDate since, int limit, java.util.Set<Long> excludeIds);

    /**
     * Fallback for {@link #findNewFaces} when the date window returns nothing — return the
     * newest N actresses overall by first_seen_at.
     */
    List<Actress> findNewFacesFallback(int limit, java.util.Set<Long> excludeIds);

    /**
     * Find bookmarked actresses ordered by bookmarked_at DESC (NULL last for backfilled rows).
     * Excludes {@code excludeIds}.
     */
    List<Actress> findBookmarksOrderedByBookmarkedAt(int limit, java.util.Set<Long> excludeIds);

    /**
     * Find "undiscovered elites": actresses whose tier is in {@code minTiers} but who have
     * been visited fewer than {@code maxVisitCount} times. Excludes rejected and {@code excludeIds}.
     * Returned in random order so the caller can pick a fresh sample each load.
     */
    List<Actress> findUndiscoveredElites(java.util.Set<Actress.Tier> minTiers,
                                         int maxVisitCount,
                                         int limit,
                                         java.util.Set<Long> excludeIds);

    /**
     * Find candidates for "Forgotten Gems": actresses with high signal (grade in {@code topGrades},
     * tier in {@code highTiers}, or favorite=1) who haven't been visited recently
     * (last_visited_at &lt; {@code staleBefore} or null). Excludes rejected and {@code excludeIds}.
     * Returned in random order for weighted sampling.
     */
    List<Actress> findForgottenGemsCandidates(java.util.Set<Actress.Grade> topGrades,
                                              java.util.Set<Actress.Tier> highTiers,
                                              java.time.LocalDate staleBefore,
                                              int limit,
                                              java.util.Set<Long> excludeIds);

    /**
     * Find candidates for the Research Gaps module: qualifying actresses (favorite, graded, or
     * tier in {@code superstarTiers}) whose biography is NULL — the strongest "needs research"
     * signal. Excludes rejected. Caller computes per-bucket completeness (profile/physical/bio/portfolio).
     */
    List<Actress> findResearchGapCandidates(java.util.Set<Actress.Tier> superstarTiers, int limit);

    /** Compute scalar library stats for the actress dashboard footer. */
    ActressLibraryStats computeActressLibraryStats();

    // ── Backup / restore ─────────────────────────────────────────────────────

    /**
     * Lightweight projection of user-altered fields for backup export.
     * Only actresses where at least one field differs from its default are returned.
     */
    record ActressBackupRow(
            String canonicalName,
            boolean favorite,
            boolean bookmark,
            java.time.LocalDateTime bookmarkedAt,
            String grade,
            boolean rejected,
            int visitCount,
            java.time.LocalDateTime lastVisitedAt
    ) {}

    /**
     * Return all actresses that have at least one non-default user field
     * (favorite, bookmark, grade, rejected, visitCount, lastVisitedAt).
     * Returns lightweight rows — no profile text is loaded.
     */
    List<ActressBackupRow> findAllForBackup();

    /**
     * Overwrite all user-altered fields for the actress with the given canonical name
     * in a single UPDATE. No-op if the actress is not found.
     * Called by the restore path — applies the full snapshot from a backup entry.
     */
    void restoreUserData(String canonicalName, boolean favorite, boolean bookmark,
                         java.time.LocalDateTime bookmarkedAt, String grade,
                         boolean rejected, int visitCount,
                         java.time.LocalDateTime lastVisitedAt);

    /**
     * Return per-(actress, label) engagement rows for Top Groups score derivation.
     * Only emits rows where the actress's title has a non-empty label. Multiple titles for
     * the same (actress, label) collapse to a single row — the label appears once per actress.
     */
    List<ActressLabelEngagement> findActressLabelEngagements();
}
