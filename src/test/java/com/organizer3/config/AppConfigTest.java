package com.organizer3.config;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private static final VolumeConfig VOLUME_A = new VolumeConfig(
            "a", "//pandora/jav_A", Path.of("/Volumes/jav_A"), "conventional", "pandora", "patrick");

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
        AppConfig.initialize(new OrganizerConfig(List.of(VOLUME_A)));

        assertNotNull(AppConfig.get());
        assertEquals(1, AppConfig.get().volumes().volumes().size());
        assertEquals("a", AppConfig.get().volumes().volumes().get(0).id());
    }

    @Test
    void initialize_throwsIfCalledTwice() {
        AppConfig.initialize(new OrganizerConfig(List.of(VOLUME_A)));

        assertThrows(IllegalStateException.class,
                () -> AppConfig.initialize(new OrganizerConfig(List.of(VOLUME_A))));
    }

    @Test
    void reset_allowsReinitializationWithNewConfig() {
        VolumeConfig volumeB = new VolumeConfig(
                "b", "//pandora/jav_B", Path.of("/Volumes/jav_B"), "conventional", "pandora", "patrick");

        AppConfig.initialize(new OrganizerConfig(List.of(VOLUME_A)));
        AppConfig.reset();
        AppConfig.initialize(new OrganizerConfig(List.of(volumeB)));

        assertEquals("b", AppConfig.get().volumes().volumes().get(0).id());
    }

    @Test
    void initializeForTest_replacesExistingInstance() {
        AppConfig.initialize(new OrganizerConfig(List.of(VOLUME_A)));
        VolumeConfig volumeB = new VolumeConfig(
                "b", "//pandora/jav_B", Path.of("/Volumes/jav_B"), "conventional", "pandora", "patrick");

        AppConfig.initializeForTest(new OrganizerConfig(List.of(volumeB)));

        assertEquals("b", AppConfig.get().volumes().volumes().get(0).id());
    }

    @Test
    void volumes_findById_worksViaAppConfig() {
        AppConfig.initialize(new OrganizerConfig(List.of(VOLUME_A)));

        assertTrue(AppConfig.get().volumes().findById("a").isPresent());
        assertFalse(AppConfig.get().volumes().findById("zzz").isPresent());
    }
}
