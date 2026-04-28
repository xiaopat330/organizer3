package com.organizer3.javdb;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Config for the javdb enrichment runner.
 *
 * <p>Mapped from the {@code javdb:} block in {@code organizer-config.yaml}.
 */
public record JavdbConfig(
        @JsonProperty("enabled")                 Boolean enabled,
        @JsonProperty("rateLimitPerSec")         Double rateLimitPerSec,
        @JsonProperty("maxAttempts")             Integer maxAttempts,
        @JsonProperty("backoffMinutes")          int[] backoffMinutes,
        @JsonProperty("rate429PauseMinutes")     Integer rate429PauseMinutes,
        @JsonProperty("userAgent")               String userAgent,
        @JsonProperty("sessionCookie")           String sessionCookie,
        @JsonProperty("burstSize")               Integer burstSize,
        @JsonProperty("burstBreakMinutes")       Integer burstBreakMinutes,
        @JsonProperty("profileChainMinTitles")   Integer profileChainMinTitles
) {

    public static final JavdbConfig DEFAULTS = new JavdbConfig(true, 0.1, 3, new int[]{1, 5, 30}, 15, null, null, 4, 30, 3);

    public boolean enabledOrDefault()               { return enabled != null ? enabled : true; }
    public double rateLimitPerSecOrDefault()        { return rateLimitPerSec != null ? rateLimitPerSec : 0.1; }
    public int maxAttemptsOrDefault()               { return maxAttempts != null ? maxAttempts : 3; }
    public int[] backoffMinutesOrDefault()          { return backoffMinutes != null ? backoffMinutes : new int[]{1, 5, 30}; }
    public int rate429PauseMinutesOrDefault()       { return rate429PauseMinutes != null ? rate429PauseMinutes : 15; }
    public int burstSizeOrDefault()                 { return burstSize != null ? burstSize : 4; }
    public int burstBreakMinutesOrDefault()         { return burstBreakMinutes != null ? burstBreakMinutes : 30; }
    public int profileChainMinTitlesOrDefault()     { return profileChainMinTitles != null ? profileChainMinTitles : 3; }
}
