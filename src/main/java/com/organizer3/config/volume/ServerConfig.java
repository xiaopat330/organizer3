package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Credentials for a single SMB server, shared across all volumes on that server.
 * Referenced by volumes via {@link VolumeConfig#server()}.
 */
public record ServerConfig(
        @JsonProperty("id")       String id,
        @JsonProperty("username") String username,
        @JsonProperty("password") String password
) {}
