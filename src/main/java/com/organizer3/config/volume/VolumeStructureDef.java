package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
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
 *
 * <p>{@code ignoredSubfolders} (nullable, used by {@code avstars}) lists folder names that the
 * sync treewalk should skip. Matched case-insensitively against each folder's own name at any
 * depth in the tree.
 */
public record VolumeStructureDef(
        @JsonProperty("id")                      String id,
        @JsonProperty("unstructuredPartitions")  List<PartitionDef> unstructuredPartitions,
        @JsonProperty("structuredPartition")     StructuredPartitionDef structuredPartition,
        @JsonProperty("ignoredSubfolders")       List<String> ignoredSubfolders
) {
    /**
     * Convenience constructor for structure types that don't use {@code ignoredSubfolders}
     * (everything except {@code avstars}). Delegates to the canonical constructor with
     * {@code ignoredSubfolders = null}.
     */
    public VolumeStructureDef(String id, List<PartitionDef> unstructuredPartitions,
                               StructuredPartitionDef structuredPartition) {
        this(id, unstructuredPartitions, structuredPartition, null);
    }

    /** Returns the ignored subfolders list, never null. */
    public List<String> ignoredSubfolders() {
        return ignoredSubfolders != null ? ignoredSubfolders : Collections.emptyList();
    }
    /** Find an unstructured partition by logical id. */
    public Optional<PartitionDef> findUnstructuredById(String partitionId) {
        return unstructuredPartitions.stream()
                .filter(p -> p.id().equals(partitionId))
                .findFirst();
    }
}
