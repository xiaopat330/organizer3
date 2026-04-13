package com.organizer3.config;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private static final VolumeConfig VOLUME_A = new VolumeConfig(
            "a", "//pandora/jav_A", "conventional", "pandora", null);

    private static OrganizerConfig cfg(VolumeConfig... vols) {
        return new OrganizerConfig(null, null, null, null, null, null, null, null, List.of(), List.of(vols), List.of(), List.of());
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void get_throwsIfNotInitialized() {
        assertThrows(IllegalStateException.class, AppConfig::get);
    }

    @Test
    void initialize_makesConfigAccessibleViaGet() {
        AppConfig.initialize(cfg(VOLUME_A));

        assertNotNull(AppConfig.get());
        assertEquals(1, AppConfig.get().volumes().volumes().size());
        assertEquals("a", AppConfig.get().volumes().volumes().get(0).id());
    }

    @Test
    void initialize_throwsIfCalledTwice() {
        AppConfig.initialize(cfg(VOLUME_A));

        assertThrows(IllegalStateException.class, () -> AppConfig.initialize(cfg(VOLUME_A)));
    }

    @Test
    void reset_allowsReinitializationWithNewConfig() {
        VolumeConfig volumeB = new VolumeConfig(
                "b", "//pandora/jav_B", "conventional", "pandora", null);

        AppConfig.initialize(cfg(VOLUME_A));
        AppConfig.reset();
        AppConfig.initialize(cfg(volumeB));

        assertEquals("b", AppConfig.get().volumes().volumes().get(0).id());
    }

    @Test
    void initializeForTest_replacesExistingInstance() {
        AppConfig.initialize(cfg(VOLUME_A));
        VolumeConfig volumeB = new VolumeConfig(
                "b", "//pandora/jav_B", "conventional", "pandora", null);

        AppConfig.initializeForTest(cfg(volumeB));

        assertEquals("b", AppConfig.get().volumes().volumes().get(0).id());
    }

    @Test
    void volumes_findById_worksViaAppConfig() {
        AppConfig.initialize(cfg(VOLUME_A));

        assertTrue(AppConfig.get().volumes().findById("a").isPresent());
        assertFalse(AppConfig.get().volumes().findById("zzz").isPresent());
    }
}
