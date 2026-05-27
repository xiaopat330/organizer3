package com.organizer3.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level {@code smb:} block from {@code organizer-config.yaml}.
 *
 * <p>Controls SMB client timeouts and per-volume watchdog budgets. The defaults are
 * conservative: 5 minutes per SMB call (single directory list / metadata read should never
 * legitimately exceed a few minutes even on a sluggish NAS), 2 hours per volume (a full
 * letter-volume scan over slow SMB can take 30–60 min; double gives headroom), and 30
 * seconds for unmount (a dead connection's unmount call can hang; 30 s is plenty for a
 * healthy one).
 *
 * <p>{@code dialTimeoutSeconds} is intentionally much shorter than the read/write/transact
 * timeouts: TCP connect + SMB session-setup on a LAN should complete in well under a second;
 * the 10-second default is a generous backstop that still prevents thread saturation when
 * the NAS host is reachable by ping but its SMB service is wedged.
 *
 * <p>See {@code spec/PROPOSAL_SMB_TIMEOUT_HARDENING.md §3.1–3.3} for design rationale.
 */
public record SmbSettings(
        @JsonProperty("readTimeoutMinutes")      Integer readTimeoutMinutes,
        @JsonProperty("writeTimeoutMinutes")     Integer writeTimeoutMinutes,
        @JsonProperty("transactTimeoutMinutes")  Integer transactTimeoutMinutes,
        @JsonProperty("perVolumeTimeoutMinutes") Integer perVolumeTimeoutMinutes,
        @JsonProperty("unmountTimeoutSeconds")   Integer unmountTimeoutSeconds,
        @JsonProperty("dialTimeoutSeconds")      Integer dialTimeoutSeconds
) {
    public static final int DEFAULT_READ_TIMEOUT_MINUTES      = 5;
    public static final int DEFAULT_WRITE_TIMEOUT_MINUTES     = 5;
    public static final int DEFAULT_TRANSACT_TIMEOUT_MINUTES  = 5;
    public static final int DEFAULT_PER_VOLUME_TIMEOUT_MINUTES = 120;
    public static final int DEFAULT_UNMOUNT_TIMEOUT_SECONDS   = 30;
    public static final int DEFAULT_DIAL_TIMEOUT_SECONDS      = 10;

    /** Singleton default instance — all fields use their defaults. */
    public static final SmbSettings DEFAULTS = new SmbSettings(null, null, null, null, null, null);

    public int readTimeoutMinutesOrDefault() {
        return readTimeoutMinutes != null ? readTimeoutMinutes : DEFAULT_READ_TIMEOUT_MINUTES;
    }

    public int writeTimeoutMinutesOrDefault() {
        return writeTimeoutMinutes != null ? writeTimeoutMinutes : DEFAULT_WRITE_TIMEOUT_MINUTES;
    }

    public int transactTimeoutMinutesOrDefault() {
        return transactTimeoutMinutes != null ? transactTimeoutMinutes : DEFAULT_TRANSACT_TIMEOUT_MINUTES;
    }

    public int perVolumeTimeoutMinutesOrDefault() {
        return perVolumeTimeoutMinutes != null ? perVolumeTimeoutMinutes : DEFAULT_PER_VOLUME_TIMEOUT_MINUTES;
    }

    public int unmountTimeoutSecondsOrDefault() {
        return unmountTimeoutSeconds != null ? unmountTimeoutSeconds : DEFAULT_UNMOUNT_TIMEOUT_SECONDS;
    }

    public int dialTimeoutSecondsOrDefault() {
        return dialTimeoutSeconds != null ? dialTimeoutSeconds : DEFAULT_DIAL_TIMEOUT_SECONDS;
    }
}
