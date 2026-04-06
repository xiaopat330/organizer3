package com.organizer3.command;

import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.util.List;
import java.util.Map;

/**
 * Lists all favorited actresses with their title counts, sorted by most to least.
 *
 * <p>Usage: {@code favorites}
 */
public class FavoritesCommand implements Command {

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;

    public FavoritesCommand(ActressRepository actressRepo, TitleRepository titleRepo) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
    }

    @Override
    public String name() {
        return "favorites";
    }

    @Override
    public String description() {
        return "List all favorited actresses with title counts";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        List<Actress> favorites = actressRepo.findFavorites();

        List<Map.Entry<Actress, Integer>> withCounts = favorites.stream()
                .map(a -> Map.entry(a, titleRepo.countByActress(a.getId())))
                .sorted(Map.Entry.<Actress, Integer>comparingByValue().reversed())
                .toList();

        io.println("FAVORITES  (" + withCounts.size() + " actress" + (withCounts.size() == 1 ? "" : "es") + ")");
        io.println(String.format("  %-40s  %s", "NAME", "TITLES"));
        io.println("  " + "-".repeat(48));

        for (var entry : withCounts) {
            io.println(String.format("  %-40s  %d", entry.getKey().getCanonicalName(), entry.getValue()));
        }
    }
}
