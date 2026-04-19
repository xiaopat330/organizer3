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

    /**
     * Overwrite media metadata fields (durationSec, width, height, videoCodec, audioCodec,
     * container) for one video. Used by the sync probe and the backfill command.
     * Other fields are left untouched.
     */
    void updateMetadata(long videoId, Long durationSec, Integer width, Integer height,
                        String videoCodec, String audioCodec, String container);

    /**
     * Returns videos whose {@code duration_sec} is NULL — the backfill candidates.
     * Optionally filtered by volume; {@code null} volume means all volumes.
     * Cursor-paginated: only returns rows with {@code id > fromIdExclusive} (use 0 to start),
     * which lets the caller make forward progress even when individual probes fail and
     * leave their rows with null metadata.
     */
    List<Video> findUnprobed(String volumeId, long fromIdExclusive, int limit);

    /** Count of videos with {@code duration_sec IS NULL}, optionally per volume. */
    long countUnprobed(String volumeId);

    /**
     * Returns videos whose {@code size_bytes} is NULL — the size-backfill candidates.
     * Optionally filtered by volume. Cursor-paginated like {@link #findUnprobed}.
     */
    List<Video> findWithoutSize(String volumeId, long fromIdExclusive, int limit);

    /** Count of videos with {@code size_bytes IS NULL}, optionally per volume. */
    long countWithoutSize(String volumeId);

    /** Overwrite just the {@code size_bytes} column for one video. */
    void updateSize(long videoId, long sizeBytes);

    void delete(long id);

    void deleteByTitle(long titleId);

    /** Remove all video records for a volume (used before a full re-sync). */
    void deleteByVolume(String volumeId);

    /**
     * Remove all video records whose titles belong to the given volume and partition
     * (used before a partition-scoped re-sync).
     */
    void deleteByVolumeAndPartition(String volumeId, String partitionId);

    /**
     * Returns unprobed (duration_sec IS NULL) videos that belong to titles flagged as
     * size-variant candidates (max/min size ratio >= minRatio, with at least minVideos
     * videos all having size_bytes populated). Cursor-paginated like {@link #findUnprobed}.
     */
    List<Video> findUnprobedForSizeVariants(long fromIdExclusive, int limit,
                                            double minRatio, int minVideos);

    /** Count of unprobed videos belonging to size-variant candidate titles. */
    long countUnprobedForSizeVariants(double minRatio, int minVideos);
}
