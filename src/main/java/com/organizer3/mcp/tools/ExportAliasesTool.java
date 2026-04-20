package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export the current alias DB table to a YAML file in the same format as
 * {@code aliases.yaml}, for backup or re-seeding purposes.
 *
 * <p>The output path defaults to {@code <dataDir>/aliases-export.yaml}.
 * Pass an explicit {@code path} argument to override.
 */
@Slf4j
public class ExportAliasesTool implements Tool {

    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    private final ActressRepository actressRepo;
    private final Path dataDir;

    public ExportAliasesTool(ActressRepository actressRepo, Path dataDir) {
        this.actressRepo = actressRepo;
        this.dataDir = dataDir;
    }

    @Override public String name()        { return "export_aliases"; }
    @Override public String description() {
        return "Serialize the current alias DB table to a YAML file (same format as aliases.yaml). "
             + "Use for backup or re-seeding. Defaults to <dataDir>/aliases-export.yaml.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("path", "string", "Output file path. Defaults to <dataDir>/aliases-export.yaml.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String pathArg = Schemas.optString(args, "path", null);
        Path target = (pathArg != null && !pathArg.isBlank())
                ? Path.of(pathArg)
                : dataDir.resolve("aliases-export.yaml");

        List<AliasYamlEntry> entries = actressRepo.exportAliases();

        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("alias", entries);
            YAML.writeValue(target.toFile(), root);
            log.info("Exported {} alias entries to {}", entries.size(), target);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write aliases to " + target + ": " + e.getMessage(), e);
        }

        return new Result(entries.size(), target.toString());
    }

    public record Result(int entriesExported, String path) {}
}
