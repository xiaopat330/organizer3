package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RevalidationCronConfig(
        @JsonProperty("enabled")            Boolean enabled,
        @JsonProperty("intervalHours")      Integer intervalHours,
        @JsonProperty("drainBatchSize")     Integer drainBatchSize,
        @JsonProperty("safetyNetBatchSize") Integer safetyNetBatchSize
) {
    public static final RevalidationCronConfig DEFAULTS = new RevalidationCronConfig(
            true, 168, 100, 200);

    public boolean enabledOrDefault()          { return enabled != null ? enabled : true; }
    public int intervalHoursOrDefault()        { return intervalHours != null ? intervalHours : 168; }
    public int drainBatchSizeOrDefault()       { return drainBatchSize != null ? drainBatchSize : 100; }
    public int safetyNetBatchSizeOrDefault()   { return safetyNetBatchSize != null ? safetyNetBatchSize : 200; }
}
