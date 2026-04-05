package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Root of the {@code organizer-config.yaml} config tree.
 */
public record OrganizerConfig(
        @JsonProperty("volumes") List<VolumeConfig> volumes
) {
    public Optional<VolumeConfig> findById(String id) {
        return volumes.stream().filter(v -> v.id().equals(id)).findFirst();
    }
}
