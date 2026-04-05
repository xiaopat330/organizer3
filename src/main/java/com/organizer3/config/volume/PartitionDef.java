package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Identifies a single partition folder within a volume structure.
 *
 * <p>{@code id} is the logical name used in config references (e.g., in syncConfig partitions lists
 * and as the {@code partition_id} stored in the database). {@code path} is the actual folder name
 * on disk relative to the partition's parent directory.
 *
 * <p>Used for both unstructured top-level partitions (e.g., id="queue", path="queue") and
 * sub-partitions under the structured {@code stars/} tree (e.g., id="popular", path="popular").
 * For queue-type volumes the names diverge: id="queue", path="fresh".
 */
public record PartitionDef(
        @JsonProperty("id")   String id,
        @JsonProperty("path") String path
) {}
