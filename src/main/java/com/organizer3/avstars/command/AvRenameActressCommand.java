package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Unified AV actress rename command. Handles both cases with one command:
 *
 * <ul>
 *   <li><b>Display rename only</b> — folder unchanged on disk; just sets the stage name
 *       so the actress appears correctly in the UI.</li>
 *   <li><b>Physical folder rename</b> — folder was renamed on disk and sync created a new row.
 *       Migrates all data (IAFD profile, curation, visit history) from the old orphaned row
 *       to the new row, then sets the stage name and deletes the orphan.</li>
 * </ul>
 *
 * <p>Usage: {@code av rename <current-name> > <new-name>}
 *
 * <p>The command auto-detects which case applies: if a separate row exists whose folder name
 * case-insensitively matches {@code new-name}, it performs the migration; otherwise it only
 * updates the stage name on the matched row.
 */
@RequiredArgsConstructor
public class AvRenameActressCommand implements Command {

    private final AvActressRepository actressRepo;

    @Override
    public String name() {
        return "av rename";
    }

    @Override
    public String description() {
        return "Rename an AV actress (updates display name or migrates a renamed folder): av rename <name> > <new name>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            printUsage(io);
            return;
        }

        String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (!joined.contains(" > ")) {
            printUsage(io);
            return;
        }

        String[] parts = joined.split(" > ", 2);
        String searchName = parts[0].trim();
        String newName    = parts[1].trim();

        if (searchName.isEmpty() || newName.isEmpty()) {
            printUsage(io);
            return;
        }

        List<AvActress> all = actressRepo.findAllByVideoCountDesc();

        // Find source: fuzzy match on stage name or folder name
        List<AvActress> sources = all.stream()
                .filter(a -> a.getStageName().toLowerCase().contains(searchName.toLowerCase())
                        || a.getFolderName().toLowerCase().contains(searchName.toLowerCase()))
                .toList();

        if (sources.isEmpty()) {
            io.println("No actress found matching '" + searchName + "'.");
            return;
        }
        if (sources.size() > 1) {
            io.println("Multiple matches for '" + searchName + "' — be more specific:");
            sources.forEach(a -> io.println("  " + a.getStageName() + "  (" + a.getFolderName() + ")"));
            return;
        }

        AvActress source = sources.get(0);

        // Look for a separate row whose folder name matches the new name (physical rename case)
        Optional<AvActress> targetOpt = all.stream()
                .filter(a -> !a.getId().equals(source.getId()))
                .filter(a -> a.getFolderName().equalsIgnoreCase(newName))
                .findFirst();

        if (targetOpt.isPresent()) {
            // Physical folder rename: migrate everything from old row to new row
            AvActress target = targetOpt.get();
            io.println("Found renamed folder: '" + source.getFolderName() + "' → '" + target.getFolderName() + "'");
            io.println("Migrating all profile and curation data...");
            actressRepo.migrateCuration(source.getId(), target.getId());
            actressRepo.updateStageName(target.getId(), newName);
            io.println("Done. '" + target.getFolderName() + "' is now: " + newName);
        } else {
            // Display rename only: just update stage name
            actressRepo.updateStageName(source.getId(), newName);
            io.println(source.getStageName() + " → " + newName);
        }
    }

    private static void printUsage(CommandIO io) {
        io.println("Usage: av rename <current-name> > <new-name>");
        io.println("  e.g. av rename alina lopez [teen face] > Alina Lopez");
    }
}
