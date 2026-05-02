package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

/**
 * A row from {@code title_path_history} — records one (volume, partition, path) slot
 * that a title has ever been seen at, together with the first and last observation times.
 *
 * <p>No FK on {@code titleId} — rows must survive title deletion so a re-add can recover.
 */
@Value
@Builder
public class TitlePathHistoryEntry {
    long id;
    long titleId;
    String volumeId;
    String partitionId;
    String path;
    String firstSeenAt;
    String lastSeenAt;
}
