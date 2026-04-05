package com.organizer3.config.sync;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Binds a user-facing command term to a sync operation for a specific structure type.
 *
 * <p>{@code term} is what the user types (e.g., {@code sync-queue}, {@code sync-all}).
 * {@code operation} determines the scan scope. {@code partitions} lists the logical partition
 * ids to scan — only meaningful when {@code operation} is {@link SyncOperationType#PARTITION}.
 */
public record SyncCommandDef(
        @JsonProperty("term")       String term,
        @JsonProperty("operation")  SyncOperationType operation,
        @JsonProperty("partitions") List<String> partitions
) {}
