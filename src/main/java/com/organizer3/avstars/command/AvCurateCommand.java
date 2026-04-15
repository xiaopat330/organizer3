package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Sets curation flags on an AV actress.
 *
 * <p>Usage:
 * <pre>
 *   av curate &lt;name&gt; fav                    — toggle favorite
 *   av curate &lt;name&gt; bookmark               — toggle bookmark
 *   av curate &lt;name&gt; reject                 — toggle rejected
 *   av curate &lt;name&gt; grade &lt;grade&gt;          — set grade (SSS/SS/S/A/B/C)
 *   av curate &lt;name&gt; clear-grade            — remove grade
 *   av curate &lt;name&gt; stage-name &lt;new name&gt;  — override display name
 * </pre>
 *
 * <p>The action keyword(s) are the trailing token(s); the name is everything between
 * the command word and the action. Name matching is case-insensitive substring.
 */
@RequiredArgsConstructor
public class AvCurateCommand implements Command {

    private static final Set<String> BOOL_ACTIONS = Set.of("fav", "bookmark", "reject");
    private static final Set<String> VALID_GRADES = Set.of(
            "SSS", "SS", "S", "A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D", "F");

    private final AvActressRepository actressRepo;

    @Override
    public String name() {
        return "av curate";
    }

    @Override
    public String description() {
        return "Set curation on an AV actress: av curate <name> fav|bookmark|reject|grade <G>|clear-grade";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        // args[0] = "av curate", args[1..] = name + action tokens
        if (args.length < 3) {
            printUsage(io);
            return;
        }

        // Detect action at the tail.
        // Tail cases:
        //   "grade S"          → action = "grade", value = args[last], name = args[1..last-2]
        //   "clear-grade"      → action = "clear-grade",               name = args[1..last-1]
        //   "fav" etc.         → action = last token,                  name = args[1..last-1]
        //   "stage-name Foo B" → scan for "stage-name" pivot token
        String lastToken = args[args.length - 1];
        String secondLast = args.length >= 3 ? args[args.length - 2] : null;

        // Check for stage-name pivot anywhere in args[1..]
        int stageNameIdx = -1;
        for (int i = 1; i < args.length; i++) {
            if ("stage-name".equalsIgnoreCase(args[i])) { stageNameIdx = i; break; }
        }

        String action;
        String gradeValue = null;
        String stageNameValue = null;
        int nameEndExclusive; // exclusive end index into args for the name tokens

        if (stageNameIdx > 1 && stageNameIdx < args.length - 1) {
            action = "stage-name";
            stageNameValue = String.join(" ", Arrays.copyOfRange(args, stageNameIdx + 1, args.length));
            nameEndExclusive = stageNameIdx;
        } else if ("grade".equalsIgnoreCase(secondLast)) {
            action = "grade";
            gradeValue = lastToken.toUpperCase();
            nameEndExclusive = args.length - 2;
        } else if ("clear-grade".equalsIgnoreCase(lastToken)) {
            action = "clear-grade";
            nameEndExclusive = args.length - 1;
        } else if (BOOL_ACTIONS.contains(lastToken.toLowerCase())) {
            action = lastToken.toLowerCase();
            nameEndExclusive = args.length - 1;
        } else {
            io.println("Unknown action '" + lastToken + "'.");
            printUsage(io);
            return;
        }

        if (nameEndExclusive <= 1) {
            io.println("No actress name given.");
            printUsage(io);
            return;
        }

        String query = String.join(" ", Arrays.copyOfRange(args, 1, nameEndExclusive)).toLowerCase();
        List<AvActress> all = actressRepo.findAllByVideoCountDesc();
        List<AvActress> matches = all.stream()
                .filter(a -> a.getStageName().toLowerCase().contains(query)
                        || a.getFolderName().toLowerCase().contains(query))
                .toList();

        if (matches.isEmpty()) {
            io.println("No AV actress matching '" + query + "'.");
            return;
        }
        if (matches.size() > 1) {
            io.println("Multiple matches — be more specific:");
            matches.forEach(a -> io.println("  " + a.getStageName()));
            return;
        }

        AvActress a = matches.get(0);

        switch (action) {
            case "fav" -> {
                boolean newVal = !a.isFavorite();
                actressRepo.toggleFavorite(a.getId(), newVal);
                io.println(a.getStageName() + ": favorite → " + (newVal ? "ON" : "off"));
            }
            case "bookmark" -> {
                boolean newVal = !a.isBookmark();
                actressRepo.toggleBookmark(a.getId(), newVal);
                io.println(a.getStageName() + ": bookmark → " + (newVal ? "ON" : "off"));
            }
            case "reject" -> {
                boolean newVal = !a.isRejected();
                actressRepo.toggleRejected(a.getId(), newVal);
                io.println(a.getStageName() + ": rejected → " + (newVal ? "ON" : "off"));
            }
            case "grade" -> {
                if (!VALID_GRADES.contains(gradeValue)) {
                    io.println("Unknown grade '" + gradeValue + "'. Valid: " + String.join(" ", VALID_GRADES));
                    return;
                }
                actressRepo.setGrade(a.getId(), gradeValue);
                io.println(a.getStageName() + ": grade → " + gradeValue);
            }
            case "clear-grade" -> {
                actressRepo.setGrade(a.getId(), null);
                io.println(a.getStageName() + ": grade cleared");
            }
            case "stage-name" -> {
                actressRepo.updateStageName(a.getId(), stageNameValue);
                io.println(a.getStageName() + " → stage name set to: " + stageNameValue);
            }
        }
    }

    private static void printUsage(CommandIO io) {
        io.println("Usage:");
        io.println("  av curate <name> fav                    — toggle favorite");
        io.println("  av curate <name> bookmark               — toggle bookmark");
        io.println("  av curate <name> reject                 — toggle rejected");
        io.println("  av curate <name> grade <grade>          — set grade (SSS SS S A B C)");
        io.println("  av curate <name> clear-grade            — remove grade");
        io.println("  av curate <name> stage-name <new name>  — override display name");
    }
}
