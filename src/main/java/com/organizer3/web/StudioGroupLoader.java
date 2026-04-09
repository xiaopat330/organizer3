package com.organizer3.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.organizer3.model.StudioGroup;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads the studio group definitions from the bundled {@code studios.yaml} resource.
 */
public class StudioGroupLoader {

    private static final String RESOURCE = "/studios.yaml";

    /** Loads and returns all studio groups in YAML declaration order. */
    public List<StudioGroup> load() {
        try (InputStream is = StudioGroupLoader.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Cannot find classpath resource: " + RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            List<Entry> entries = mapper.readerForListOf(Entry.class).readValue(is);
            return entries.stream()
                    .map(e -> new StudioGroup(e.name(), e.slug(), e.companies() != null ? e.companies() : List.of()))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + RESOURCE, e);
        }
    }

    private record Entry(
            @JsonProperty("name")      String name,
            @JsonProperty("slug")      String slug,
            @JsonProperty("companies") List<String> companies
    ) {}
}
