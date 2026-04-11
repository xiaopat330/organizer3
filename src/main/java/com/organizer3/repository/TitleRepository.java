package com.organizer3.repository;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** Delete titles that have zero locations (orphaned after location cleanup). */
    void deleteOrphaned();

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
}
