package com.organizer3.command;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.organize.TitleSorterService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;

/**
 * Shell counterpart of {@code sort_title}. Usage:
 *
 * <pre>
 *   sort-title {CODE}
 * </pre>
 *
 * <p>Respects session dry-run. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.3.
 */
@Slf4j
@RequiredArgsConstructor
public class SortTitleCommand implements Command {

    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final TitleSorterService service;

    @Override public String name()        { return "sort-title"; }
    @Override public String description() { return "Move a title from the volume queue to /stars/{tier}/{actress}/... (or /attention/)"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: sort-title <CODE>");
            return;
        }
        String titleCode = args[1].trim();

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

        VolumeFileSystem fs = conn.fileSystem();
        AttentionRouter router = new AttentionRouter(fs, volumeId, Clock.systemUTC());
        boolean dry = ctx.isDryRun();

        try {
            TitleSorterService.Result r = service.sort(fs, volumeConfig, router, jdbi, titleCode, dry);
            io.println(String.format("%s  (%s)", r.outcome(), r.reason()));
            io.println(String.format("  from: %s", r.from()));
            if (r.to() != null) io.println(String.format("  to:   %s", r.to()));
            if (r.reasonSidecar() != null) io.println("  sidecar: " + r.reasonSidecar());
            if (r.timestampNote() != null) io.println("  " + r.timestampNote());
        } catch (IllegalArgumentException e) {
            io.println("Error: " + e.getMessage());
            log.debug("sort-title failed", e);
        }
    }
}
