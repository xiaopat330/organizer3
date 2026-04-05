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

    List<Title> findByVolume(String volumeId);

    List<Title> findByActress(long actressId);

    /**
     * Insert a new title or update an existing one (matched by id).
     * Returns the title with its generated id populated.
     */
    Title save(Title title);

    void delete(long id);

    /** Remove all title records for a volume (used before a full re-sync). */
    void deleteByVolume(String volumeId);

    /** Remove title records for a specific volume+partition (used before a partition-scoped re-sync). */
    void deleteByVolumeAndPartition(String volumeId, String partitionId);
}
