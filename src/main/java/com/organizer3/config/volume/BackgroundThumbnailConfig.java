package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Config for the background thumbnail sync worker.
 *
 * <p>See {@code spec/PROPOSAL_BACKGROUND_THUMBNAILS.md}.
 */
public record BackgroundThumbnailConfig(
        @JsonProperty("enabled")               Boolean enabled,
        @JsonProperty("quietThresholdSec")     Integer quietThresholdSec,
        @JsonProperty("maxCandidatesPerCycle") Integer maxCandidatesPerCycle,
        @JsonProperty("idleSleepSec")          Integer idleSleepSec,
        @JsonProperty("startupDelaySec")       Integer startupDelaySec,
        @JsonProperty("generationTimeoutSec")  Integer generationTimeoutSec,
        @JsonProperty("evictionDays")          Integer evictionDays
) {

    public static final BackgroundThumbnailConfig DEFAULTS = new BackgroundThumbnailConfig(
            false, 30, 200, 300, 600, 300, 30);

    public boolean enabledOrDefault()               { return enabled != null ? enabled : false; }
    public int quietThresholdSecOrDefault()         { return quietThresholdSec != null ? quietThresholdSec : 30; }
    public int maxCandidatesPerCycleOrDefault()     { return maxCandidatesPerCycle != null ? maxCandidatesPerCycle : 200; }
    public int idleSleepSecOrDefault()              { return idleSleepSec != null ? idleSleepSec : 300; }
    public int startupDelaySecOrDefault()           { return startupDelaySec != null ? startupDelaySec : 600; }
    public int generationTimeoutSecOrDefault()      { return generationTimeoutSec != null ? generationTimeoutSec : 300; }
    public int evictionDaysOrDefault()              { return evictionDays != null ? evictionDays : 30; }
}
