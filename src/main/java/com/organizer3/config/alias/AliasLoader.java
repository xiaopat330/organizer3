package com.organizer3.config.alias;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and parses aliases.yaml into a list of {@link AliasYamlEntry} objects.
 *
 * <p>The file may be reloaded at any time by calling {@link #load(Path)} again.
 * The caller (typically {@code ActressRepository.importFromYaml}) is responsible for
 * persisting the result to the DB.
 */
public class AliasLoader {

    private static final Logger log = LoggerFactory.getLogger(AliasLoader.class);

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /**
     * Parse the given aliases.yaml file and return its entries.
     *
     * @param path path to aliases.yaml
     * @return list of alias entries (never null, may be empty)
     * @throws IOException if the file cannot be read or parsed
     */
    public List<AliasYamlEntry> load(Path path) throws IOException {
        log.info("Loading aliases from {}", path);
        AliasYamlRoot root = yaml.readValue(path.toFile(), AliasYamlRoot.class);
        List<AliasYamlEntry> entries = root.alias() != null ? root.alias() : List.of();
        log.info("Loaded {} alias entries from {}", entries.size(), path);
        return entries;
    }

    /** Internal root wrapper matching the top-level {@code alias:} key in the YAML file. */
    private record AliasYamlRoot(@JsonProperty("alias") List<AliasYamlEntry> alias) {}
}
