package com.organizer3.avstars.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.organizer3.avstars.model.AvTagDefinition;
import com.organizer3.avstars.repository.AvTagDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads {@code av_tags.yaml} from the data directory and upserts tag definitions.
 */
@Slf4j
@RequiredArgsConstructor
public class AvTagYamlLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final AvTagDefinitionRepository tagDefRepo;

    /**
     * Loads the YAML file at {@code yamlPath} and upserts all tag definitions.
     *
     * @return the number of definitions loaded
     * @throws IOException if the file is missing or malformed
     */
    public int load(Path yamlPath) throws IOException {
        List<AvTagYamlEntry> entries = YAML.readValue(
                yamlPath.toFile(),
                new TypeReference<List<AvTagYamlEntry>>() {});

        for (AvTagYamlEntry e : entries) {
            if (e.slug == null || e.slug.isBlank()) continue;
            String aliasesJson = null;
            if (e.aliases != null && !e.aliases.isEmpty()) {
                // Store as a JSON array for easy deserialization in AvTagsCommand
                aliasesJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(e.aliases);
            }
            tagDefRepo.upsert(AvTagDefinition.builder()
                    .slug(e.slug)
                    .displayName(e.displayName != null ? e.displayName : e.slug)
                    .category(e.category)
                    .aliasesJson(aliasesJson)
                    .build());
        }
        log.info("Loaded {} tag definitions from {}", entries.size(), yamlPath);
        return entries.size();
    }
}
