package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Defines the {@code stars/} tree found in conventional volumes.
 *
 * <p>{@code path} is the folder name relative to the volume root (always {@code stars}).
 * {@code partitions} are the tier sub-folders inside {@code stars/}
 * (library, minor, popular, superstar, goddess, favorites, archive). Each sub-folder contains
 * actress folders, and each actress folder contains title folders.
 */
public record StructuredPartitionDef(
        @JsonProperty("path")       String path,
        @JsonProperty("partitions") List<PartitionDef> partitions
) {}
