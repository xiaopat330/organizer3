package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Describes the full folder layout for a structure type (conventional, queue, etc.).
 *
 * <p>Keyed by {@code id} which matches the {@code structureType} field in {@link VolumeConfig}.
 * Multiple volumes can share the same structure definition.
 *
 * <p>{@code unstructuredPartitions} are top-level folders that contain title folders directly.
 * {@code structuredPartition} (nullable) is the {@code stars/} tree present only in conventional
 * volumes — it contains actress sub-folders rather than titles directly.
 */
public record VolumeStructureDef(
        @JsonProperty("id")                      String id,
        @JsonProperty("unstructuredPartitions")  List<PartitionDef> unstructuredPartitions,
        @JsonProperty("structuredPartition")     StructuredPartitionDef structuredPartition
) {
    /** Find an unstructured partition by logical id. */
    public Optional<PartitionDef> findUnstructuredById(String partitionId) {
        return unstructuredPartitions.stream()
                .filter(p -> p.id().equals(partitionId))
                .findFirst();
    }
}
