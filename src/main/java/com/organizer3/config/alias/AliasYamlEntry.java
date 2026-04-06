package com.organizer3.config.alias;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents one entry in aliases.yaml — a canonical actress name and her known aliases.
 *
 * <pre>
 * alias:
 *   - name: Aya Sazanami
 *     aliases:
 *       - Haruka Suzumiya
 *       - Aya Konami
 * </pre>
 */
public record AliasYamlEntry(
        @JsonProperty("name") String name,
        @JsonProperty("aliases") List<String> aliases
) {}
