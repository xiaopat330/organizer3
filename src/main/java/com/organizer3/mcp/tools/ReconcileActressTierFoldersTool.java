package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.ActressClassifierService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Treats {@code actresses.tier} as authoritative and moves an actress's on-disk tier folder
 * (and rebases her title_locations) to match — in either direction (up or down). Operates on
 * the currently-mounted volume only. Intra-volume, atomic moves only.
 *
 * <p>Accepts either a single {@code actressId} or {@code allOnVolume: true} for a batch scan
 * that reconciles every stranded actress on the volume. Default {@code dryRun: true}.
 */
public class ReconcileActressTierFoldersTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final ActressClassifierService service;

    public ReconcileActressTierFoldersTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                                           ActressClassifierService service) {
        this.session = session;
        this.jdbi = jdbi;
        this.config = config;
        this.service = service;
    }

    @Override public String name() { return "reconcile_actress_tier_folders"; }

    @Override public String description() {
        return "Move an actress's on-disk tier folder to match her DB actresses.tier — in either "
             + "direction (up or down). DB tier is authoritative. Rebases only title_locations "
             + "under the moved folder; queue/comp/pool and other-partition locations are left "
             + "untouched. Operates on the currently-mounted volume only (intra-volume/atomic). "
             + "Provide either actressId (single actress) or allOnVolume:true (batch scan every "
             + "stranded actress). Default dryRun:true — returns the plan without touching files or DB.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actressId",    "integer", "Actress id to reconcile (from lookup_actress). Mutually exclusive with allOnVolume.")
                .prop("allOnVolume",  "boolean", "When true, reconcile every actress whose on-disk tier folder differs from her DB tier. Mutually exclusive with actressId.", false)
                .prop("dryRun",       "boolean", "If true (default), return the plan without touching files or DB.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        boolean allOnVolume = Schemas.optBoolean(args, "allOnVolume", false);
        boolean hasActressId = args.has("actressId") && !args.get("actressId").isNull();

        if (allOnVolume && hasActressId) {
            throw new IllegalArgumentException("Provide either actressId or allOnVolume:true — not both.");
        }
        if (!allOnVolume && !hasActressId) {
            throw new IllegalArgumentException("Provide either actressId (single actress) or allOnVolume:true (batch).");
        }

        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException("No volume is currently mounted. Mount one before calling this tool.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeConfig volumeConfig = config.findById(volumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeId));

        VolumeFileSystem fs = conn.fileSystem();

        if (allOnVolume) {
            List<ActressClassifierService.Result> results =
                    service.reconcileTierFoldersOnVolume(fs, volumeConfig, jdbi, dryRun);
            long moved   = results.stream().filter(r -> r.outcome() == ActressClassifierService.Outcome.MOVED).count();
            long wouldMove = results.stream().filter(r -> r.outcome() == ActressClassifierService.Outcome.WOULD_MOVE).count();
            long skipped = results.stream().filter(r -> r.outcome() == ActressClassifierService.Outcome.SKIPPED).count();
            long failed  = results.stream().filter(r -> r.outcome() == ActressClassifierService.Outcome.FAILED).count();
            return new BatchResult(dryRun, results.size(), moved, wouldMove, skipped, failed, results);
        }

        long actressId = Schemas.requireLong(args, "actressId");
        return service.reconcileTierFolders(fs, volumeConfig, jdbi, actressId, dryRun);
    }

    public record BatchResult(
            boolean dryRun,
            int total,
            long moved,
            long wouldMove,
            long skipped,
            long failed,
            List<ActressClassifierService.Result> results
    ) {}
}
