package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * A record of a title being watched.
 * Keyed on {@code titleCode} (stable across moves and re-syncs).
 */
@Value
@Builder
public class WatchHistory {
    Long id;
    String titleCode;
    LocalDateTime watchedAt;
}
