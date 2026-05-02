package com.organizer3.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ActressSummary serializes all expected fields, including hasCustomAvatar.
 */
class ActressSummarySerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hasCustomAvatar_defaultsFalse() {
        ActressSummary summary = ActressSummary.builder()
                .id(1L)
                .canonicalName("Aya Sazanami")
                .tier("LIBRARY")
                .build();

        assertFalse(summary.isHasCustomAvatar());
    }

    @Test
    void hasCustomAvatar_setTrue() {
        ActressSummary summary = ActressSummary.builder()
                .id(2L)
                .canonicalName("Sora Aoi")
                .tier("LIBRARY")
                .localAvatarUrl("/actress-custom-avatars/2.jpg")
                .hasCustomAvatar(true)
                .build();

        assertTrue(summary.isHasCustomAvatar());
        assertEquals("/actress-custom-avatars/2.jpg", summary.getLocalAvatarUrl());
    }

    @Test
    void serialization_includesHasCustomAvatar() throws Exception {
        ActressSummary summary = ActressSummary.builder()
                .id(3L)
                .canonicalName("Hibiki Otsuki")
                .tier("LIBRARY")
                .hasCustomAvatar(true)
                .build();

        String json = MAPPER.writeValueAsString(summary);
        assertTrue(json.contains("\"hasCustomAvatar\":true"), "JSON should contain hasCustomAvatar:true, got: " + json);
    }

    @Test
    void serialization_hasCustomAvatarFalseWhenNotSet() throws Exception {
        ActressSummary summary = ActressSummary.builder()
                .id(4L)
                .canonicalName("Yua Aida")
                .tier("LIBRARY")
                .build();

        String json = MAPPER.writeValueAsString(summary);
        assertTrue(json.contains("\"hasCustomAvatar\":false"), "JSON should contain hasCustomAvatar:false, got: " + json);
    }
}
