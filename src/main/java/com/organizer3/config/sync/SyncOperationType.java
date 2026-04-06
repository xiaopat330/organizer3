package com.organizer3.config.sync;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Determines what scope a sync command covers.
 *
 * <p>The string values match the YAML config (e.g., {@code operation: full}).
 */
public enum SyncOperationType {
    @JsonProperty("full")      FULL,
    @JsonProperty("partition") PARTITION
}
