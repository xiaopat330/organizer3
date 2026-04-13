package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * Moves curation data (favorite, bookmark, rejected, grade, notes, IAFD linkage)
 * from an orphaned {@code av_actresses} row to the row for the actress's renamed folder.
 *
 * <p>Sync does not attempt fuzzy matching when folder names change — this command
 * handles it manually. Both actress rows must already exist in the database.
 *
 * <p>Usage: {@code av migrate <old-folder-name> <new-folder-name>}
 *
 * <p>The old and new names must be exact folder names (case-sensitive), not fuzzy queries,
 * to avoid accidental data moves.
 */
@RequiredArgsConstructor
public class AvMigrateActressCommand implements Command {

    private final AvActressRepository actressRepo;

    @Override
    public String name() {
        return "av migrate";
    }

    @Override
    public String description() {
        return "Move curation from a renamed/orphaned actress row: av migrate <old-name> <new-name>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        // args[0] = "av migrate", args[1] = old name, args[2] = new name
        // Names may contain spaces — we split on " > " as the separator if present,
        // otherwise require exactly 3 tokens.
        String joined = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : "";

        String oldName;
        String newName;

        if (joined.contains(" > ")) {
            String[] parts = joined.split(" > ", 2);
            oldName = parts[0].trim();
            newName = parts[1].trim();
        } else if (args.length == 3) {
            oldName = args[1];
            newName = args[2];
        } else {
            io.println("Usage: av migrate <old-folder-name> > <new-folder-name>");
            io.println("  or:  av migrate <old> <new>  (single-word names without spaces)");
            io.println("");
            io.println("Both names must be exact folder names, not fuzzy queries.");
            return;
        }

        if (oldName.isEmpty() || newName.isEmpty()) {
            io.println("Both old and new names are required.");
            return;
        }
        if (oldName.equalsIgnoreCase(newName)) {
            io.println("Old and new names are the same — nothing to migrate.");
            return;
        }

        // Look up both rows — search across all volumes
        Optional<AvActress> fromOpt = findByFolderName(oldName);
        Optional<AvActress> toOpt = findByFolderName(newName);

        if (fromOpt.isEmpty()) {
            io.println("No actress row found for old name: '" + oldName + "'");
            io.println("Run 'av actresses' to see all known folder names.");
            return;
        }
        if (toOpt.isEmpty()) {
            io.println("No actress row found for new name: '" + newName + "'");
            io.println("Run 'av sync' first so the new folder is indexed, then retry.");
            return;
        }

        AvActress from = fromOpt.get();
        AvActress to = toOpt.get();

        // Show what will be moved and ask for confirmation
        io.println("Will migrate curation from:");
        io.println("  FROM: " + from.getFolderName() + "  (id=" + from.getId() + ")");
        printCurationSummary(from, io);
        io.println("  TO:   " + to.getFolderName() + "  (id=" + to.getId() + ")");
        io.println("");

        boolean hasCuration = from.isFavorite() || from.isBookmark() || from.isRejected()
                || from.getGrade() != null || from.getNotes() != null || from.getIafdId() != null;
        if (!hasCuration) {
            io.println("Source row has no curation data to migrate.");
            io.println("The source row will still be deleted.");
        }

        actressRepo.migrateCuration(from.getId(), to.getId());
        io.println("Migration complete. Source row deleted.");
    }

    /** Searches all volumes for an exact folder name match. */
    private Optional<AvActress> findByFolderName(String folderName) {
        return actressRepo.findAllByVideoCountDesc().stream()
                .filter(a -> a.getFolderName().equals(folderName))
                .findFirst();
    }

    private static void printCurationSummary(AvActress a, CommandIO io) {
        StringBuilder sb = new StringBuilder("        ");
        if (a.isFavorite())  sb.append("♥ fav  ");
        if (a.isBookmark())  sb.append("⊕ bookmark  ");
        if (a.isRejected())  sb.append("✗ rejected  ");
        if (a.getGrade() != null) sb.append("grade=").append(a.getGrade()).append("  ");
        if (a.getIafdId() != null) sb.append("iafd=").append(a.getIafdId()).append("  ");
        if (sb.toString().isBlank()) sb.append("(no curation)");
        io.println(sb.toString().stripTrailing());
    }
}
