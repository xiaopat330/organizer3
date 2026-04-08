package com.pyoung.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.PromptBuilder;
import com.organizer3.shell.SessionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    void unmountedPromptShowsUnmountedLabel() {
        assertEquals("[UNMOUNTED] ▶ ", promptBuilder.build(new SessionContext()).toString());
    }

    @Test
    void promptIncludesMountIdWhenMounted() {
        SessionContext session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null));
        assertEquals("[MOUNT → a] ▶ ", promptBuilder.build(session).toString());
    }

    @Test
    void promptIncludesMultiCharMountId() {
        SessionContext session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("bg", "//pandora/jav_BG", "conventional", "pandora", null));
        assertEquals("[MOUNT → bg] ▶ ", promptBuilder.build(session).toString());
    }
}
