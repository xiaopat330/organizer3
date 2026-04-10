package com.organizer3.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Seeds the {@code tags} table from the bundled {@code tags.yaml} classpath resource.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #seedIfEmpty()} — inserts all rows only when the table is empty. Called on startup.
 *   <li>{@link #reimport()} — clears all rows and re-inserts. Call when the YAML has been updated.
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class TagSeeder {

    private static final String RESOURCE = "/tags.yaml";

    private final Jdbi jdbi;

    /** Seeds the tags table if it is currently empty. */
    public void seedIfEmpty() {
        long count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM tags")
                        .mapTo(Long.class)
                        .one()
        );
        if (count == 0) {
            log.info("Tags table is empty — seeding from {}", RESOURCE);
            jdbi.useTransaction(this::insertAll);
        } else {
            log.debug("Tags table already has {} rows, skipping seed", count);
        }
    }

    /** Clears all existing tag data and re-imports from {@code tags.yaml}. */
    public void reimport() {
        log.info("Reimporting tags from {}", RESOURCE);
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM tags");
            insertAll(h);
        });
        log.info("Tags reimport complete");
    }

    private void insertAll(Handle h) {
        Map<String, List<TagEntry>> categories = loadYaml();
        int total = 0;
        for (Map.Entry<String, List<TagEntry>> entry : categories.entrySet()) {
            String category = entry.getKey();
            for (TagEntry tag : entry.getValue()) {
                h.createUpdate("""
                                INSERT OR IGNORE INTO tags (name, category, description)
                                VALUES (:name, :category, :description)
                                """)
                        .bind("name", tag.name())
                        .bind("category", category)
                        .bind("description", tag.description() != null ? tag.description() : "")
                        .execute();
                total++;
            }
        }
        log.info("Inserted {} tag rows", total);
    }

    private Map<String, List<TagEntry>> loadYaml() {
        try (InputStream is = TagSeeder.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Cannot find classpath resource: " + RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return mapper.readValue(is, new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + RESOURCE, e);
        }
    }

    public record TagEntry(String name, String description) {}
}
