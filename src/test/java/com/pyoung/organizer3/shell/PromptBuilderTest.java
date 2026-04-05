package com.pyoung.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.PromptBuilder;
import com.organizer3.shell.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptBuilderTest {

    private PromptBuilder promptBuilder;
    private SessionContext session;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
        session = new SessionContext();
    }

    @Test
    void defaultPromptShowsDryRunAndNoVolume() {
        assertEquals("organizer [*DRYRUN*] > ", promptBuilder.build(session));
    }

    @Test
    void promptIncludesVolumeIdWhenMounted() {
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora"));
        assertEquals("organizer:vol-a [*DRYRUN*] > ", promptBuilder.build(session));
    }

    @Test
    void armedModeOmitsDryRunMarker() {
        session.setDryRun(false);
        assertEquals("organizer > ", promptBuilder.build(session));
    }

    @Test
    void armedModeWithMountedVolume() {
        session.setMountedVolume(new VolumeConfig("bg", "//pandora/jav_BG", "conventional", "pandora"));
        session.setDryRun(false);
        assertEquals("organizer:vol-bg > ", promptBuilder.build(session));
    }
}
