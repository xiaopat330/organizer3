package com.organizer3.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A video release, identified by its JAV code (e.g. "ABP-123").
 * Maps directly to the {@code titles} DB table.
 *
 * <p>{@code actressId} is nullable — titles in unstructured partitions (queue, archive, etc.)
 * have no associated actress until they are organized into a starred partition.
 *
 * <p>{@code baseCode} is the normalized form used for matching: label uppercased, number
 * zero-padded to 5 digits (e.g. "ABP-00123"). Used to correlate titles with their video files.
 *
 * <p>Location information (volume, partition, path) is stored in {@link TitleLocation} records.
 * A title may exist in multiple physical locations across volumes.
 *
 * <p>Enrichment fields ({@code titleOriginal}, {@code titleEnglish}, {@code releaseDate},
 * {@code notes}, {@code tags}) are populated by the {@code load actress} command from curated
 * YAML research data, not from sync.
 */
@Value
@Builder(toBuilder = true)
public class Title {

    Long id;
    String code;
    String baseCode;
    String label;
    Integer seqNum;
    @With Long actressId;
    boolean favorite;
    boolean bookmark;
    Actress.Grade grade;
    boolean rejected;

    // --- Enrichment fields (populated via load actress command) ---
    String titleOriginal;
    String titleEnglish;
    LocalDate releaseDate;
    String notes;

    @Builder.Default
    List<String> tags = List.of();

    @Builder.Default
    List<TitleLocation> locations = List.of();

    /** Returns the earliest added date across all locations. */
    public LocalDate getAddedDate() {
        return locations.stream()
                .map(TitleLocation::getAddedDate)
                .filter(d -> d != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Returns the most recent lastSeenAt across all locations. */
    public LocalDate getLastSeenAt() {
        return locations.stream()
                .map(TitleLocation::getLastSeenAt)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Returns the path from the first location, or null if no locations. */
    public Path getPath() {
        return locations.isEmpty() ? null : locations.get(0).getPath();
    }

    /** Returns the volumeId from the first location, or null if no locations. */
    public String getVolumeId() {
        return locations.isEmpty() ? null : locations.get(0).getVolumeId();
    }

    /** Returns the partitionId from the first location, or null if no locations. */
    public String getPartitionId() {
        return locations.isEmpty() ? null : locations.get(0).getPartitionId();
    }

    /** Finds a location on a specific volume. */
    public Optional<TitleLocation> findLocation(String volumeId) {
        return locations.stream()
                .filter(loc -> loc.getVolumeId().equals(volumeId))
                .findFirst();
    }

    /** Returns true if this title has more than one location (duplicate). */
    public boolean isDuplicate() {
        return locations.size() > 1;
    }
}
