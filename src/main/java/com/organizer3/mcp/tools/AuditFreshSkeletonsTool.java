package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.FreshAuditService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Read-only audit of fresh-prepped skeletons in a queue partition. Classifies
 * each child folder by graduation readiness: READY (actress + cover + video),
 * NEEDS_COVER, NEEDS_ACTRESS, EMPTY, OTHER.
 *
 * <p>No mutation, no gating — always safe to call.
 */
public class AuditFreshSkeletonsTool implements Tool {

    private final SessionContext session;
    private final OrganizerConfig config;
    private final FreshAuditService service;

    public AuditFreshSkeletonsTool(SessionContext session, OrganizerConfig config, FreshAuditService service) {
        this.session = session;
        this.config  = config;
        this.service = service;
    }

    @Override public String name()        { return "audit_fresh_skeletons"; }
    @Override public String description() {
        return "Read-only audit of a queue partition. Classifies each (CODE) folder into "
             + "READY (actress prefix + cover + video), NEEDS_COVER, NEEDS_ACTRESS, "
             + "EMPTY, or OTHER. Returns counts + per-folder listing with last-modified date.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",    "string", "Volume id (must be currently mounted).")
                .prop("partitionId", "string", "Logical partition id to audit, e.g. 'queue'.")
                .require("volumeId", "partitionId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId    = Schemas.requireString(args, "volumeId").trim();
        String partitionId = Schemas.requireString(args, "partitionId").trim();

        String mountedId = session.getMountedVolumeId();
        if (mountedId == null || !mountedId.equals(volumeId)) {
            throw new IllegalArgumentException(
                    "Volume '" + volumeId + "' is not currently mounted (mounted='" + mountedId + "'). Mount it first.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }

        VolumeConfig vol = config.findById(volumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeId));
        VolumeStructureDef def = config.findStructureById(vol.structureType()).orElseThrow(
                () -> new IllegalArgumentException("Unknown structure type: " + vol.structureType()));
        PartitionDef part = def.findUnstructuredById(partitionId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Partition '" + partitionId + "' not defined in structure '" + vol.structureType() + "'"));

        Path partitionRoot = Path.of("/", part.path());
        VolumeFileSystem fs = conn.fileSystem();
        try {
            return service.audit(fs, partitionRoot);
        } catch (IOException e) {
            throw new IllegalArgumentException("audit_fresh_skeletons failed: " + e.getMessage(), e);
        }
    }
}
