package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.TitleRestructurerService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Phase 2 of the organize pipeline: move videos from the title's base folder into
 * a child subfolder chosen by filename hint ({@code -4K} → {@code 4K/},
 * {@code -h265} → {@code h265/}, else {@code video/}).
 * See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.2.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class RestructureTitleTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final TitleRestructurerService service;

    public RestructureTitleTool(SessionContext session, Jdbi jdbi, TitleRestructurerService service) {
        this.session = session;
        this.jdbi = jdbi;
        this.service = service;
    }

    @Override public String name()        { return "restructure_title"; }
    @Override public String description() {
        return "Move videos at the title's base folder into a subfolder (4K / h265 / video) by "
             + "filename hint. Covers stay at base. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("dryRun",    "boolean", "If true (default), return the plan without moving anything.", true)
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

        Path folder = lookupTitleFolder(volumeId, titleCode);
        VolumeFileSystem fs = conn.fileSystem();
        try {
            return service.apply(fs, folder, dryRun);
        } catch (IOException e) {
            throw new IllegalArgumentException("restructure_title failed: " + e.getMessage(), e);
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
                .orElseThrow(() -> new IllegalArgumentException(
                        "No title '" + titleCode + "' on mounted volume '" + volumeId + "'")));
    }
}
