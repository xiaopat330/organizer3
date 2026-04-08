package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for a single NAS volume, loaded from {@code organizer-config.yaml}.
 *
 * <p>{@code smbPath} uses forward-slash UNC notation (e.g., {@code //pandora/jav_A}).
 * {@code server} references a {@link ServerConfig} id whose credentials are used to
 * authenticate the SMB connection.
 */
public record VolumeConfig(
        @JsonProperty("id")            String id,
        @JsonProperty("smbPath")       String smbPath,
        @JsonProperty("structureType") String structureType,
        @JsonProperty("server")        String server,
        @JsonProperty("group")         String group
) {}
