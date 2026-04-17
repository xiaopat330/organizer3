package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.organize.TitleSorterService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;

/**
 * Phase 3 of the organize pipeline: move a title from the volume's queue to its
 * final home under {@code /stars/{tier}/{actress}/...}, routing to {@code /attention/}
 * when the title can't be filed (actressless, letter-mismatch, collision).
 * See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.3.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class SortTitleTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final TitleSorterService service;
    private final Clock clock;

    public SortTitleTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                         TitleSorterService service) {
        this(session, jdbi, config, service, Clock.systemUTC());
    }

    SortTitleTool(SessionContext session, Jdbi jdbi, OrganizerConfig config,
                  TitleSorterService service, Clock clock) {
        this.session = session;
        this.jdbi = jdbi;
        this.config = config;
        this.service = service;
        this.clock = clock;
    }

    @Override public String name()        { return "sort_title"; }
    @Override public String description() {
        return "Move a title from the volume queue to /stars/{tier}/{actress}/..., or route to "
             + "/attention/ with REASON.txt when filing isn't possible. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("dryRun",    "boolean", "If true (default), return the decision without touching files or DB.", true)
                .require("titleCode")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode = Schemas.requireString(args, "titleCode").trim();
        boolean dryRun   = Schemas.optBoolean(args, "dryRun", true);

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
        return service.sort(fs, volumeConfig, router, jdbi, titleCode, dryRun);
    }
}
