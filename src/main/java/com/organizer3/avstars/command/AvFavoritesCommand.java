package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Lists favorited AV actresses sorted by name.
 *
 * <p>Usage: {@code av favorites}
 */
@RequiredArgsConstructor
public class AvFavoritesCommand implements Command {

    private final AvActressRepository actressRepo;

    @Override
    public String name() {
        return "av favorites";
    }

    @Override
    public String description() {
        return "List favorited AV actresses";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        List<AvActress> favorites = actressRepo.findFavorites();
        if (favorites.isEmpty()) {
            io.println("No AV favorites yet. Use 'av actress <name>' to view and curate.");
            return;
        }

        io.println(String.format("%-40s  %6s  %8s  %s",
                "Name", "Videos", "Size", "Grade"));
        io.println("-".repeat(65));

        for (AvActress a : favorites) {
            io.println(String.format("%-40s  %6d  %8s  %s",
                    truncate(a.getStageName(), 40),
                    a.getVideoCount(),
                    AvActressesCommand.formatSize(a.getTotalSizeBytes()),
                    a.getGrade() != null ? "[" + a.getGrade() + "]" : ""));
        }
        io.println("\n" + favorites.size() + " favorite(s)");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
