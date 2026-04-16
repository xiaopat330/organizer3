package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.TitleTimestampService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;

/**
 * Shell counterpart of {@code audit_volume_timestamps} MCP tool. Runs the audit across
 * all title folders on the mounted volume in one pass. Respects session dry-run setting.
 *
 * <pre>
 *   audit-timestamps
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class AuditTimestampsCommand implements Command {

    private final Jdbi jdbi;
    private final TitleTimestampService service;

    @Override public String name()        { return "audit-timestamps"; }
    @Override public String description() { return "Audit + correct title folder timestamps across the mounted volume"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String volumeId = ctx.getMountedVolumeId();
        VolumeConnection conn = ctx.getActiveConnection();
        if (volumeId == null || conn == null || !conn.isConnected()) {
            io.println("No volume is currently mounted.");
            return;
        }
        VolumeFileSystem fs = conn.fileSystem();
        boolean dry = ctx.isDryRun();

        int scanned = 0, needsChange = 0, changed = 0, skipped = 0, errors = 0;
        long started = System.currentTimeMillis();

        var locations = jdbi.withHandle(h -> h.createQuery("""
                    SELECT t.code AS code, tl.path AS path
                    FROM title_locations tl
                    JOIN titles t ON t.id = tl.title_id
                    WHERE tl.volume_id = :volumeId
                    ORDER BY tl.id
                    """)
                .bind("volumeId", volumeId)
                .map((rs, ctxx) -> new Loc(rs.getString("code"), rs.getString("path")))
                .list());

        io.println(String.format("Auditing %d title folders on '%s' (%s)...",
                locations.size(), volumeId, dry ? "DRY RUN" : "ARMED"));

        for (Loc loc : locations) {
            scanned++;
            try {
                Path folder = Path.of(loc.path);
                if (!fs.exists(folder) || !fs.isDirectory(folder)) {
                    skipped++;
                    continue;
                }
                TitleTimestampService.Result r = service.apply(fs, folder, dry);
                if (r.plan().needsChange() && r.plan().earliestChildTime() != null) {
                    needsChange++;
                    if (r.applied()) changed++;
                }
            } catch (Exception e) {
                errors++;
                log.debug("audit-timestamps error on {}: {}", loc.path, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - started;
        io.println(String.format(
                "Done. scanned=%d  needsChange=%d  changed=%d  skipped=%d  errors=%d  in %.1fs",
                scanned, needsChange, changed, skipped, errors, elapsed / 1000.0));
    }

    private record Loc(String code, String path) {}
}
