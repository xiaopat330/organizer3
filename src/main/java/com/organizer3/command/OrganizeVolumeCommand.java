package com.organizer3.command;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shell counterpart of {@code organize_volume}. Usage:
 *
 * <pre>
 *   organize                         — run all phases on the whole queue
 *   organize normalize,sort          — run a subset of phases
 * </pre>
 *
 * <p>Respects session dry-run. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §7.2.
 */
@Slf4j
@RequiredArgsConstructor
public class OrganizeVolumeCommand implements Command {

    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final OrganizeVolumeService service;

    @Override public String name()        { return "organize"; }
    @Override public String description() { return "Run the organize pipeline on the mounted volume's queue (all phases by default)"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String volumeId = ctx.getMountedVolumeId();
        VolumeConnection conn = ctx.getActiveConnection();
        if (volumeId == null || conn == null || !conn.isConnected()) {
            io.println("No volume is currently mounted.");
            return;
        }
        VolumeConfig volumeConfig = config.findById(volumeId).orElse(null);
        if (volumeConfig == null) {
            io.println("Volume not in config: " + volumeId);
            return;
        }

        Set<OrganizeVolumeService.Phase> phases = null;
        if (args.length >= 2) {
            try {
                phases = parsePhases(args[1]);
            } catch (IllegalArgumentException e) {
                io.println(e.getMessage());
                return;
            }
        }

        VolumeFileSystem fs = conn.fileSystem();
        AttentionRouter router = new AttentionRouter(fs, volumeId, Clock.systemUTC());
        boolean dry = ctx.isDryRun();

        long start = System.currentTimeMillis();
        OrganizeVolumeService.Result r = service.organize(fs, volumeConfig, router, jdbi, phases, 0, 0, dry);
        long elapsed = System.currentTimeMillis() - start;

        io.println(String.format("Volume '%s' (%s)  titles in queue: %d  processed: %d  [%s]",
                r.volumeId(), dry ? "DRY RUN" : "ARMED", r.queueTotal(), r.titlesInSlice(),
                r.phases().stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse("")));
        var s = r.summary();
        io.println(String.format("  normalize: %d  |  restructure: %d  |  sort→stars: %d  |  sort→attn: %d  |  skipped: %d  |  errors: %d",
                s.normalizeSuccesses(), s.restructureSuccesses(), s.sortedToStars(), s.sortedToAttention(),
                s.sortsSkipped(), s.titlesWithErrors()));
        io.println(String.format("  actresses classified: %d  |  promoted: %d",
                s.actressesClassified(), s.actressesPromoted()));
        io.println(String.format("  elapsed: %.1fs", elapsed / 1000.0));

        // Per-title error lines (if any) so the operator can see what needs attention
        for (var t : r.titles()) {
            if (t.error() != null) {
                io.println(String.format("  ✗ %s: %s", t.titleCode() == null ? t.path() : t.titleCode(), t.error()));
            }
        }
    }

    private static Set<OrganizeVolumeService.Phase> parsePhases(String csv) {
        EnumSet<OrganizeVolumeService.Phase> out = EnumSet.noneOf(OrganizeVolumeService.Phase.class);
        for (String tok : csv.split(",")) {
            String t = tok.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            try {
                out.add(OrganizeVolumeService.Phase.valueOf(t));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown phase '" + tok + "' — valid: normalize, restructure, sort, classify");
            }
        }
        return out.isEmpty() ? null : out;
    }
}
