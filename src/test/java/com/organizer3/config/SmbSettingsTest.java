package com.organizer3.config;

import com.organizer3.config.volume.OrganizerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SmbSettings} — default fallbacks and explicit-value overrides.
 * Also exercises {@link OrganizerConfig#smbOrDefaults()} plumbing.
 */
class SmbSettingsTest {

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    // ── Default fallbacks ─────────────────────────────────────────────────────

    @Test
    void defaults_appliedWhenSmbBlockAbsent() {
        SmbSettings settings = SmbSettings.DEFAULTS;

        assertEquals(SmbSettings.DEFAULT_READ_TIMEOUT_MINUTES,       settings.readTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_WRITE_TIMEOUT_MINUTES,      settings.writeTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_TRANSACT_TIMEOUT_MINUTES,   settings.transactTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_PER_VOLUME_TIMEOUT_MINUTES, settings.perVolumeTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_UNMOUNT_TIMEOUT_SECONDS,    settings.unmountTimeoutSecondsOrDefault());
        assertEquals(SmbSettings.DEFAULT_DIAL_TIMEOUT_SECONDS,       settings.dialTimeoutSecondsOrDefault());
        assertEquals(SmbSettings.DEFAULT_READ_TIMEOUT_SECONDS,       settings.readTimeoutSecondsOrDefault());
        assertEquals(SmbSettings.DEFAULT_TRANSACT_TIMEOUT_SECONDS,   settings.transactTimeoutSecondsOrDefault());
    }

    @Test
    void defaults_matchExpectedValues() {
        // Sanity-check the canonical defaults match the spec (§3.1–3.3).
        assertEquals(5,   SmbSettings.DEFAULT_READ_TIMEOUT_MINUTES);
        assertEquals(5,   SmbSettings.DEFAULT_WRITE_TIMEOUT_MINUTES);
        assertEquals(5,   SmbSettings.DEFAULT_TRANSACT_TIMEOUT_MINUTES);
        assertEquals(120, SmbSettings.DEFAULT_PER_VOLUME_TIMEOUT_MINUTES);
        assertEquals(30,  SmbSettings.DEFAULT_UNMOUNT_TIMEOUT_SECONDS);
        assertEquals(10,  SmbSettings.DEFAULT_DIAL_TIMEOUT_SECONDS);
        assertEquals(45,  SmbSettings.DEFAULT_READ_TIMEOUT_SECONDS);
        assertEquals(45,  SmbSettings.DEFAULT_TRANSACT_TIMEOUT_SECONDS);
    }

    @Test
    void readTimeoutSeconds_defaultIs45() {
        assertEquals(45, SmbSettings.DEFAULTS.readTimeoutSecondsOrDefault());
    }

    @Test
    void readTimeoutSeconds_explicitValueOverridesDefault() {
        SmbSettings settings = new SmbSettings(null, null, null, null, null, null, 90, null, null, null, null, null, null, null, null, null, null);
        assertEquals(90, settings.readTimeoutSecondsOrDefault());
    }

    @Test
    void transactTimeoutSeconds_defaultIs45() {
        assertEquals(45, SmbSettings.DEFAULTS.transactTimeoutSecondsOrDefault());
    }

    @Test
    void transactTimeoutSeconds_explicitValueOverridesDefault() {
        SmbSettings settings = new SmbSettings(null, null, null, null, null, null, null, 60, null, null, null, null, null, null, null, null, null);
        assertEquals(60, settings.transactTimeoutSecondsOrDefault());
    }

    @Test
    void dialBackoff_defaults() {
        assertEquals(3,  SmbSettings.DEFAULTS.dialBackoffThresholdOrDefault());
        assertEquals(60, SmbSettings.DEFAULTS.dialBackoffWindowSecondsOrDefault());
        assertEquals(30, SmbSettings.DEFAULTS.dialBackoffCooldownSecondsOrDefault());
    }

    @Test
    void dialBackoff_explicitValuesOverrideDefaults() {
        SmbSettings settings = new SmbSettings(null, null, null, null, null, null, null, null, null, 5, 90, 15, null, null, null, null, null);
        assertEquals(5,  settings.dialBackoffThresholdOrDefault());
        assertEquals(90, settings.dialBackoffWindowSecondsOrDefault());
        assertEquals(15, settings.dialBackoffCooldownSecondsOrDefault());
    }

    @Test
    void wave3_defaults() {
        assertEquals(30, SmbSettings.DEFAULTS.poolSweepIntervalSecondsOrDefault());
        assertEquals(3,  SmbSettings.DEFAULTS.livenessProbeTimeoutSecondsOrDefault());
        assertTrue(SmbSettings.DEFAULTS.networkChangeTeardownEnabledOrDefault());
    }

    @Test
    void wave3_explicitValuesOverrideDefaults() {
        SmbSettings settings = new SmbSettings(
                null, null, null, null, null, null, null, null, null, null, null, null, 15, 2, false, null, null);
        assertEquals(15, settings.poolSweepIntervalSecondsOrDefault());
        assertEquals(2,  settings.livenessProbeTimeoutSecondsOrDefault());
        assertFalse(settings.networkChangeTeardownEnabledOrDefault());
    }

    @Test
    void dialTimeoutSeconds_defaultIs10() {
        assertEquals(10, SmbSettings.DEFAULTS.dialTimeoutSecondsOrDefault());
    }

    @Test
    void dialTimeoutSeconds_explicitValueOverridesDefault() {
        SmbSettings settings = new SmbSettings(null, null, null, null, null, 30, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(30, settings.dialTimeoutSecondsOrDefault());
    }

    // ── Explicit overrides ────────────────────────────────────────────────────

    @Test
    void explicitValues_overrideDefaults() {
        SmbSettings settings = new SmbSettings(10, 15, 20, 60, 45, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(10, settings.readTimeoutMinutesOrDefault());
        assertEquals(15, settings.writeTimeoutMinutesOrDefault());
        assertEquals(20, settings.transactTimeoutMinutesOrDefault());
        assertEquals(60, settings.perVolumeTimeoutMinutesOrDefault());
        assertEquals(45, settings.unmountTimeoutSecondsOrDefault());
    }

    @Test
    void partialOverride_unsetFieldsUseDefaults() {
        // Only readTimeoutMinutes is set; all others should fall back to defaults.
        SmbSettings settings = new SmbSettings(3, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(3,   settings.readTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_WRITE_TIMEOUT_MINUTES,      settings.writeTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_TRANSACT_TIMEOUT_MINUTES,   settings.transactTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_PER_VOLUME_TIMEOUT_MINUTES, settings.perVolumeTimeoutMinutesOrDefault());
        assertEquals(SmbSettings.DEFAULT_UNMOUNT_TIMEOUT_SECONDS,    settings.unmountTimeoutSecondsOrDefault());
    }

    // ── OrganizerConfig plumbing ──────────────────────────────────────────────

    @Test
    void smbOrDefaults_returnsDefaults_whenSmbFieldNull() {
        // Legacy ctor leaves smb=null
        OrganizerConfig config = new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null);

        SmbSettings settings = config.smbOrDefaults();

        assertNotNull(settings);
        assertEquals(SmbSettings.DEFAULT_READ_TIMEOUT_MINUTES, settings.readTimeoutMinutesOrDefault());
    }

    @Test
    void smbOrDefaults_returnsConfiguredValues_whenSmbFieldPresent() {
        SmbSettings custom = new SmbSettings(7, 8, 9, 45, 10, null, null, null, null, null, null, null, null, null, null, null, null);
        // Use the full canonical ctor with smb= custom
        OrganizerConfig config = new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null,
                null, null, null, null, null, null, null, null, custom, null);

        SmbSettings settings = config.smbOrDefaults();

        assertEquals(7,  settings.readTimeoutMinutesOrDefault());
        assertEquals(8,  settings.writeTimeoutMinutesOrDefault());
        assertEquals(9,  settings.transactTimeoutMinutesOrDefault());
        assertEquals(45, settings.perVolumeTimeoutMinutesOrDefault());
        assertEquals(10, settings.unmountTimeoutSecondsOrDefault());
    }

    @Test
    void appConfig_smbOrDefaults_readableAtRuntime() {
        AppConfig.initializeForTest(new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null));

        SmbSettings settings = AppConfig.get().volumes().smbOrDefaults();

        assertNotNull(settings);
        // No smb: block → should use defaults
        assertEquals(SmbSettings.DEFAULT_PER_VOLUME_TIMEOUT_MINUTES, settings.perVolumeTimeoutMinutesOrDefault());
    }
}
