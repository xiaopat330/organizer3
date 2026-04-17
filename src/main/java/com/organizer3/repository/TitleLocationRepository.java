package com.organizer3.repository;

import com.organizer3.model.TitleLocation;

import java.util.List;

/**
 * Persistence operations for {@link TitleLocation} records.
 */
public interface TitleLocationRepository {

    TitleLocation save(TitleLocation location);

    List<TitleLocation> findByTitle(long titleId);

    List<TitleLocation> findByTitleIds(List<Long> titleIds);

    List<TitleLocation> findByVolume(String volumeId);

    void deleteByVolume(String volumeId);

    void deleteByVolumeAndPartition(String volumeId, String partitionId);

    /**
     * Update an existing row's {@code path} and {@code partition_id} after a sort-phase
     * move. No-op if the row does not exist. Does not touch {@code last_seen_at} or
     * {@code added_date} — those reflect discovery state, not filing state.
     */
    void updatePathAndPartition(long locationId, java.nio.file.Path newPath, String newPartitionId);
}
