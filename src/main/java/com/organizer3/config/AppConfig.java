package com.organizer3.config;

import com.organizer3.config.volume.OrganizerConfig;

/**
 * Application-wide configuration singleton.
 *
 * <p>Mirrors the Spring {@code @ConfigurationProperties} pattern without a DI container.
 * Loaded once at startup by the composition root ({@link com.organizer3.Application}) and
 * then accessible anywhere via {@link #get()}.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AppConfig.get().volumes().findById("a")
 * }</pre>
 *
 * <p><b>Initialization (composition root only):</b>
 * <pre>{@code
 * AppConfig.initialize(new OrganizerConfigLoader().load());
 * }</pre>
 *
 * <p><b>Testing:</b> Use {@link #initializeForTest} to inject a custom config, and call
 * {@link #reset} in {@code @AfterEach} to prevent state leaking between tests.
 */
public class AppConfig {

    private static AppConfig instance;

    private final OrganizerConfig volumes;

    private AppConfig(OrganizerConfig volumes) {
        this.volumes = volumes;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initializes the singleton from the given loaded config. Call once at application startup.
     *
     * @throws IllegalStateException if called more than once without a reset
     */
    public static void initialize(OrganizerConfig volumes) {
        if (instance != null) {
            throw new IllegalStateException("AppConfig is already initialized");
        }
        instance = new AppConfig(volumes);
    }

    /**
     * Returns the singleton instance.
     *
     * @throws IllegalStateException if {@link #initialize} has not been called
     */
    public static AppConfig get() {
        if (instance == null) {
            throw new IllegalStateException("AppConfig has not been initialized — call AppConfig.initialize() at startup");
        }
        return instance;
    }

    /**
     * Resets the singleton. For use in tests only.
     */
    public static void reset() {
        instance = null;
    }

    /**
     * Initializes with the given config, replacing any existing instance. For use in tests only.
     */
    public static void initializeForTest(OrganizerConfig volumes) {
        instance = new AppConfig(volumes);
    }

    // -------------------------------------------------------------------------
    // Config accessors
    // -------------------------------------------------------------------------

    /** Volume definitions from {@code organizer-config.yaml}. */
    public OrganizerConfig volumes() {
        return volumes;
    }
}
