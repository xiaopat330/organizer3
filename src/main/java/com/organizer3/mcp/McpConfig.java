package com.organizer3.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level configuration for the embedded MCP server.
 *
 * <p>The block is optional in {@code organizer-config.yaml}. When absent, defaults are:
 * enabled, mounted at {@code /mcp}, mutations disabled.
 */
public record McpConfig(
        @JsonProperty("enabled")        Boolean enabled,
        @JsonProperty("path")           String path,
        @JsonProperty("allowMutations") Boolean allowMutations
) {
    public static McpConfig defaults() {
        return new McpConfig(true, "/mcp", false);
    }

    public boolean isEnabled()        { return enabled == null ? true : enabled; }
    public String  effectivePath()    { return (path == null || path.isBlank()) ? "/mcp" : path; }
    public boolean mutationsAllowed() { return allowMutations != null && allowMutations; }
}
