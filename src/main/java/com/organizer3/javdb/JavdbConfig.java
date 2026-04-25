package com.organizer3.javdb;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Config for the javdb enrichment runner.
 *
 * <p>Mapped from the {@code javdb:} block in {@code organizer-config.yaml}.
 */
public record JavdbConfig(
        @JsonProperty("enabled")               Boolean enabled,
        @JsonProperty("rateLimitPerSec")        Double rateLimitPerSec,
        @JsonProperty("maxAttempts")            Integer maxAttempts,
        @JsonProperty("backoffMinutes")         int[] backoffMinutes,
        @JsonProperty("rate429PauseMinutes")    Integer rate429PauseMinutes,
        @JsonProperty("userAgent")              String userAgent
) {

    public static final JavdbConfig DEFAULTS = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null);

    public boolean enabledOrDefault()           { return enabled != null ? enabled : true; }
    public double rateLimitPerSecOrDefault()    { return rateLimitPerSec != null ? rateLimitPerSec : 1.0; }
    public int maxAttemptsOrDefault()           { return maxAttempts != null ? maxAttempts : 3; }
    public int[] backoffMinutesOrDefault()      { return backoffMinutes != null ? backoffMinutes : new int[]{1, 5, 30}; }
    public int rate429PauseMinutesOrDefault()   { return rate429PauseMinutes != null ? rate429PauseMinutes : 5; }
}
