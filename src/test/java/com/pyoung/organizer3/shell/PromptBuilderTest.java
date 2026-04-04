package com.pyoung.organizer3.shell;

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
        session.setMountedVolumeId("a");
        assertEquals("organizer:vol-a [*DRYRUN*] > ", promptBuilder.build(session));
    }

    @Test
    void armedModeOmitsDryRunMarker() {
        session.setDryRun(false);
        assertEquals("organizer > ", promptBuilder.build(session));
    }

    @Test
    void armedModeWithMountedVolume() {
        session.setMountedVolumeId("bg");
        session.setDryRun(false);
        assertEquals("organizer:vol-bg > ", promptBuilder.build(session));
    }
}
