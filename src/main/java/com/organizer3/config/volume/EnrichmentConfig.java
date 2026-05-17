package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.organizer3.config.EnrichmentAssistConfig;

public record EnrichmentConfig(
        @JsonProperty("revalidationCron") RevalidationCronConfig revalidationCron,
        @JsonProperty("draftGcHourUtc")   Integer draftGcHourUtc,
        @JsonProperty("draftMaxAgeDays")  Integer draftMaxAgeDays,
        @JsonProperty("assist")           EnrichmentAssistConfig assist
) {
    public static final EnrichmentConfig DEFAULTS = new EnrichmentConfig(null, null, null, null);

    /** Legacy ctor for tests that predate the {@code assist:} block. */
    public EnrichmentConfig(RevalidationCronConfig revalidationCron, Integer draftGcHourUtc, Integer draftMaxAgeDays) {
        this(revalidationCron, draftGcHourUtc, draftMaxAgeDays, null);
    }

    /** Returns the AI assist config, or off-mode defaults if unset. */
    public EnrichmentAssistConfig assistOrDefaults() {
        return assist != null ? assist : EnrichmentAssistConfig.defaults();
    }

    public RevalidationCronConfig revalidationCronOrDefaults() {
        return revalidationCron != null ? revalidationCron : RevalidationCronConfig.DEFAULTS;
    }

    /** UTC hour at which the draft GC sweep fires. Default: 2 (2am UTC). */
    public int draftGcHourUtcOrDefault() {
        return draftGcHourUtc != null ? draftGcHourUtc : 2;
    }

    /** Maximum age in days before a draft is considered stale and reaped. Default: 30. */
    public int draftMaxAgeDaysOrDefault() {
        return draftMaxAgeDays != null ? draftMaxAgeDays : 30;
    }
}
