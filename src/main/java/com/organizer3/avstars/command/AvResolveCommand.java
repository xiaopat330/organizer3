package com.organizer3.avstars.command;

import com.organizer3.avstars.iafd.IafdClient;
import com.organizer3.avstars.iafd.IafdFetchException;
import com.organizer3.avstars.iafd.IafdProfileParser;
import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.iafd.IafdSearchParser;
import com.organizer3.avstars.iafd.IafdSearchResult;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves AV actress records against IAFD profiles.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code av resolve <name>} — interactive: search by name, pick candidate, fetch + persist</li>
 *   <li>{@code av resolve all} — batch over all unresolved actresses; auto-resolves single matches</li>
 *   <li>{@code av resolve refresh <name>} — re-fetch profile for an already-resolved actress</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AvResolveCommand implements Command {

    private final AvActressRepository actressRepo;
    private final IafdClient iafdClient;
    private final IafdSearchParser searchParser;
    private final IafdProfileParser profileParser;
    private final Path headshotDir;

    @Override
    public String name() {
        return "av resolve";
    }

    @Override
    public String description() {
        return "Resolve AV actress against IAFD: av resolve <name> | av resolve all | av resolve refresh <name> | av resolve id <name> <iafd-id>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        // args[0] = "av resolve" (merged by dispatcher), args[1..] = subcommand + name
        if (args.length < 2) {
            io.println("Usage: av resolve <name>");
            io.println("       av resolve all");
            io.println("       av resolve refresh <name>");
            io.println("       av resolve id <name> <iafd-id>");
            return;
        }

        String sub = args[1].toLowerCase();

        if ("all".equals(sub)) {
            resolveAll(io);
        } else if ("refresh".equals(sub)) {
            String name = joinArgs(args, 2);
            if (name.isEmpty()) { io.println("Usage: av resolve refresh <name>"); return; }
            resolveRefresh(name, io);
        } else if ("id".equals(sub)) {
            if (args.length < 4) { io.println("Usage: av resolve id <name> <iafd-id>"); return; }
            String iafdId = args[args.length - 1];
            String name = joinArgs(args, 2, args.length - 1);
            if (name.isEmpty()) { io.println("Usage: av resolve id <name> <iafd-id>"); return; }
            resolveById(name, iafdId, io);
        } else {
            String name = joinArgs(args, 1);
            resolveOne(name, io);
        }
    }

    // ── av resolve <name> ──────────────────────────────────────────────────────

    private void resolveOne(String searchName, CommandIO io) {
        // Find actress in DB (fuzzy match by stage name)
        Optional<AvActress> actressOpt = findActress(searchName);
        if (actressOpt.isEmpty()) {
            io.println("No actress found matching '" + searchName + "'.");
            io.println("Use 'av actresses' to see known names.");
            return;
        }
        AvActress actress = actressOpt.get();

        io.println("Searching IAFD for: " + actress.getStageName());
        List<IafdSearchResult> candidates = search(actress.getStageName(), io);
        if (candidates == null) return; // fetch error already reported

        if (candidates.isEmpty()) {
            io.println("No IAFD results for '" + actress.getStageName() + "'.");
            return;
        }

        IafdSearchResult chosen = pickCandidate(candidates, io);
        if (chosen == null) {
            io.println("Cancelled.");
            return;
        }

        applyProfile(actress, chosen, io);
    }

    // ── av resolve all ─────────────────────────────────────────────────────────

    private void resolveAll(CommandIO io) {
        List<AvActress> unresolved = actressRepo.findUnresolved();
        if (unresolved.isEmpty()) {
            io.println("All actresses are already resolved.");
            return;
        }

        io.println("Resolving " + unresolved.size() + " unresolved actress(es)...");
        int resolved = 0, ambiguous = 0, notFound = 0, failed = 0;

        for (AvActress actress : unresolved) {
            io.println("");
            io.println("── " + actress.getStageName() + " ──");

            List<IafdSearchResult> candidates = search(actress.getStageName(), io);
            if (candidates == null) { failed++; continue; }

            if (candidates.isEmpty()) {
                io.println("  Not found on IAFD.");
                notFound++;
                continue;
            }

            if (candidates.size() == 1) {
                io.println("  Single match: " + candidates.get(0).name() + " → auto-resolving");
                applyProfile(actress, candidates.get(0), io);
                resolved++;
            } else {
                io.println("  " + candidates.size() + " candidates — use 'av resolve " + actress.getStageName() + "' to pick.");
                ambiguous++;
            }
        }

        io.println("");
        io.println("Done: " + resolved + " resolved, " + ambiguous + " ambiguous, "
                + notFound + " not found, " + failed + " errors.");
    }

    // ── av resolve id <name> <iafd-id> ────────────────────────────────────────

    private void resolveById(String name, String iafdId, CommandIO io) {
        Optional<AvActress> actressOpt = findActress(name);
        if (actressOpt.isEmpty()) {
            io.println("No actress found matching '" + name + "'.");
            return;
        }
        AvActress actress = actressOpt.get();

        io.println("Fetching IAFD profile for " + actress.getStageName() + " using ID: " + iafdId);
        try {
            String profileHtml = iafdClient.fetchProfile(iafdId);
            IafdResolvedProfile profile = profileParser.parse(iafdId, profileHtml);
            String headshotPath = profile.getHeadshotUrl() != null
                    ? downloadHeadshot(iafdId, profile.getHeadshotUrl(), io)
                    : actress.getHeadshotPath();
            actressRepo.updateIafdFields(actress.getId(), profile, headshotPath);
            io.println("Resolved: " + actress.getStageName() + " → IAFD ID " + iafdId);
            if (profile.getIafdTitleCount() != null) {
                io.println("Titles: " + profile.getIafdTitleCount()
                        + (profile.getActiveFrom() != null
                        ? "  Active: " + profile.getActiveFrom() + "–" + (profile.getActiveTo() != null ? profile.getActiveTo() : "present")
                        : ""));
            }
        } catch (IafdFetchException e) {
            io.println("IAFD fetch failed: " + e.getMessage());
        }
    }

    // ── av resolve refresh <name> ──────────────────────────────────────────────

    private void resolveRefresh(String name, CommandIO io) {
        Optional<AvActress> actressOpt = findActress(name);
        if (actressOpt.isEmpty()) {
            io.println("No actress found matching '" + name + "'.");
            return;
        }
        AvActress actress = actressOpt.get();

        if (actress.getIafdId() == null) {
            io.println(actress.getStageName() + " is not yet resolved. Use 'av resolve " + name + "' first.");
            return;
        }

        io.println("Re-fetching IAFD profile for " + actress.getStageName() + " (" + actress.getIafdId() + ")...");
        try {
            String profileHtml = iafdClient.fetchProfile(actress.getIafdId());
            IafdResolvedProfile profile = profileParser.parse(actress.getIafdId(), profileHtml);
            // Re-download headshot if the profile page has one; otherwise keep existing
            String headshotPath = profile.getHeadshotUrl() != null
                    ? downloadHeadshot(actress.getIafdId(), profile.getHeadshotUrl(), io)
                    : actress.getHeadshotPath();
            actressRepo.updateIafdFields(actress.getId(), profile, headshotPath);
            io.println("Updated: " + actress.getStageName());
        } catch (IafdFetchException e) {
            io.println("IAFD fetch failed: " + e.getMessage());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<IafdSearchResult> search(String name, CommandIO io) {
        try {
            String html = iafdClient.fetchSearch(name);
            return searchParser.parse(html);
        } catch (IafdFetchException e) {
            io.println("IAFD search failed: " + e.getMessage());
            return null;
        }
    }

    private IafdSearchResult pickCandidate(List<IafdSearchResult> candidates, CommandIO io) {
        if (candidates.size() == 1) return candidates.get(0);

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            IafdSearchResult r = candidates.get(i);
            String years = (r.activeFrom() != null ? r.activeFrom() : "?")
                    + "–" + (r.activeTo() != null ? r.activeTo() : "?");
            String akas = r.akas().isEmpty() ? "" : " (aka " + String.join(", ", r.akas()) + ")";
            labels.add((i + 1) + ". " + r.name() + akas + " [" + years + ", " + r.titleCount() + " titles]");
        }

        Optional<String> picked = io.pick(labels);
        if (picked.isEmpty()) return null;

        // Find which candidate matches the chosen label
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equals(picked.get())) return candidates.get(i);
        }
        return null;
    }

    private void applyProfile(AvActress actress, IafdSearchResult chosen, CommandIO io) {
        io.println("  Fetching profile: " + chosen.name() + " [" + chosen.uuid() + "]");
        try {
            String profileHtml = iafdClient.fetchProfile(chosen.uuid());
            IafdResolvedProfile profile = profileParser.parse(chosen.uuid(), profileHtml);

            // Prefer the higher-res headshot from the profile page; fall back to search thumbnail
            String headshotUrl = profile.getHeadshotUrl() != null
                    ? profile.getHeadshotUrl() : chosen.headshotUrl();
            String headshotPath = downloadHeadshot(chosen.uuid(), headshotUrl, io);

            actressRepo.updateIafdFields(actress.getId(), profile, headshotPath);
            io.println("  Resolved: " + actress.getStageName() + " → " + chosen.name());
            if (profile.getIafdTitleCount() != null) {
                io.println("  Titles: " + profile.getIafdTitleCount()
                        + (profile.getActiveFrom() != null
                        ? "  Active: " + profile.getActiveFrom() + "–" + (profile.getActiveTo() != null ? profile.getActiveTo() : "present")
                        : ""));
            }
        } catch (IafdFetchException e) {
            io.println("  IAFD fetch failed: " + e.getMessage());
        }
    }

    private String downloadHeadshot(String uuid, String url, CommandIO io) {
        if (url == null || url.isBlank()) return null;
        String ext = url.contains(".") ? url.substring(url.lastIndexOf('.')) : ".jpg";
        // Sanitize extension (strip query strings etc.)
        if (ext.contains("?")) ext = ext.substring(0, ext.indexOf('?'));
        if (ext.length() > 5) ext = ".jpg"; // fallback

        Path target = headshotDir.resolve(uuid + ext);
        try {
            Files.createDirectories(headshotDir);
            byte[] bytes = iafdClient.fetchBytes(url);
            Files.write(target, bytes);
            return target.toString();
        } catch (IafdFetchException | IOException e) {
            io.println("  Warning: headshot download failed — " + e.getMessage());
            return null;
        }
    }

    private Optional<AvActress> findActress(String name) {
        // Search across all actresses by stage name (case-insensitive prefix/contains)
        String nameLower = name.toLowerCase();
        return actressRepo.findAllByVideoCountDesc().stream()
                .filter(a -> a.getStageName().equalsIgnoreCase(name)
                        || a.getStageName().toLowerCase().contains(nameLower)
                        || a.getFolderName().equalsIgnoreCase(name)
                        || a.getFolderName().toLowerCase().contains(nameLower))
                .findFirst();
    }

    private String joinArgs(String[] args, int fromIndex) {
        return joinArgs(args, fromIndex, args.length);
    }

    private String joinArgs(String[] args, int fromIndex, int toIndexExclusive) {
        if (fromIndex >= toIndexExclusive) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < toIndexExclusive; i++) {
            if (i > fromIndex) sb.append(" ");
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }
}
