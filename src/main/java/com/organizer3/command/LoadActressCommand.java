package com.organizer3.command;

import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.List;

/**
 * Loads curated actress YAML data from {@code resources/actresses/} into the database.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code load actress <slug>} — load one file (e.g. {@code load actress nana_ogura})
 *   <li>{@code load actresses} — load all files under {@code resources/actresses/}
 * </ul>
 *
 * <p>All enrichment fields are overwritten unconditionally. Operational fields
 * (tier, favorite, bookmark, grade, rejected) are never modified.
 */
@RequiredArgsConstructor
public class LoadActressCommand implements Command {

    private final ActressYamlLoader loader;

    @Override
    public String name() {
        return "load actress";
    }

    @Override
    public List<String> aliases() {
        return List.of("load actresses");
    }

    @Override
    public String description() {
        return "Load actress YAML data into the DB: load actress <slug> | load actresses";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        // Dispatcher merges two-word command name into args[0]:
        // "load actress <slug>" → args = ["load actress", "<slug>"]
        // "load actresses"      → args = ["load actresses"]
        boolean loadAll = args[0].equalsIgnoreCase("load actresses");

        if (loadAll) {
            loadAll(io);
        } else if (args.length >= 2) {
            loadOne(args[1], io);
        } else {
            io.println("Usage: load actress <slug> | load actresses");
            io.println("  slug is the filename without .yaml, e.g. nana_ogura");
        }
    }

    private void loadOne(String slug, CommandIO io) {
        try {
            ActressYamlLoader.LoadResult result = loader.loadOne(slug);
            printResult(result, io);
        } catch (IllegalArgumentException e) {
            io.println("Not found: " + e.getMessage());
        } catch (IOException e) {
            io.println("Error loading " + slug + ": " + e.getMessage());
        }
    }

    private void loadAll(CommandIO io) {
        try {
            List<ActressYamlLoader.LoadResult> results = loader.loadAll();
            if (results.isEmpty()) {
                io.println("No actress YAML files found in resources/actresses/");
                return;
            }
            results.forEach(r -> printResult(r, io));
            io.println("Done. Loaded " + results.size() + " actress" + (results.size() == 1 ? "" : "es") + ".");
        } catch (IOException e) {
            io.println("Error loading actresses: " + e.getMessage());
        }
    }

    private static void printResult(ActressYamlLoader.LoadResult result, CommandIO io) {
        io.println(String.format("%-30s  +%d titles created, %d enriched",
                result.canonicalName(), result.titlesCreated(), result.titlesEnriched()));
    }
}
