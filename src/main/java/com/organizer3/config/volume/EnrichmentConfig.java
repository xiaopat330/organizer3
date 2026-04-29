package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnrichmentConfig(
        @JsonProperty("revalidationCron") RevalidationCronConfig revalidationCron
) {
    public static final EnrichmentConfig DEFAULTS = new EnrichmentConfig(null);

    public RevalidationCronConfig revalidationCronOrDefaults() {
        return revalidationCron != null ? revalidationCron : RevalidationCronConfig.DEFAULTS;
    }
}
