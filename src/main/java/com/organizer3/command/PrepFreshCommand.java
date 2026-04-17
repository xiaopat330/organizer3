package com.organizer3.command;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.FreshPrepService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shell counterpart of {@code prep_fresh_videos}. Usage:
 *
 * <pre>
 *   prep-fresh &lt;partitionId&gt; [limit] [offset]
 * </pre>
 *
 * <p>Operates on the currently-mounted volume. Respects session dry-run.
 */
@Slf4j
@RequiredArgsConstructor
public class PrepFreshCommand implements Command {

    private final OrganizerConfig config;
    private final FreshPrepService service;

    @Override public String name()        { return "prep-fresh"; }
    @Override public String description() { return "Turn raw video files at a queue partition root into (CODE)/<video|h265>/ skeletons"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: prep-fresh <partitionId> [limit] [offset]");
            return;
        }
        String partitionId = args[1].trim();
        int limit  = args.length >= 3 ? parseIntOr(args[2], 0, io, "limit")  : 0;
        int offset = args.length >= 4 ? parseIntOr(args[3], 0, io, "offset") : 0;
        if (limit < 0 || offset < 0) return;

        String volumeId = ctx.getMountedVolumeId();
        VolumeConnection conn = ctx.getActiveConnection();
        if (volumeId == null || conn == null || !conn.isConnected()) {
            io.println("No volume is currently mounted.");
            return;
        }
        VolumeConfig vol = config.findById(volumeId).orElse(null);
        if (vol == null) { io.println("Volume not in config: " + volumeId); return; }
        VolumeStructureDef def = config.findStructureById(vol.structureType()).orElse(null);
        if (def == null) { io.println("Unknown structure type: " + vol.structureType()); return; }
        PartitionDef part = def.findUnstructuredById(partitionId).orElse(null);
        if (part == null) {
            io.println("Partition '" + partitionId + "' not defined in structure '" + vol.structureType() + "'");
            return;
        }

        Path partitionRoot = Path.of("/", part.path());
        VolumeFileSystem fs = conn.fileSystem();
        boolean dry = ctx.isDryRun();
        try {
            FreshPrepService.Result r = dry
                    ? service.plan(fs, partitionRoot, limit, offset)
                    : service.execute(fs, partitionRoot, limit, offset);

            io.println((dry ? "[DRY RUN] " : "")
                    + r.planned().size() + " planned, "
                    + r.skipped().size() + " skipped"
                    + (dry ? "" : ", " + r.moved().size() + " moved, " + r.failed().size() + " failed")
                    + "  (total videos at root: " + r.totalVideosAtRoot() + ")");

            for (var p : r.planned()) {
                io.println((dry ? "  - " : "  ✓ ") + p.sourcePath() + " → " + p.targetVideoPath());
            }
            for (var s : r.skipped()) {
                io.println("  ~ " + s.filename() + ": " + s.reason());
            }
            if (!dry) {
                for (var p : r.failed()) io.println("  ✗ " + p.sourcePath());
            }
        } catch (IOException e) {
            io.println("Error: " + e.getMessage());
            log.debug("prep-fresh failed", e);
        }
    }

    private static int parseIntOr(String s, int def, CommandIO io, String name) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { io.println("Invalid " + name + ": " + s); return -1; }
    }
}
