package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.TitleRestructurerService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shell counterpart of {@code restructure_title}. Usage:
 *
 * <pre>
 *   restructure-title {CODE}
 * </pre>
 *
 * <p>Respects session dry-run. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.2.
 */
@Slf4j
@RequiredArgsConstructor
public class RestructureTitleCommand implements Command {

    private final Jdbi jdbi;
    private final TitleRestructurerService service;

    @Override public String name()        { return "restructure-title"; }
    @Override public String description() { return "Move base videos into video/h265/4K subfolder by filename hint"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: restructure-title <CODE>");
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
        boolean dry = ctx.isDryRun();
        try {
            TitleRestructurerService.Result r = service.apply(fs, folder, dry);

            if (r.planned().isEmpty() && r.collisions().isEmpty()) {
                io.println("Nothing to move — all videos already in a subfolder (or none found).");
                return;
            }

            if (dry) {
                io.println("[DRY RUN] planned:");
                for (var a : r.planned())    io.println("  - " + a.from() + " → " + a.to());
            } else {
                for (var a : r.moved())   io.println("  ✓ " + a.from() + " → " + a.to());
                for (var a : r.failed())  io.println("  ✗ " + a.from() + " → " + a.to() + "  (" + a.note() + ")");
            }
            for (var a : r.collisions())   io.println("  ! collision: " + a.from() + " (" + a.note() + ")");
        } catch (IOException e) {
            io.println("Error: " + e.getMessage());
            log.debug("restructure-title failed", e);
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
