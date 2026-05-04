package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.organizer3.config.sync.StructureSyncConfig;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.mcp.McpConfig;
import com.organizer3.translation.TranslationConfig;

import java.util.List;
import java.util.Optional;

/**
 * Root of the {@code organizer-config.yaml} config tree.
 */
public record OrganizerConfig(
        @JsonProperty("appName")            String appName,
        @JsonProperty("dataDir")            String dataDir,
        @JsonProperty("maxBrowseTitles")    Integer maxBrowseTitles,
        @JsonProperty("maxRandomTitles")    Integer maxRandomTitles,
        @JsonProperty("maxRandomActresses") Integer maxRandomActresses,
        @JsonProperty("thumbnailInterval")  Integer thumbnailInterval,
        @JsonProperty("thumbnailColumns")   Integer thumbnailColumns,
        @JsonProperty("coverCropPercent")   Integer coverCropPercent,
        @JsonProperty("servers")            List<ServerConfig> servers,
        @JsonProperty("volumes")         List<VolumeConfig> volumes,
        @JsonProperty("structures")      List<VolumeStructureDef> structures,
        @JsonProperty("syncConfig")      List<StructureSyncConfig> syncConfig,
        @JsonProperty("backup")          BackupConfig backup,
        @JsonProperty("mcp")             McpConfig mcp,
        @JsonProperty("library")         LibraryConfig library,
        @JsonProperty("normalize")       NormalizeConfig normalize,
        @JsonProperty("media")           MediaConfig media,
        @JsonProperty("backgroundThumbnails") BackgroundThumbnailConfig backgroundThumbnails,
        @JsonProperty("javdb")               JavdbConfig javdb,
        @JsonProperty("enrichment")          EnrichmentConfig enrichment,
        @JsonProperty("translation")         TranslationConfig translation
) {
    /** Legacy ctor for tests that predate the organize-pipeline blocks. */
    public OrganizerConfig(String appName, String dataDir,
                           Integer maxBrowseTitles, Integer maxRandomTitles, Integer maxRandomActresses,
                           Integer thumbnailInterval, Integer thumbnailColumns, Integer coverCropPercent,
                           List<ServerConfig> servers, List<VolumeConfig> volumes,
                           List<VolumeStructureDef> structures, List<StructureSyncConfig> syncConfig,
                           BackupConfig backup, McpConfig mcp) {
        this(appName, dataDir, maxBrowseTitles, maxRandomTitles, maxRandomActresses,
             thumbnailInterval, thumbnailColumns, coverCropPercent,
             servers, volumes, structures, syncConfig, backup, mcp, null, null, null, null, null, null, null);
    }

    /** Legacy ctor for test sites that predate the {@code mcp:} block. */
    public OrganizerConfig(String appName, String dataDir,
                           Integer maxBrowseTitles, Integer maxRandomTitles, Integer maxRandomActresses,
                           Integer thumbnailInterval, Integer thumbnailColumns, Integer coverCropPercent,
                           List<ServerConfig> servers, List<VolumeConfig> volumes,
                           List<VolumeStructureDef> structures, List<StructureSyncConfig> syncConfig,
                           BackupConfig backup) {
        this(appName, dataDir, maxBrowseTitles, maxRandomTitles, maxRandomActresses,
             thumbnailInterval, thumbnailColumns, coverCropPercent,
             servers, volumes, structures, syncConfig, backup, null, null, null, null, null, null, null, null);
    }

    /** Returns the background-thumbnail config, or defaults (disabled) if unset. */
    public BackgroundThumbnailConfig backgroundThumbnailsOrDefaults() {
        return backgroundThumbnails != null ? backgroundThumbnails : BackgroundThumbnailConfig.DEFAULTS;
    }

    /** Returns the library config, or defaults if unset. */
    public LibraryConfig libraryOrDefaults() {
        return library != null ? library : LibraryConfig.DEFAULTS;
    }

    /** Returns the normalize config, or an empty one if unset. */
    public NormalizeConfig normalizeOrEmpty() {
        return normalize != null ? normalize : NormalizeConfig.EMPTY;
    }

    /** Returns the media config, or defaults if unset. */
    public MediaConfig mediaOrDefaults() {
        return media != null ? media : MediaConfig.DEFAULTS;
    }

    /** Returns the javdb config, or defaults if unset. */
    public JavdbConfig javdbOrDefaults() {
        return javdb != null ? javdb : JavdbConfig.DEFAULTS;
    }

    /** Returns the enrichment config, or defaults if unset. */
    public EnrichmentConfig enrichmentOrDefaults() {
        return enrichment != null ? enrichment : EnrichmentConfig.DEFAULTS;
    }

    /** Returns the translation config, or defaults if unset. */
    public TranslationConfig translationOrDefaults() {
        return translation != null ? translation : TranslationConfig.DEFAULTS;
    }

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
