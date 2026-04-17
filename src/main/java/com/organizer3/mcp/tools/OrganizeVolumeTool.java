package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Composite organize pipeline: walks the mounted volume's queue and runs phases 1–4
 * (normalize → restructure → sort per title, then classify affected actresses).
 * See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §7.2.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class OrganizeVolumeTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final OrganizeVolumeService service;
    private final Clock clock;

    public OrganizeVolumeTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                               OrganizeVolumeService service) {
        this(session, jdbi, config, service, Clock.systemUTC());
    }

    OrganizeVolumeTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                        OrganizeVolumeService service, Clock clock) {
        this.session = session;
        this.jdbi = jdbi;
        this.config = config;
        this.service = service;
        this.clock = clock;
    }

    @Override public String name()        { return "organize_volume"; }
    @Override public String description() {
        return "Run the organize pipeline on the mounted volume's queue: normalize → "
             + "restructure → sort per title, then classify affected actresses. "
             + "Paginate with limit/offset. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit",  "integer", "Max titles to process per call. 0 (default) = all.", 0)
                .prop("offset", "integer", "Skip this many queue titles before processing. Default 0.", 0)
                .prop("phases", "string",  "Comma-separated subset of phases to run. Default: all. "
                                           + "Values: normalize,restructure,sort,classify")
                .prop("dryRun", "boolean", "If true (default), return the plan without touching files or DB.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit   = Math.max(0, Schemas.optInt(args, "limit",  0));
        int offset  = Math.max(0, Schemas.optInt(args, "offset", 0));
        boolean dry = Schemas.optBoolean(args, "dryRun", true);
        Set<OrganizeVolumeService.Phase> phases = parsePhases(Schemas.optString(args, "phases", null));

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
        AttentionRouter router = new AttentionRouter(fs, volumeId, clock);
        return service.organize(fs, volumeConfig, router, jdbi, phases, limit, offset, dry);
    }

    private static Set<OrganizeVolumeService.Phase> parsePhases(String csv) {
        if (csv == null || csv.isBlank()) return null;
        EnumSet<OrganizeVolumeService.Phase> out = EnumSet.noneOf(OrganizeVolumeService.Phase.class);
        for (String tok : csv.split(",")) {
            String t = tok.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            try {
                out.add(OrganizeVolumeService.Phase.valueOf(t));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown phase '" + tok + "' — valid: normalize, restructure, sort, classify");
            }
        }
        return out.isEmpty() ? null : out;
    }
}
