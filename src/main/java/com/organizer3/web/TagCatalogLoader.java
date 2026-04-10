package com.organizer3.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads the tag vocabulary from the bundled {@code tags.yaml} resource,
 * returning groups in YAML declaration order with human-readable labels.
 */
public class TagCatalogLoader {

    private static final String RESOURCE = "/tags.yaml";

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "format",           "Format / Production",
            "production_style", "Production Style",
            "setting",          "Setting / Location",
            "role",             "Role / Character",
            "theme",            "Theme / Scenario",
            "act",              "Act / Focus",
            "body",             "Body / Aesthetic"
    );

    public record TagItem(String name, String description) {}
    public record TagGroup(String category, String label, List<TagItem> tags) {}

    /** Loads and returns all tag groups in YAML declaration order. */
    public List<TagGroup> load() {
        try (InputStream is = TagCatalogLoader.class.getResourceAsStream(RESOURCE)) {
            if (is == null) throw new IllegalStateException("Cannot find classpath resource: " + RESOURCE);
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            // LinkedHashMap preserves YAML key order
            Map<String, List<Map<String, String>>> raw = mapper.readValue(is, new TypeReference<>() {});
            return raw.entrySet().stream()
                    .map(e -> {
                        String cat  = e.getKey();
                        String lbl  = CATEGORY_LABELS.getOrDefault(cat, cat);
                        List<TagItem> tags = e.getValue().stream()
                                .map(m -> new TagItem(m.get("name"), m.get("description")))
                                .toList();
                        return new TagGroup(cat, lbl, tags);
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + RESOURCE, e);
        }
    }
}
