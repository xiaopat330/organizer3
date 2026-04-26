package com.organizer3.repository;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence operations for {@link Title} records.
 */
public interface TitleRepository {

    Optional<Title> findById(long id);

    /** Look up by exact raw code (e.g. "ABP-123"). */
    Optional<Title> findByCode(String code);

    /** Look up by normalized base code (e.g. "ABP-00123") — may return multiple for variant releases. */
    List<Title> findByBaseCode(String baseCode);

    /**
     * Returns the actress id most frequently attributed to titles with the given label,
     * or empty if no attributed titles exist for that label.
     * Used to infer the actress for unattributed queue titles.
     */
    Optional<Long> findDominantActressForLabel(String label);

    /** Find all titles that have at least one location on the given volume. */
    List<Title> findByVolume(String volumeId);

    /** Count titles that have at least one location on the given volume. */
    int countByVolume(String volumeId);

    List<Title> findByActress(long actressId);

    int countByActress(long actressId);

    /**
     * Find all titles for an actress, including titles attributed to any actress record
     * whose canonical name matches one of her known aliases.
     */
    List<Title> findByActressIncludingAliases(long actressId);

    /**
     * Find titles attributed to alias actress records but not to the canonical actress record.
     */
    List<Title> findByAliasesOnly(long actressId);

    /**
     * Insert a new title or update an existing one (matched by id).
     * Returns the title with its generated id populated.
     */
    Title save(Title title);

    /**
     * Find an existing title by code, or create a new one from the given template.
     * If the title already exists and has no actress but the template provides one,
     * updates the actress attribution.
     */
    Title findOrCreateByCode(Title template);

    void delete(long id);

    /** Find titles with at least one location on the given volume, ordered newest-first. */
    List<Title> findByVolumePaged(String volumeId, int limit, int offset);

    /** Find titles with at least one location on the given volume+partition. */
    List<Title> findByVolumeAndPartition(String volumeId, String partitionId, int limit, int offset);

    /** Find titles ordered by added_date DESC — for the browse home page. */
    List<Title> findRecent(int limit, int offset);

    /**
     * Find titles whose label starts with {@code labelPrefix} (case-insensitive) and whose
     * {@code seq_num}, when rendered without leading zeros, starts with {@code seqPrefix}.
     * Pass an empty {@code seqPrefix} to skip the seq constraint. Results are ordered by
     * favorite → bookmark → label → seq_num.
     */
    List<Title> findByCodePrefixPaged(String labelPrefix, String seqPrefix, int limit, int offset);

    /** Find favorited titles, ordered newest-first by added_date. */
    List<Title> findFavoritesPaged(int limit, int offset);

    /** Find bookmarked titles, ordered newest-first by added_date. */
    List<Title> findBookmarksPaged(int limit, int offset);

    /** Find titles for an actress ordered by added_date DESC. */
    List<Title> findByActressPaged(long actressId, int limit, int offset);

    /**
     * Find titles for an actress restricted to the given label codes (upper-case),
     * ordered by added_date DESC.
     */
    List<Title> findByActressAndLabelsPaged(long actressId, List<String> labels, int limit, int offset);

    /** Find titles in random order (ignores offset — each call returns a fresh random sample). */
    List<Title> findRandom(int limit);

    /** Total number of rows in {@code titles}. Used by cascade-safety guards. */
    int countAll();

    /**
     * Returns the set of all {@code base_code} values in the titles table.
     * Used by orphaned-covers detection to do a single bulk lookup instead of one query per file.
     */
    Set<String> allBaseCodes();

    /**
     * Batch-load titles for multiple actress IDs in a single query. Returns a map from actress ID
     * to the list of titles attributed to that actress (via actress_id or title_actresses junction).
     * Returns an empty map when the input collection is empty.
     */
    Map<Long, List<Title>> findByActressIds(Collection<Long> actressIds);

    /**
     * Delete titles that have zero locations (orphaned after location cleanup). Returns the
     * number of rows deleted.
     *
     * <p><b>Cascade safety:</b> throws {@link com.organizer3.repository.CatastrophicDeleteException}
     * without deleting anything if the orphan count exceeds {@code max(500, total/4)}. That
     * threshold catches the failure mode from the 2026-04-23 incident (bug in a location
     * predicate wipes {@code title_locations}, then this method would drop every title) while
     * leaving normal sync cleanups (a handful of orphans per run) unaffected.
     */
    int deleteOrphaned();

    /**
     * Returns a lightweight projection of every title that is currently orphaned — zero
     * {@code title_locations} rows. Used by sync to enumerate covers to delete alongside the
     * row drop. Call before {@link #deleteOrphaned}; after the delete the set is empty.
     */
    List<OrphanedTitleRef> findOrphanedTitles();

    /** Lightweight {label, baseCode} projection for orphan cover cleanup. */
    record OrphanedTitleRef(String label, String baseCode) {}

    /**
     * Returns the top actresses by title count for titles whose label is in {@code labels}.
     * Each row is [actressId (Long), actressName (String), tier (String), count (Long)].
     */
    List<Object[]> findTopActressesByLabels(List<String> labels, int limit);

    /**
     * Returns distinct actresses ordered by the most recently added title whose label is in
     * {@code labels}. Each row is [actressId (Long), actressName (String), tier (String)].
     */
    List<Object[]> findNewestActressesByLabels(List<String> labels, int limit);

    /**
     * Counts titles per label company, restricted to {@code companies}. The mapping
     * {@code titles.label → labels.code → labels.company} is resolved inside the query.
     * Companies with zero matching titles are omitted from the returned map.
     */
    Map<String, Long> countTitlesByCompanies(List<String> companies);

    /**
     * Overwrite enrichment fields for a title.
     * Leaves operational fields (actress_id, favorite, bookmark, rejected) unchanged.
     * Called by the {@code load actress} command.
     */
    void enrichTitle(long titleId, String titleOriginal, String titleEnglish,
                     java.time.LocalDate releaseDate, String notes, Actress.Grade grade);

    /** Find titles having ALL of the given tags, ordered newest-first. */
    List<Title> findByTagsPaged(List<String> tags, int limit, int offset);

    /**
     * Find titles for an actress, optionally restricted to a set of label codes and/or requiring
     * all of the given tags (direct or label-derived). Pass empty lists to skip that dimension.
     * Ordered by favorite → bookmark → newest first.
     */
    List<Title> findByActressTagsFiltered(long actressId, List<String> labels, List<String> tags, int limit, int offset);

    /**
     * Find titles on a volume, optionally restricted to label codes and/or requiring all tags.
     * Pass empty lists to skip that dimension. Ordered by favorite → bookmark → newest first.
     */
    List<Title> findByVolumeFiltered(String volumeId, List<String> labels, List<String> tags, int limit, int offset);

    /**
     * Find titles in a volume+partition, optionally restricted to label codes and/or requiring
     * all tags. Pass empty lists to skip that dimension. Ordered by favorite → bookmark → newest first.
     */
    List<Title> findByVolumeAndPartitionFiltered(String volumeId, String partitionId, List<String> labels, List<String> tags, int limit, int offset);

    /** Returns all distinct tags (direct + label-derived) for titles on the given volume, sorted. */
    List<String> findTagsByVolume(String volumeId);

    /** Returns all distinct tags (direct + label-derived) for titles in a volume+partition, sorted. */
    List<String> findTagsByVolumeAndPartition(String volumeId, String partitionId);

    void toggleFavorite(long titleId, boolean favorite);

    void toggleBookmark(long titleId, boolean bookmark);

    /**
     * Increment the visit counter and update last_visited_at to now for a title.
     * No-op if the title does not exist.
     */
    void recordVisit(long titleId);

    /** Returns the most recently visited titles (visit_count &gt; 0), ordered by last_visited_at DESC. */
    List<Title> findLastVisited(int limit);

    /** Returns the most-visited titles (visit_count &gt; 0), ordered by visit_count DESC. */
    List<Title> findMostVisited(int limit);

    // ── Federated search ──────────────────────────────────────────────────────

    /** Lightweight title projection for federated search results. */
    record FederatedTitleResult(
            long id,
            String code,
            String titleOriginal,
            String titleEnglish,
            String label,
            String baseCode,
            String releaseDate,
            Long actressId,
            String actressName,
            boolean favorite,
            boolean bookmark
    ) {}

    /**
     * Search titles by title_original or title_english for the federated search overlay.
     * Rejected titles are excluded. Results ordered: favorites first, bookmarks next, then newest.
     */
    List<FederatedTitleResult> searchByTitleName(String query, boolean startsWith, int limit);

    /**
     * Search titles whose code starts with {@code prefix} (case-insensitive).
     * Used by the search overlay's partial product-code shortcut.
     * Pass {@code limit=11} and check if the result size exceeds 10 to decide whether to display.
     */
    List<FederatedTitleResult> searchByCodePrefix(String prefix, int limit);

    // ── Dashboard module queries ─────────────────────────────────────────────

    /** Light projection: (labelCode, score) for a label's aggregated engagement score. */
    record LabelScore(String code, double score) {}

    /** Light projection for library stats. */
    record LibraryStats(long totalTitles, long totalLabels, long unseen, long addedThisMonth, long addedThisYear) {}

    /**
     * Find titles whose earliest location added_date is on or after {@code since}, ordered
     * newest-first. Used for "Just Added". Excludes any title whose code is in {@code excludeCodes}.
     */
    List<Title> findAddedSince(java.time.LocalDate since, int limit, java.util.Set<String> excludeCodes);

    /**
     * Same as {@link #findAddedSince} but restricted to titles whose label (uppercased) is in
     * {@code labels}. Used for "From Favorite Labels".
     */
    List<Title> findAddedSinceByLabels(java.time.LocalDate since, java.util.Collection<String> labels,
                                        int limit, java.util.Set<String> excludeCodes);

    /**
     * Find titles whose release_date OR earliest added_date month-day matches the given
     * month/day, ordered by year ascending (oldest first).
     */
    List<Title> findAnniversary(int month, int day, int limit);

    /**
     * Compute per-label aggregated engagement scores across all titles:
     * {@code sum(visitCount) + 3*favoriteCount + 2*bookmarkCount}. Returns rows with score &gt; 0
     * ordered by raw score DESC. Caller can apply weighted random selection on top.
     */
    List<LabelScore> computeLabelScores(int limit);

    /** Compute scalar library stats in a single pass where possible. */
    LibraryStats computeLibraryStats();

    /**
     * Find candidate titles for the Spotlight module (one big card, weighted random pick).
     * Candidate pool: favorited/bookmarked titles, titles for loved actresses, titles with
     * loved labels, or titles whose actress tier is ≥ SUPERSTAR. Returns a larger candidate set
     * with a computed score column so the caller can do weighted sampling.
     *
     * <p>Returns up to {@code limit} rows ordered by computed score DESC. Caller applies
     * weighted random sampling and excludes codes in {@code excludeCodes}.
     *
     * @param lovedLabels        set of loved label codes (uppercase)
     * @param lovedActressIds    set of loved actress ids (favorited/bookmarked/high-tier)
     * @param superstarTiers     tier names counted as "superstar or above"
     */
    List<Title> findSpotlightCandidates(java.util.Set<String> lovedLabels,
                                         java.util.Set<Long> lovedActressIds,
                                         java.util.Set<String> superstarTiers,
                                         int limit,
                                         java.util.Set<String> excludeCodes);

    /**
     * Find candidates for Forgotten Attic:
     * visitCount = 0 OR lastVisitedAt &lt; now - 180d, AND (addedDate &lt; 60d OR addedDate &gt; 365d).
     * Sorted by computed score DESC (age dominates).
     */
    List<Title> findForgottenAtticCandidates(int limit, java.util.Set<String> excludeCodes);

    /**
     * Find candidates for Forgotten Favorites:
     * favorite=1 AND (lastVisitedAt IS NULL OR lastVisitedAt &lt; now - 90d).
     * Sorted by staleness-dominated score DESC.
     */
    List<Title> findForgottenFavoritesCandidates(int limit, java.util.Set<String> excludeCodes);

    // ── Library browse ──────────────────────────────────────────────────────

    /**
     * Returns distinct label codes (uppercased) that start with {@code prefix}, ordered
     * alphabetically. At most 20 results — used for the Library code-input autocomplete.
     */
    List<String> findLabelCodesWithPrefix(String prefix);

    /**
     * Full-library paged query with all optional filters combined via AND.
     *
     * @param labelPrefix        label code prefix from TitleCodeQuery (empty = no filter)
     * @param seqPrefix          sequence number prefix, leading zeros stripped (empty = no filter)
     * @param companyLabels      label codes belonging to the selected company (empty = no filter)
     * @param tags               curated tag names that must ALL be present (empty = no filter)
     * @param enrichmentTagIds   raw enrichment_tag_definition IDs that must ALL be present (empty = no filter)
     * @param sort               "productCode" | "actressName" | "addedDate" (null → addedDate)
     * @param asc                true for ascending, false for descending
     */
    List<Title> findLibraryPaged(String labelPrefix, String seqPrefix,
                                  List<String> companyLabels, List<String> tags,
                                  List<Long> enrichmentTagIds,
                                  String sort, boolean asc,
                                  int limit, int offset);

    /** Convenience overload that forwards with an empty enrichmentTagIds list. */
    default List<Title> findLibraryPaged(String labelPrefix, String seqPrefix,
                                          List<String> companyLabels, List<String> tags,
                                          String sort, boolean asc,
                                          int limit, int offset) {
        return findLibraryPaged(labelPrefix, seqPrefix, companyLabels, tags,
                List.of(), sort, asc, limit, offset);
    }

    /**
     * Returns effective tag counts across the library.
     * Map key is the curated tag name; value is the count of distinct titles bearing that tag.
     */
    Map<String, Long> getTagCounts();

    // ── Duplication management ───────────────────────────────────────────────

    /**
     * Returns titles that appear in more than one location, ordered by code ascending.
     * If {@code volumeId} is non-null, only titles with at least one location on that volume
     * are returned (while still requiring the overall count > 1). Used by the Duplicates tool.
     */
    List<Title> findWithMultipleLocationsPaged(int limit, int offset, String volumeId);

    /** Total count of titles with more than one location, optionally filtered to a volume. */
    int countWithMultipleLocations(String volumeId);

    // ── Backup / restore ─────────────────────────────────────────────────────

    /**
     * Lightweight projection of user-altered fields for backup export.
     * Only titles where at least one field differs from its default are returned.
     */
    record TitleBackupRow(
            String code,
            boolean favorite,
            boolean bookmark,
            java.time.LocalDateTime bookmarkedAt,
            String grade,
            boolean rejected,
            int visitCount,
            java.time.LocalDateTime lastVisitedAt,
            String notes
    ) {}

    /**
     * Return all titles that have at least one non-default user field
     * (favorite, bookmark, grade, rejected, visitCount, lastVisitedAt, notes).
     * Returns lightweight rows — no location or enrichment data is loaded.
     */
    List<TitleBackupRow> findAllForBackup();

    /**
     * Overwrite all user-altered fields for the title with the given code
     * in a single UPDATE. No-op if the title is not found.
     * Called by the restore path — applies the full snapshot from a backup entry.
     */
    void restoreUserData(String code, boolean favorite, boolean bookmark,
                         java.time.LocalDateTime bookmarkedAt, String grade,
                         boolean rejected, int visitCount,
                         java.time.LocalDateTime lastVisitedAt, String notes);
}
