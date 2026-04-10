package com.organizer3.repository;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;

import java.util.List;
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
}
