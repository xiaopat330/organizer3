package com.organizer3.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code export aliases} — serialize the current alias DB table to a YAML file
 * in the same format as {@code aliases.yaml}, for backup or re-seeding purposes.
 *
 * <p>Output path defaults to {@code <dataDir>/aliases-export.yaml}.
 * Pass an optional path argument to override, e.g. {@code export aliases /tmp/backup.yaml}.
 */
@Slf4j
@RequiredArgsConstructor
public class ExportAliasesCommand implements Command {

    private final ActressRepository actressRepo;
    private final Path dataDir;

    @Override
    public String name() { return "export aliases"; }

    @Override
    public String description() { return "Serialize alias DB table to YAML (backup format)"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        Path target = args.length > 0 && !args[0].isBlank()
                ? Path.of(args[0])
                : dataDir.resolve("aliases-export.yaml");

        List<AliasYamlEntry> entries = actressRepo.exportAliases();

        if (ctx.isDryRun()) {
            io.println(String.format("[DRY RUN] Would export %,d alias entries to %s", entries.size(), target));
            io.println("Run 'arm' to enable writing.");
            return;
        }

        try {
            ObjectMapper yaml = new ObjectMapper(
                    new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("alias", entries);
            yaml.writeValue(target.toFile(), root);
            io.println(String.format("Exported %,d alias entries to %s", entries.size(), target));
        } catch (Exception e) {
            io.println("Export failed: " + e.getMessage());
            log.error("Failed to export aliases to {}", target, e);
        }
    }
}
