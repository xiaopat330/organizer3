package com.organizer3.avstars.repository;

import com.organizer3.avstars.model.AvVideo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link AvVideo} records.
 */
public interface AvVideoRepository {

    /** Finds a video by its database ID. */
    Optional<AvVideo> findById(long id);

    /** Returns all videos for the given actress, sorted by relative_path. */
    List<AvVideo> findByActress(long avActressId);

    /** Returns all videos for the given volume. */
    List<AvVideo> findByVolume(String volumeId);

    /**
     * Upserts a video by (av_actress_id, relative_path). Updates size, mtime, and
     * last_seen_at on conflict. Returns the row ID (new or existing).
     */
    long upsert(AvVideo video);

    /**
     * Deletes all videos for the given volume that were last seen before the given
     * timestamp (orphan cleanup after sync).
     */
    void deleteOrphanedByVolume(String volumeId, LocalDateTime syncStart);
}
