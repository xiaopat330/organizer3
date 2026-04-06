package com.organizer3.config.sync;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The set of sync commands available for a given structure type.
 *
 * <p>Loaded from the {@code syncConfig} section of {@code organizer-config.yaml}.
 * Each entry associates a structure type (e.g., {@code conventional}) with a list of
 * {@link SyncCommandDef} entries, so the available sync terms are fully config-driven.
 */
public record StructureSyncConfig(
        @JsonProperty("structureType") String structureType,
        @JsonProperty("commands")      List<SyncCommandDef> commands
) {}
