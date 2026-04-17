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

/**
 * Phase 4 of the organize pipeline: re-tier an actress's folder based on her current
 * title count. Only upward moves. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.4.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class ClassifyActressTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final ActressClassifierService service;

    public ClassifyActressTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                                ActressClassifierService service) {
        this.session = session;
        this.jdbi = jdbi;
        this.config = config;
        this.service = service;
    }

    @Override public String name()        { return "classify_actress"; }
    @Override public String description() {
        return "Move an actress's folder to her correct tier based on current title count. "
             + "Upward-only; skips favorites/archive. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actressId", "integer", "Actress id (from lookup_actress or list_actresses).")
                .prop("dryRun",    "boolean", "If true (default), return the plan without touching files or DB.", true)
                .require("actressId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long actressId = Schemas.requireLong(args, "actressId");
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
        return service.classify(fs, volumeConfig, jdbi, actressId, dryRun);
    }
}
