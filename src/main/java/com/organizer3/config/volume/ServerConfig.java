package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Credentials for a single SMB server, shared across all volumes on that server.
 * Referenced by volumes via {@link VolumeConfig#server()}.
 */
public record ServerConfig(
        @JsonProperty("id")       String id,
        @JsonProperty("username") String username,
        @JsonProperty("password") String password,
        @JsonProperty("domain")   String domain,
        @JsonProperty("trash")    String trash,
        @JsonProperty("sandbox")  String sandbox
) {
    public String domainOrEmpty() {
        return domain != null ? domain : "";
    }

    /** Legacy constructor for callers/tests that don't specify trash/sandbox. */
    public ServerConfig(String id, String username, String password, String domain) {
        this(id, username, password, domain, null, null);
    }
}
