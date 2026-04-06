package com.organizer3.repository;

import com.organizer3.model.Volume;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@link Volume} records.
 *
 * <p>Volumes are primarily defined by config (organizer-config.yaml) but the DB tracks
 * their last-sync timestamp so the UI can show staleness at a glance.
 */
public interface VolumeRepository {

    Optional<Volume> findById(String id);

    List<Volume> findAll();

    /** Insert or update a volume record. */
    void save(Volume volume);

    void updateLastSyncedAt(String volumeId, LocalDateTime at);
}
