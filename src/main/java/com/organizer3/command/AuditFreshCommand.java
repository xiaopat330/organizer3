package com.organizer3.command;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.FreshAuditService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shell counterpart of {@code audit_fresh_skeletons}. Usage:
 *
 * <pre>
 *   audit-fresh &lt;partitionId&gt;
 * </pre>
 *
 * <p>Read-only. Reports which skeletons in a queue partition are ready to
 * graduate, which still need actress / cover, and which are empty.
 */
@Slf4j
@RequiredArgsConstructor
public class AuditFreshCommand implements Command {

    private final OrganizerConfig config;
    private final FreshAuditService service;

    @Override public String name()        { return "audit-fresh"; }
    @Override public String description() { return "Classify fresh-prepped folders by graduation readiness (actress / cover / video)"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) { io.println("Usage: audit-fresh <partitionId>"); return; }
        String partitionId = args[1].trim();

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
        try {
            FreshAuditService.Result r = service.audit(fs, partitionRoot);
            io.println("Audit of " + r.partitionRoot() + " — total: " + r.total());
            for (FreshAuditService.Bucket b : FreshAuditService.Bucket.values()) {
                io.println("  " + b + ": " + r.counts().get(b));
            }
            for (FreshAuditService.Bucket b : FreshAuditService.Bucket.values()) {
                boolean printedHeader = false;
                for (FreshAuditService.Entry e : r.entries()) {
                    if (e.bucket() != b) continue;
                    if (!printedHeader) { io.println("--- " + b + " ---"); printedHeader = true; }
                    String age = e.lastModified() == null ? "" : " (mtime " + e.lastModified() + ")";
                    io.println("  " + e.folderName() + age);
                }
            }
        } catch (IOException e) {
            io.println("Error: " + e.getMessage());
            log.debug("audit-fresh failed", e);
        }
    }
}
