package com.organizer3.config.volume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads {@code organizer-config.yaml} from the classpath into an {@link OrganizerConfig}.
 */
public class OrganizerConfigLoader {

    private static final String CONFIG_PATH = "/organizer-config.yaml";

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public OrganizerConfig load() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                throw new IOException("organizer-config.yaml not found on classpath");
            }
            return mapper.readValue(in, OrganizerConfig.class);
        }
    }
}
