package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * A physical location where a {@link Title} exists on a volume.
 * A title may exist in multiple locations (different volumes, different partitions,
 * or even multiple copies on the same volume).
 */
@Value
@Builder(toBuilder = true)
public class TitleLocation {
    Long id;
    long titleId;
    String volumeId;
    String partitionId;
    Path path;
    LocalDate lastSeenAt;
    LocalDate addedDate;
}
