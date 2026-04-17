package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * Configuration for a single NAS volume, loaded from {@code organizer-config.yaml}.
 *
 * <p>{@code smbPath} uses forward-slash UNC notation (e.g., {@code //pandora/jav_A}).
 * {@code server} references a {@link ServerConfig} id whose credentials are used to
 * authenticate the SMB connection.
 *
 * <p>{@code letters} is an optional list of name-prefixes this volume covers. Each
 * entry is a prefix matched case-insensitively against the actress's canonical name.
 * Examples:
 * <ul>
 *   <li>{@code [A]} on volume {@code a} — single-letter match.</li>
 *   <li>{@code [B, C, D, E, F, G]} on volume {@code bg} — multiple single-letter
 *       prefixes, any one matches.</li>
 *   <li>{@code [Ma]} on volume {@code ma} — two-letter prefix match only.</li>
 * </ul>
 *
 * <p>When {@code letters} is unset, letter-mismatch detection is skipped for this
 * volume (e.g. for non-conventional structure types like {@code exhibition},
 * {@code collections}). See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §2.
 */
public record VolumeConfig(
        @JsonProperty("id")            String id,
        @JsonProperty("smbPath")       String smbPath,
        @JsonProperty("structureType") String structureType,
        @JsonProperty("server")        String server,
        @JsonProperty("group")         String group,
        @JsonProperty("letters")       List<String> letters
) {

    /** Backward-compatible ctor without {@code letters}. */
    public VolumeConfig(String id, String smbPath, String structureType, String server, String group) {
        this(id, smbPath, structureType, server, group, null);
    }

    /**
     * Whether this volume is a valid placement for an actress with the given canonical name.
     * Returns {@code true} if {@link #letters} is unset or empty (no constraint). Otherwise
     * returns {@code true} iff the name starts with at least one of the configured prefixes
     * (case-insensitive).
     */
    public boolean coversName(String actressCanonicalName) {
        if (letters == null || letters.isEmpty()) return true;
        if (actressCanonicalName == null || actressCanonicalName.isEmpty()) return false;
        String nameLower = actressCanonicalName.toLowerCase(Locale.ROOT);
        for (String prefix : letters) {
            if (prefix == null || prefix.isEmpty()) continue;
            if (nameLower.startsWith(prefix.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
