package com.organizer3.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.organizer3.config.volume.EnrichmentConfig;
import com.organizer3.config.volume.OrganizerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentAssistConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_areSafeOffMode() {
        EnrichmentAssistConfig d = EnrichmentAssistConfig.defaults();

        assertEquals("off", d.mode());
        assertEquals("phi4", d.primaryModel());
        assertEquals("gemma3:12b", d.secondaryModel());
        assertEquals(60, d.sweeperIntervalSeconds());
        assertEquals(60, d.autoApplyDelaySeconds());
        assertEquals("v7-kanji-bridge", d.promptVersion());
    }

    @Test
    void deserializes_fromValidYaml() throws Exception {
        String src = """
                mode: shadow
                primaryModel: phi4
                secondaryModel: gemma3:12b
                sweeperIntervalSeconds: 30
                autoApplyDelaySeconds: 90
                promptVersion: v8-test
                """;
        EnrichmentAssistConfig cfg = yaml.readValue(src, EnrichmentAssistConfig.class);

        assertEquals("shadow", cfg.mode());
        assertEquals(30, cfg.sweeperIntervalSeconds());
        assertEquals(90, cfg.autoApplyDelaySeconds());
        assertEquals("v8-test", cfg.promptVersion());
    }

    @Test
    void enrichmentBlock_withoutAssist_returnsOffDefaults() throws Exception {
        String src = """
                draftGcHourUtc: 3
                """;
        EnrichmentConfig cfg = yaml.readValue(src, EnrichmentConfig.class);

        assertNull(cfg.assist(), "raw assist field should be null when missing");
        assertEquals("off", cfg.assistOrDefaults().mode());
        assertEquals("phi4", cfg.assistOrDefaults().primaryModel());
    }

    @Test
    void enrichmentBlock_withAssist_threadsThrough() throws Exception {
        String src = """
                draftGcHourUtc: 3
                assist:
                  mode: shadow
                  primaryModel: phi4
                  secondaryModel: gemma3:12b
                  sweeperIntervalSeconds: 60
                  autoApplyDelaySeconds: 60
                  promptVersion: v7-kanji-bridge
                """;
        EnrichmentConfig cfg = yaml.readValue(src, EnrichmentConfig.class);

        assertNotNull(cfg.assist());
        assertEquals("shadow", cfg.assist().mode());
        assertEquals("shadow", cfg.assistOrDefaults().mode());
    }

    @Test
    void organizerConfig_withoutEnrichmentBlock_assistDefaultsToOff() throws Exception {
        // Minimal config with no enrichment block at all
        String src = """
                appName: test
                servers: []
                volumes: []
                structures: []
                syncConfig: []
                """;
        OrganizerConfig cfg = yaml.readValue(src, OrganizerConfig.class);

        assertNull(cfg.enrichment());
        assertEquals("off", cfg.enrichmentOrDefaults().assistOrDefaults().mode());
    }
}
