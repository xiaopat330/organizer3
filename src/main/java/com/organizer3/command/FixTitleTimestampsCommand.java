package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.TitleTimestampService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shell counterpart of {@code fix_title_timestamps} MCP tool. Usage:
 *
 * <pre>
 *   fix-title-timestamps {CODE}
 * </pre>
 *
 * <p>Always executes (not dryRun). Requires a mounted volume. See
 * {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.5.
 */
@Slf4j
@RequiredArgsConstructor
public class FixTitleTimestampsCommand implements Command {

    private final Jdbi jdbi;
    private final TitleTimestampService service;

    @Override public String name()        { return "fix-title-timestamps"; }
    @Override public String description() { return "Set a title folder's creation + modification time to the earliest child timestamp"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: fix-title-timestamps <CODE>");
            return;
        }
        String titleCode = args[1].trim();

        String volumeId = ctx.getMountedVolumeId();
        VolumeConnection conn = ctx.getActiveConnection();
        if (volumeId == null || conn == null || !conn.isConnected()) {
            io.println("No volume is currently mounted.");
            return;
        }

        Path folder = lookupTitleFolder(volumeId, titleCode);
        if (folder == null) {
            io.println("No title '" + titleCode + "' on mounted volume '" + volumeId + "'");
            return;
        }

        VolumeFileSystem fs = conn.fileSystem();
        try {
            TitleTimestampService.Result r = service.apply(fs, folder, ctx.isDryRun());
            if (!r.plan().needsChange()) {
                io.println("No change needed: folder already matches earliest child time.");
                return;
            }
            if (r.plan().earliestChildTime() == null) {
                io.println("No child files to infer from — nothing to do.");
                return;
            }
            if (r.dryRun()) {
                io.println(String.format(
                        "[DRY RUN] would set %s → created=%s modified=%s",
                        folder, r.plan().earliestChildTime(), r.plan().earliestChildTime()));
            } else if (r.applied()) {
                io.println(String.format(
                        "Applied: %s → %s",
                        folder, r.plan().earliestChildTime()));
            } else {
                io.println("Failed: " + r.error());
            }
        } catch (IOException e) {
            io.println("Error: " + e.getMessage());
            log.debug("fix-title-timestamps failed", e);
        }
    }

    private Path lookupTitleFolder(String volumeId, String titleCode) {
        return jdbi.withHandle(h -> h.createQuery("""
                    SELECT tl.path FROM title_locations tl
                    JOIN titles t ON t.id = tl.title_id
                    WHERE tl.volume_id = :volumeId AND UPPER(t.code) = UPPER(:code)
                    ORDER BY tl.id
                    LIMIT 1
                    """)
                .bind("volumeId", volumeId)
                .bind("code", titleCode)
                .mapTo(String.class)
                .findFirst()
                .map(Path::of)
                .orElse(null));
    }
}
