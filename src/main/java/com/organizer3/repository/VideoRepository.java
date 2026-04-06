package com.organizer3.repository;

import com.organizer3.model.Video;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@link Video} records.
 */
public interface VideoRepository {

    Optional<Video> findById(long id);

    List<Video> findByTitle(long titleId);

    /**
     * Insert a new video or update an existing one (matched by id).
     * Returns the video with its generated id populated.
     */
    Video save(Video video);

    void delete(long id);

    void deleteByTitle(long titleId);

    /** Remove all video records for a volume (used before a full re-sync). */
    void deleteByVolume(String volumeId);

    /**
     * Remove all video records whose titles belong to the given volume and partition
     * (used before a partition-scoped re-sync).
     */
    void deleteByVolumeAndPartition(String volumeId, String partitionId);
}
