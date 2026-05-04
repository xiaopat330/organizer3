package com.organizer3.translation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * Configuration for the local translation service, read from {@code organizer-config.yaml}
 * under the {@code translation:} key.
 *
 * <p>All fields have defaults so existing configs with no {@code translation:} block continue
 * to work without modification.
 */
public record TranslationConfig(
        @JsonProperty("ollamaBaseUrl")                  String ollamaBaseUrl,
        @JsonProperty("timeoutSeconds")                 Integer timeoutSeconds,
        @JsonProperty("primaryModel")                   String primaryModel,
        @JsonProperty("fallbackModel")                  String fallbackModel,
        @JsonProperty("workerPollIntervalSeconds")       Integer workerPollIntervalSeconds,
        @JsonProperty("maxAttempts")                    Integer maxAttempts,
        @JsonProperty("stuckThresholdSeconds")          Integer stuckThresholdSeconds,
        @JsonProperty("sweeperIntervalSeconds")         Integer sweeperIntervalSeconds,
        @JsonProperty("tier2BatchSize")                 Integer tier2BatchSize,
        @JsonProperty("tier2MaxWaitMinutes")            Integer tier2MaxWaitMinutes,
        @JsonProperty("tier2SweeperIntervalSeconds")    Integer tier2SweeperIntervalSeconds
) {
    /** Default configuration — {@code http://localhost:11434}, 120s, gemma4:e4b / qwen2.5:14b. */
    public static final TranslationConfig DEFAULTS = new TranslationConfig(
            "http://localhost:11434",
            120,
            "gemma4:e4b",
            "qwen2.5:14b",
            2,
            3,
            600,
            300,
            10,
            60,
            300
    );

    public String ollamaBaseUrlOrDefault() {
        return ollamaBaseUrl != null ? ollamaBaseUrl : "http://localhost:11434";
    }

    public Duration timeoutOrDefault() {
        return Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 120);
    }

    public String primaryModelOrDefault() {
        return primaryModel != null ? primaryModel : "gemma4:e4b";
    }

    public String fallbackModelOrDefault() {
        return fallbackModel != null ? fallbackModel : "qwen2.5:14b";
    }

    public int workerPollIntervalSecondsOrDefault() {
        return workerPollIntervalSeconds != null ? workerPollIntervalSeconds : 2;
    }

    public int maxAttemptsOrDefault() {
        return maxAttempts != null ? maxAttempts : 3;
    }

    public int stuckThresholdSecondsOrDefault() {
        return stuckThresholdSeconds != null ? stuckThresholdSeconds : 600;
    }

    public int sweeperIntervalSecondsOrDefault() {
        return sweeperIntervalSeconds != null ? sweeperIntervalSeconds : 300;
    }

    /** Minimum number of tier_2_pending rows before the sweeper triggers a batch drain. */
    public int tier2BatchSizeOrDefault() {
        return tier2BatchSize != null ? tier2BatchSize : 10;
    }

    /** Maximum wait in minutes before draining tier_2_pending regardless of count. */
    public int tier2MaxWaitMinutesOrDefault() {
        return tier2MaxWaitMinutes != null ? tier2MaxWaitMinutes : 60;
    }

    /** Interval in seconds between tier-2 sweeper runs. */
    public int tier2SweeperIntervalSecondsOrDefault() {
        return tier2SweeperIntervalSeconds != null ? tier2SweeperIntervalSeconds : 300;
    }
}
