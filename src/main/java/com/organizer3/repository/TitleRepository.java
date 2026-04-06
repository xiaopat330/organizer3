package com.organizer3.repository;

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

    List<Title> findByVolume(String volumeId);

    List<Title> findByActress(long actressId);

    int countByActress(long actressId);

    /**
     * Find all titles for an actress, including titles attributed to any actress record
     * whose canonical name matches one of her known aliases.
     *
     * <p>This covers the case where titles were synced before an alias was configured,
     * resulting in a separate actress record for the alias name. Use this instead of
     * {@link #findByActress} when you want the full picture of her content.
     */
    List<Title> findByActressIncludingAliases(long actressId);

    /**
     * Find titles attributed to alias actress records but not to the canonical actress record.
     *
     * <p>These are titles that need to be re-attributed to the canonical actress as part of
     * alias denormalization — i.e., the actress's content is fragmented across orphan records.
     */
    List<Title> findByAliasesOnly(long actressId);

    /**
     * Insert a new title or update an existing one (matched by id).
     * Returns the title with its generated id populated.
     */
    Title save(Title title);

    void delete(long id);

    /** Remove all title records for a volume (used before a full re-sync). */
    void deleteByVolume(String volumeId);

    /** Find titles for a specific volume+partition, ordered by added_date DESC, id DESC. */
    List<Title> findByVolumeAndPartition(String volumeId, String partitionId, int limit, int offset);

    /** Remove title records for a specific volume+partition (used before a partition-scoped re-sync). */
    void deleteByVolumeAndPartition(String volumeId, String partitionId);

    /** Find titles ordered by added_date DESC, id DESC — for the browse home page. */
    List<Title> findRecent(int limit, int offset);

    /** Find titles for an actress ordered by added_date DESC, id DESC — for the actress detail page. */
    List<Title> findByActressPaged(long actressId, int limit, int offset);
}
