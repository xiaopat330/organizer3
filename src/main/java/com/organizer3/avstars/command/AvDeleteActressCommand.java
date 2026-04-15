package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * Permanently deletes an AV actress and all her associated videos, tags, and screenshots.
 *
 * <p>Usage: {@code av delete <name>}
 *
 * <p>Intended for removing placeholder or temp rows that were created during sync but
 * do not correspond to a real performer.
 */
@RequiredArgsConstructor
public class AvDeleteActressCommand implements Command {

    private final AvActressRepository actressRepo;

    @Override
    public String name() {
        return "av delete";
    }

    @Override
    public String description() {
        return "Permanently delete an AV actress and all her videos: av delete <name>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: av delete <name>");
            return;
        }

        String search = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        Optional<AvActress> match = findActress(search);

        if (match.isEmpty()) {
            io.println("No actress found matching '" + search + "'.");
            return;
        }

        AvActress actress = match.get();
        io.println("Found: " + actress.getStageName()
                + " (" + actress.getFolderName() + ")"
                + " — " + actress.getVideoCount() + " video(s)");
        io.println("This will permanently delete the actress and all her videos.");
        io.println();

        Optional<String> choice = io.pick(List.of("Yes, delete permanently", "Cancel"));
        if (choice.isEmpty() || choice.get().startsWith("Cancel")) {
            io.println("Cancelled.");
            return;
        }

        actressRepo.delete(actress.getId());
        io.println("Deleted: " + actress.getStageName());
    }

    private Optional<AvActress> findActress(String name) {
        String lower = name.toLowerCase();
        return actressRepo.findAllByVideoCountDesc().stream()
                .filter(a -> a.getStageName().equalsIgnoreCase(name)
                        || a.getStageName().toLowerCase().contains(lower)
                        || a.getFolderName().equalsIgnoreCase(name)
                        || a.getFolderName().toLowerCase().contains(lower))
                .findFirst();
    }
}
