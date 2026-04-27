package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.command.ActressMergeService.LocationRename;
import com.organizer3.command.ActressMergeService.MergePreview;
import com.organizer3.command.ActressMergeService.MergeResult;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Merges a misspelled actress record into the correct one, renaming affected title folders.
 *
 * <p>Usage: {@code actress merge <suspect> > <canonical>}
 *
 * <p>Requires a volume to be mounted for folder renames. The DB merge always runs;
 * folders on unmounted volumes are listed as needing manual attention.
 */
@Slf4j
@RequiredArgsConstructor
public class MergeActressCommand implements Command {

    private final ActressRepository actressRepo;
    private final ActressMergeService mergeService;

    @Override
    public String name() { return "actress merge"; }

    @Override
    public String description() { return "Fix actress name typo: actress merge <suspect> > <canonical>"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String[] names = parseNames(args);
        if (names == null) {
            io.println("Usage: actress merge <suspect-name> > <canonical-name>");
            io.println("  e.g. actress merge \"Rin Hatchimitsu\" > \"Rin Hachimitsu\"");
            io.println("  Both names must match exactly as stored in the database.");
            return;
        }

        String suspectName = names[0];
        String canonicalName = names[1];

        Optional<Actress> suspectOpt = actressRepo.findByCanonicalName(suspectName);
        Optional<Actress> canonicalOpt = actressRepo.findByCanonicalName(canonicalName);

        if (suspectOpt.isEmpty()) {
            io.println("No actress found with name: '" + suspectName + "'");
            return;
        }
        if (canonicalOpt.isEmpty()) {
            io.println("No actress found with name: '" + canonicalName + "'");
            return;
        }

        Actress suspect = suspectOpt.get();
        Actress canonical = canonicalOpt.get();

        if (suspect.getId() == canonical.getId()) {
            io.println("Both names resolve to the same actress record — nothing to merge.");
            return;
        }

        MergePreview preview = mergeService.preview(suspect, canonical);

        boolean dry = ctx.isDryRun();
        VolumeConnection conn = ctx.getActiveConnection();
        String mountedVolumeId = ctx.getMountedVolumeId();

        if (mountedVolumeId == null || conn == null || !conn.isConnected()) {
            io.println("Warning: no volume mounted — folder renames will be skipped.");
            io.println("Mount a volume first to rename folders on that volume.");
            io.println("");
        }

        printPreview(preview, mountedVolumeId, dry, io);

        if (dry) {
            return;
        }

        VolumeFileSystem fs = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        try {
            MergeResult result = mergeService.execute(preview, mountedVolumeId, fs, false);
            printResult(result, canonical.getCanonicalName(), io);
        } catch (IOException e) {
            io.println("Error during merge: " + e.getMessage());
            log.error("actress merge failed", e);
        }
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private void printPreview(MergePreview preview, String mountedVolumeId, boolean dry, CommandIO io) {
        String mode = dry ? "[DRY RUN] " : "";
        io.println("");
        io.println(mode + "Merging actress records:");
        io.println("  Suspect:   " + preview.suspect().getCanonicalName()
                + " (id=" + preview.suspect().getId() + ")");
        io.println("  Canonical: " + preview.canonical().getCanonicalName()
                + " (id=" + preview.canonical().getId() + ")");
        io.println("");
        io.println("  Cast titles to reassign: " + preview.castTitleCount());
        io.println("  Filing titles to update: " + preview.filingTitleCount());
        io.println("  Folder renames:          " + preview.renames().size());
        io.println("");

        if (!preview.renames().isEmpty()) {
            for (LocationRename r : preview.renames()) {
                String tag = mountedVolumeId != null && r.volumeId().equals(mountedVolumeId)
                        ? ""
                        : "  [SKIPPED — mount '" + r.volumeId() + "' first]";
                String smbBase = resolveSmbBase(r.volumeId());
                io.println("  " + smbBase + r.currentPath() + " →");
                io.println("    " + smbBase + r.newPath() + tag);
            }
            io.println("");
        }

        if (!dry) {
            io.println("The suspect actress record will be permanently deleted.");
        }
    }

    private void printResult(MergeResult result, String canonicalName, CommandIO io) {
        io.println("");
        io.println("Merge complete → " + canonicalName);
        io.println("  Cast titles reassigned: " + result.castTitlesReassigned());
        io.println("  Filing titles updated:  " + result.filingTitlesUpdated());
        io.println("  Folders renamed:        " + result.renamedPaths().size());

        if (!result.skipped().isEmpty()) {
            io.println("");
            io.println("  Folders NOT renamed (volume not mounted — mount and sync to update paths):");
            for (LocationRename r : result.skipped()) {
                String smbBase = resolveSmbBase(r.volumeId());
                io.println("    " + smbBase + r.currentPath());
            }
        }
    }

    private String resolveSmbBase(String volumeId) {
        return AppConfig.get().volumes().findById(volumeId)
                .map(v -> v.smbPath())
                .orElse("//" + volumeId);
    }

    // ── Argument parsing ─────────────────────────────────────────────────────

    /**
     * Parses {@code actress merge <suspect> > <canonical>} from args.
     * Supports multi-word names joined by {@code " > "} separator.
     * Returns {@code String[2]} = {suspect, canonical}, or {@code null} on parse failure.
     */
    static String[] parseNames(String[] args) {
        if (args.length < 2) return null;

        // Join everything after the command token and split on " > "
        String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (joined.contains(" > ")) {
            String[] parts = joined.split(" > ", 2);
            String suspect = parts[0].trim();
            String canonical = parts[1].trim();
            if (suspect.isEmpty() || canonical.isEmpty()) return null;
            // Strip optional surrounding quotes
            suspect = stripQuotes(suspect);
            canonical = stripQuotes(canonical);
            return new String[]{suspect, canonical};
        }

        // Single-word names: args[1] and args[2]
        if (args.length == 3) {
            return new String[]{stripQuotes(args[1]), stripQuotes(args[2])};
        }

        return null;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
