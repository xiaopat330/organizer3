package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.FreshPrepService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Prep raw video files dropped into a queue partition into {@code (CODE)/<video|h265>/}
 * skeletons ready for human curation. Operates on the currently-mounted volume; caller
 * supplies the logical partition id (e.g. {@code queue}).
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class PrepFreshVideosTool implements Tool {

    private final SessionContext session;
    private final OrganizerConfig config;
    private final FreshPrepService service;

    public PrepFreshVideosTool(SessionContext session, OrganizerConfig config, FreshPrepService service) {
        this.session = session;
        this.config  = config;
        this.service = service;
    }

    @Override public String name()        { return "prep_fresh_videos"; }
    @Override public String description() {
        return "Turn raw video files at a queue partition's root into (CODE)/<video|h265>/ "
             + "skeletons for human curation. Strips legacy junk-prefix tokens, parses the "
             + "product code, picks a subfolder by h265 hint, skips unparseable or colliding "
             + "files. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",    "string",  "Volume id (must be currently mounted).")
                .prop("partitionId", "string",  "Logical partition id to scan, e.g. 'queue'. The actual folder is looked up in the volume's structure definition.")
                .prop("limit",       "integer", "Maximum number of files to process in this call. 0 = all.", 0)
                .prop("offset",      "integer", "Skip the first N files (alphabetical). Useful for pagination.", 0)
                .prop("dryRun",      "boolean", "If true (default), return the plan without moving anything.", true)
                .require("volumeId", "partitionId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId    = Schemas.requireString(args, "volumeId").trim();
        String partitionId = Schemas.requireString(args, "partitionId").trim();
        int limit          = Schemas.optInt(args, "limit", 0);
        int offset         = Schemas.optInt(args, "offset", 0);
        boolean dryRun     = Schemas.optBoolean(args, "dryRun", true);

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
            return dryRun
                    ? service.plan(fs, partitionRoot, limit, offset)
                    : service.execute(fs, partitionRoot, limit, offset);
        } catch (IOException e) {
            throw new IllegalArgumentException("prep_fresh_videos failed: " + e.getMessage(), e);
        }
    }
}
