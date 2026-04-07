package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * A video release, identified by its JAV code (e.g. "ABP-123").
 * Maps directly to the {@code titles} DB table.
 *
 * <p>{@code actressId} is nullable — titles in unstructured partitions (queue, archive, etc.)
 * have no associated actress until they are organized into a starred partition.
 *
 * <p>{@code baseCode} is the normalized form used for matching: label uppercased, number
 * zero-padded to 5 digits (e.g. "ABP-00123"). Used to correlate titles with their video files.
 */
@Value
@Builder(toBuilder = true)
public class Title {

    Long id;
    String code;
    String baseCode;
    String label;
    Integer seqNum;
    String volumeId;
    String partitionId;
    Long actressId;
    Path path;
    LocalDate lastSeenAt;
    LocalDate addedDate;
    boolean favorite;
    boolean bookmark;
    Actress.Grade grade;
    boolean rejected;
}
