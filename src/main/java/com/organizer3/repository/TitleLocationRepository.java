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
}
