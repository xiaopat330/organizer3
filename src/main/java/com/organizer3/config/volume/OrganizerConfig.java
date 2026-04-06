package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.organizer3.config.sync.StructureSyncConfig;

import java.util.List;
import java.util.Optional;

/**
 * Root of the {@code organizer-config.yaml} config tree.
 */
public record OrganizerConfig(
        @JsonProperty("servers")    List<ServerConfig> servers,
        @JsonProperty("volumes")    List<VolumeConfig> volumes,
        @JsonProperty("structures") List<VolumeStructureDef> structures,
        @JsonProperty("syncConfig") List<StructureSyncConfig> syncConfig
) {
    public Optional<VolumeConfig> findById(String id) {
        return volumes.stream().filter(v -> v.id().equals(id)).findFirst();
    }

    public Optional<ServerConfig> findServerById(String id) {
        return servers.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public Optional<VolumeStructureDef> findStructureById(String structureType) {
        return structures.stream().filter(s -> s.id().equals(structureType)).findFirst();
    }

    public Optional<StructureSyncConfig> findSyncConfigForStructure(String structureType) {
        return syncConfig.stream().filter(s -> s.structureType().equals(structureType)).findFirst();
    }
}
