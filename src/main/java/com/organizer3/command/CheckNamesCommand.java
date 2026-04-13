package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.ActressRepository.FilingLocation;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Checks actress canonical names for likely data-entry errors.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code check names}        — summary counts only</li>
 *   <li>{@code check names swaps}  — list name-order swap pairs</li>
 *   <li>{@code check names typos}  — list likely typo pairs</li>
 * </ul>
 *
 * <p>For each suspect entry the command shows its filing-title SMB paths so that
 * the user can locate and rename the affected folders on the server.
 */
@RequiredArgsConstructor
public class CheckNamesCommand implements Command {

    private final ActressRepository actressRepo;
    private final ActressNameCheckService svc;

    @Override
    public String name() {
        return "check names";
    }

    @Override
    public String description() {
        return "Detect actress name errors: check names [swaps|typos]";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        // args[0] = "check names" (two-word dispatch strips it to one token)
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";

        io.println("Loading actress data...");
        List<Actress> names         = actressRepo.findAll();
        Map<Long, Integer> counts   = actressRepo.countAllTitlesByActress();

        switch (sub) {
            case "swaps" -> {
                List<ActressNameCheckService.SwapPair> swaps = svc.findSwaps(names, counts);
                printSwaps(swaps, counts, io);
            }
            case "typos" -> {
                List<ActressNameCheckService.TypoPair> typos = svc.findTypos(names, counts);
                printTypos(typos, io);
            }
            default -> {
                List<ActressNameCheckService.SwapPair> swaps = svc.findSwaps(names, counts);
                List<ActressNameCheckService.TypoPair> typos = svc.findTypos(names, counts);
                io.println("");
                io.println("Name-check summary  (" + names.size() + " actresses in DB)");
                io.println("  Name-order swaps:  " + swaps.size());
                io.println("  Likely typos:      " + typos.size() + "  (one side ≤ "
                        + ActressNameCheckService.SUSPECT_THRESHOLD + " titles)");
                io.println("");
                io.println("Use 'check names swaps' or 'check names typos' to see details.");
            }
        }
    }

    // ── Swap display ────────────────────────────────────────────────────────

    private void printSwaps(List<ActressNameCheckService.SwapPair> swaps,
                            Map<Long, Integer> counts, CommandIO io) {
        if (swaps.isEmpty()) {
            io.println("No name-order swaps found.");
            return;
        }
        io.println("");
        io.println("NAME-ORDER SWAPS  (" + swaps.size() + " pairs)");
        io.println("Same actress entered twice with Given/Family order reversed.");
        io.println("─".repeat(70));

        Map<Long, List<FilingLocation>> locations = actressRepo.findFilingLocations();

        for (int i = 0; i < swaps.size(); i++) {
            var pair = swaps.get(i);
            io.println("");
            io.println(String.format("[%d] %s  →  %s",
                    i + 1, pair.suspect().getCanonicalName(), pair.canonical().getCanonicalName()));
            io.println(String.format("    Canonical: %-36s (%d titles)",
                    pair.canonical().getCanonicalName(), pair.canonicalCount()));
            io.println(String.format("    Suspect:   %-36s (%d titles)",
                    pair.suspect().getCanonicalName(), pair.suspectCount()));
            io.println("    Titles to reassign (rename folder to canonical name):");
            printLocations(locations, pair.suspect().getId(), pair.suspectCount(), io);
        }
    }

    // ── Typo display ────────────────────────────────────────────────────────

    private void printTypos(List<ActressNameCheckService.TypoPair> typos, CommandIO io) {
        if (typos.isEmpty()) {
            io.println("No likely typo pairs found.");
            return;
        }
        io.println("");
        io.println("LIKELY TYPO PAIRS  (" + typos.size() + " pairs, one side ≤ "
                + ActressNameCheckService.SUSPECT_THRESHOLD + " titles)");
        io.println("Romanization-normalized edit distance = 1.");
        io.println("─".repeat(70));

        Map<Long, List<FilingLocation>> locations = actressRepo.findFilingLocations();

        for (int i = 0; i < typos.size(); i++) {
            var pair = typos.get(i);
            String kind = pair.sameSurname() ? "given-name typo" : "surname typo";
            io.println("");
            io.println(String.format("[%d] %s  →  %s  (dist=%d, %s)",
                    i + 1, pair.suspect().getCanonicalName(),
                    pair.canonical().getCanonicalName(), pair.dist(), kind));
            io.println(String.format("    Likely correct: %-36s (%d titles)",
                    pair.canonical().getCanonicalName(), pair.canonicalCount()));
            io.println(String.format("    Suspect entry:  %-36s (%d titles)",
                    pair.suspect().getCanonicalName(), pair.suspectCount()));
            io.println("    Titles to reassign:");
            printLocations(locations, pair.suspect().getId(), pair.suspectCount(), io);
        }
    }

    // ── Location helper ─────────────────────────────────────────────────────

    private void printLocations(Map<Long, List<FilingLocation>> locations,
                                long actressId, int totalCount, CommandIO io) {
        List<FilingLocation> locs = locations.get(actressId);
        if (locs == null || locs.isEmpty()) {
            if (totalCount == 0) {
                io.println("      (0 titles in DB — DB record only, no files on server to rename)");
            } else {
                io.println("      (WARNING: " + totalCount
                        + " titles in DB but no file locations found — sync may be needed)");
            }
            return;
        }
        for (FilingLocation loc : locs) {
            String smbBase = resolveSmbBase(loc.volumeId());
            io.println("      " + smbBase + loc.path());
        }
    }

    private String resolveSmbBase(String volumeId) {
        return AppConfig.get().volumes().findById(volumeId)
                .map(v -> v.smbPath())
                .orElse("//" + volumeId);
    }
}
