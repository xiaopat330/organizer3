package com.organizer3.command;

import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Lists all actresses in a given tier with their title counts, sorted by most to least.
 *
 * <p>Usage: {@code actresses <tier>}
 * <p>Tier values (case-insensitive): library, minor, popular, superstar, goddess
 */
public class ActressesCommand implements Command {

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;

    public ActressesCommand(ActressRepository actressRepo, TitleRepository titleRepo) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
    }

    @Override
    public String name() {
        return "actresses";
    }

    @Override
    public String description() {
        return "List actresses in a tier with title counts: actresses <tier>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: actresses <tier>");
            io.println("Tiers: " + tierNames());
            return;
        }

        Actress.Tier tier;
        try {
            tier = Actress.Tier.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            io.println("Unknown tier '" + args[1] + "'. Valid tiers: " + tierNames());
            return;
        }

        List<Actress> actresses = actressRepo.findByTier(tier);

        List<Map.Entry<Actress, Integer>> withCounts = actresses.stream()
                .map(a -> Map.entry(a, titleRepo.countByActress(a.getId())))
                .sorted(Map.Entry.<Actress, Integer>comparingByValue().reversed())
                .toList();

        io.println(tier.name() + "  (" + withCounts.size() + " actress" + (withCounts.size() == 1 ? "" : "es") + ")");
        io.println(String.format("  %-40s  %s", "NAME", "TITLES"));
        io.println("  " + "-".repeat(48));

        for (var entry : withCounts) {
            io.println(String.format("  %-40s  %d", entry.getKey().getCanonicalName(), entry.getValue()));
        }
    }

    private static String tierNames() {
        return Arrays.stream(Actress.Tier.values())
                .map(t -> t.name().toLowerCase())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
