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
 *   <li>{@code load actress --strict <slug>} — load one file, fail if actress not already in DB
 *   <li>{@code load actresses} — load all files under {@code resources/actresses/}
 *   <li>{@code load actresses --strict} — load all, fail any YAML that would create a new actress
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
        return "Load actress YAML data into the DB: load actress [--strict] <slug> | load actresses [--strict]";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        // Dispatcher merges two-word command name into args[0]:
        // "load actress <slug>" → args = ["load actress", "<slug>"]
        // "load actresses"      → args = ["load actresses"]
        boolean loadAll = args[0].equalsIgnoreCase("load actresses");

        if (loadAll) {
            boolean strict = args.length >= 2 && "--strict".equalsIgnoreCase(args[1]);
            loadAll(io, strict);
        } else {
            // parse --strict flag from remaining args
            boolean strict = false;
            String slug = null;
            for (int i = 1; i < args.length; i++) {
                if ("--strict".equalsIgnoreCase(args[i])) {
                    strict = true;
                } else {
                    slug = args[i];
                }
            }
            if (slug == null) {
                io.println("Usage: load actress [--strict] <slug> | load actresses [--strict]");
                io.println("  slug is the filename without .yaml, e.g. nana_ogura");
                io.println("  --strict: fail if no existing actress matches (no phantom creation)");
            } else {
                loadOne(slug, io, strict);
            }
        }
    }

    private void loadOne(String slug, CommandIO io, boolean strict) {
        try {
            ActressYamlLoader.LoadResult result = loader.loadOne(slug, strict);
            printResult(result, io);
        } catch (IllegalArgumentException e) {
            io.println("Not found: " + e.getMessage());
        } catch (IOException e) {
            io.println("Error loading " + slug + ": " + e.getMessage());
        }
    }

    private void loadAll(CommandIO io, boolean strict) {
        try {
            List<ActressYamlLoader.LoadResult> results = loader.loadAll(strict);
            if (results.isEmpty()) {
                io.println("No actress YAML files found in resources/actresses/");
                return;
            }
            results.forEach(r -> printResult(r, io));
            long createdCount = results.stream().filter(ActressYamlLoader.LoadResult::created).count();
            io.println("Done. Loaded " + results.size() + " actress" + (results.size() == 1 ? "" : "es") + ".");
            if (createdCount > 0) {
                io.println("NEW actresses created (" + createdCount + "):");
                results.stream()
                        .filter(ActressYamlLoader.LoadResult::created)
                        .forEach(r -> io.println("  [NEW] " + r.canonicalName() + " (id=" + r.actressId() + ")"));
            }
        } catch (IOException e) {
            io.println("Error loading actresses: " + e.getMessage());
        }
    }

    private static void printResult(ActressYamlLoader.LoadResult result, CommandIO io) {
        String prefix = result.created() ? "[NEW] " : "      ";
        io.println(String.format("%s%-30s  +%d titles created, %d enriched",
                prefix, result.canonicalName(), result.titlesCreated(), result.titlesEnriched()));
    }
}
