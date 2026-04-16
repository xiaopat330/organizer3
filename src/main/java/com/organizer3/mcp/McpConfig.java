package com.organizer3.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level configuration for the embedded MCP server.
 *
 * <p>The block is optional in {@code organizer-config.yaml}. When absent, defaults are:
 * enabled, mounted at {@code /mcp}, mutations and network ops disabled.
 */
public record McpConfig(
        @JsonProperty("enabled")         Boolean enabled,
        @JsonProperty("path")            String path,
        @JsonProperty("allowMutations")  Boolean allowMutations,
        @JsonProperty("allowNetworkOps") Boolean allowNetworkOps
) {
    public static McpConfig defaults() {
        return new McpConfig(true, "/mcp", false, false);
    }

    /**
     * Legacy two-flag constructor for tests and configs that predate {@code allowNetworkOps}.
     * Defaults the new flag to {@code false}.
     */
    public McpConfig(Boolean enabled, String path, Boolean allowMutations) {
        this(enabled, path, allowMutations, false);
    }

    public boolean isEnabled()         { return enabled == null ? true : enabled; }
    public String  effectivePath()     { return (path == null || path.isBlank()) ? "/mcp" : path; }
    public boolean mutationsAllowed()  { return allowMutations != null && allowMutations; }
    public boolean networkOpsAllowed() { return allowNetworkOps != null && allowNetworkOps; }
}
