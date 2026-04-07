package com.organizer3.command;

import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

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
        ActressesCommand.printActressTable("FAVORITES", actressRepo.findFavorites(), titleRepo, io);
    }
}
