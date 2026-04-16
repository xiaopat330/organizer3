package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.TitleNormalizerService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shell counterpart of {@code normalize_title}. Usage:
 *
 * <pre>
 *   normalize-title {CODE}
 * </pre>
 *
 * <p>Respects session dry-run. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.1.
 */
@Slf4j
@RequiredArgsConstructor
public class NormalizeTitleCommand implements Command {

    private final Jdbi jdbi;
    private final TitleNormalizerService service;

    @Override public String name()        { return "normalize-title"; }
    @Override public String description() { return "Rename a title's sole cover + sole video to canonical {CODE}.{ext}"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: normalize-title <CODE>");
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
            TitleNormalizerService.Result r = service.apply(fs, folder, titleCode, dry);

            if (r.planned().isEmpty()) {
                io.println("Nothing to rename:");
                for (var s : r.skipped()) io.println("  - " + s.kind() + ": " + s.reason());
                return;
            }

            if (dry) {
                io.println("[DRY RUN] planned:");
                for (var a : r.planned()) io.println("  - " + a.op() + ": " + a.from() + " → " + a.to());
            } else {
                for (var a : r.applied())  io.println("  ✓ " + a.op() + ": " + a.from() + " → " + a.to());
                for (var a : r.failed())   io.println("  ✗ " + a.op() + ": " + a.from() + " → " + a.to());
            }
            for (var s : r.skipped()) io.println("  ~ " + s.kind() + ": " + s.reason());
        } catch (IOException e) {
            io.println("Error: " + e.getMessage());
            log.debug("normalize-title failed", e);
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
