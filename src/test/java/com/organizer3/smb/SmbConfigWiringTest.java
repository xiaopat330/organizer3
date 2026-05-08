package com.organizer3.smb;

import com.hierynomus.smbj.SmbConfig;
import com.organizer3.config.SmbSettings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link SmbConnectionFactory#buildSmbConfig} and
 * {@link SmbjConnector#buildSmbConfig} apply the expected timeouts from a {@link SmbSettings}.
 *
 * <p>Uses the real smbj {@link SmbConfig} API (getReadTimeout etc.) to assert that
 * the builder method names and units are correct. This is a smoke test against
 * the smbj API surface — if the API changes in a future upgrade, this test will catch it.
 *
 * <p>See {@code spec/PROPOSAL_SMB_TIMEOUT_HARDENING.md §3.1}.
 */
class SmbConfigWiringTest {

    @Test
    void buildSmbConfig_factory_appliesReadTimeout() {
        SmbSettings settings = new SmbSettings(3, 4, 6, 120, 30);
        SmbConfig config = SmbConnectionFactory.buildSmbConfig(settings);

        long expectedMs = TimeUnit.MINUTES.toMillis(3);
        assertEquals(expectedMs, config.getReadTimeout(),
                "readTimeout should be set to " + expectedMs + " ms (3 minutes)");
    }

    @Test
    void buildSmbConfig_factory_appliesWriteTimeout() {
        SmbSettings settings = new SmbSettings(3, 4, 6, 120, 30);
        SmbConfig config = SmbConnectionFactory.buildSmbConfig(settings);

        long expectedMs = TimeUnit.MINUTES.toMillis(4);
        assertEquals(expectedMs, config.getWriteTimeout(),
                "writeTimeout should be set to " + expectedMs + " ms (4 minutes)");
    }

    @Test
    void buildSmbConfig_factory_appliesTransactTimeout() {
        SmbSettings settings = new SmbSettings(3, 4, 6, 120, 30);
        SmbConfig config = SmbConnectionFactory.buildSmbConfig(settings);

        long expectedMs = TimeUnit.MINUTES.toMillis(6);
        assertEquals(expectedMs, config.getTransactTimeout(),
                "transactTimeout should be set to " + expectedMs + " ms (6 minutes)");
    }

    @Test
    void buildSmbConfig_factory_appliesSoTimeout() {
        SmbSettings settings = new SmbSettings(3, 4, 6, 120, 30);
        SmbConfig config = SmbConnectionFactory.buildSmbConfig(settings);

        // soTimeout mirrors readTimeout (backstop at TCP level)
        int expectedMs = (int) TimeUnit.MINUTES.toMillis(3);
        assertEquals(expectedMs, config.getSoTimeout(),
                "soTimeout should mirror readTimeout: " + expectedMs + " ms");
    }

    @Test
    void buildSmbConfig_connector_matchesFactory() {
        SmbSettings settings = new SmbSettings(5, 5, 5, 120, 30);
        SmbConfig factoryConfig   = SmbConnectionFactory.buildSmbConfig(settings);
        SmbConfig connectorConfig = SmbjConnector.buildSmbConfig(settings);

        assertEquals(factoryConfig.getReadTimeout(),    connectorConfig.getReadTimeout());
        assertEquals(factoryConfig.getWriteTimeout(),   connectorConfig.getWriteTimeout());
        assertEquals(factoryConfig.getTransactTimeout(), connectorConfig.getTransactTimeout());
        assertEquals(factoryConfig.getSoTimeout(),      connectorConfig.getSoTimeout());
    }

    @Test
    void buildSmbConfig_defaults_noUnlimitedTimeouts() {
        // With default settings (all 5 min), no timeout value should be 0 (unlimited).
        SmbConfig config = SmbConnectionFactory.buildSmbConfig(SmbSettings.DEFAULTS);

        assertTrue(config.getReadTimeout() > 0,    "readTimeout must not be unlimited (0)");
        assertTrue(config.getWriteTimeout() > 0,   "writeTimeout must not be unlimited (0)");
        assertTrue(config.getTransactTimeout() > 0, "transactTimeout must not be unlimited (0)");
        assertTrue(config.getSoTimeout() > 0,      "soTimeout must not be unlimited (0)");
    }
}
