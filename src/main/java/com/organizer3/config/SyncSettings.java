package com.organizer3.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level {@code sync:} block from {@code organizer-config.yaml}.
 *
 * <p>Distinct from {@link com.organizer3.config.sync.StructureSyncConfig} which configures
 * per-structure scan partitions. This block carries library-wide sync behaviour settings.
 */
public record SyncSettings(
        @JsonProperty("staleGraceDays") Integer staleGraceDays
) {
    /** Default grace period (days) before a stale title_location row is swept. */
    public static final int DEFAULT_STALE_GRACE_DAYS = 90;

    /** Singleton default instance — all fields use their defaults. */
    public static final SyncSettings DEFAULTS = new SyncSettings(null);

    /** Returns the configured grace days, or the default (90) if unset. */
    public int staleGraceDaysOrDefault() {
        return staleGraceDays != null ? staleGraceDays : DEFAULT_STALE_GRACE_DAYS;
    }
}
