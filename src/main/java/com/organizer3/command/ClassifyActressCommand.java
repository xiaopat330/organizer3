package com.organizer3.command;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.ActressClassifierService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

/**
 * Shell counterpart of {@code classify_actress}. Usage:
 *
 * <pre>
 *   classify-actress {actressId}
 * </pre>
 *
 * <p>Respects session dry-run. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.4.
 */
@Slf4j
@RequiredArgsConstructor
public class ClassifyActressCommand implements Command {

    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final ActressClassifierService service;

    @Override public String name()        { return "classify-actress"; }
    @Override public String description() { return "Re-tier an actress's folder to match her current title count"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: classify-actress <actressId>");
            return;
        }
        long actressId;
        try {
            actressId = Long.parseLong(args[1].trim());
        } catch (NumberFormatException e) {
            io.println("actressId must be a number");
            return;
        }

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
        boolean dry = ctx.isDryRun();

        try {
            ActressClassifierService.Result r = service.classify(fs, volumeConfig, jdbi, actressId, dry);
            io.println(String.format("%s  (%s)", r.outcome(), r.reason()));
            if (r.actressName() != null) io.println(String.format("  actress: %s", r.actressName()));
            if (r.fromTier() != null || r.toTier() != null)
                io.println(String.format("  tier: %s → %s", r.fromTier(), r.toTier()));
            if (r.fromPath() != null) io.println(String.format("  from: %s", r.fromPath()));
            if (r.toPath()   != null) io.println(String.format("  to:   %s", r.toPath()));
        } catch (IllegalArgumentException e) {
            io.println("Error: " + e.getMessage());
            log.debug("classify-actress failed", e);
        }
    }
}
