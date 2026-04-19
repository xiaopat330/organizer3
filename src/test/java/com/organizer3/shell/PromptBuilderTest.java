package com.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
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

    @Test
    void liveBadgeAbsentInDryRunMode() {
        SessionContext session = new SessionContext();
        String prompt = promptBuilder.build(session).toString();
        org.junit.jupiter.api.Assertions.assertFalse(prompt.contains("LIVE"), "no live badge in dry-run");
    }

    @Test
    void liveBadgePresentWhenArmed() {
        SessionContext session = new SessionContext();
        session.setDryRun(false);
        String prompt = promptBuilder.build(session).toString();
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("*LIVE*"));
    }

    @Test
    void liveBadgeAppearsAfterMountBadge() {
        SessionContext session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("bg", "//pandora/jav_BG", "conventional", "pandora", null));
        session.setDryRun(false);
        assertEquals("[MOUNT → bg] [*LIVE*] ▶ ", promptBuilder.build(session).toString());
    }
}
