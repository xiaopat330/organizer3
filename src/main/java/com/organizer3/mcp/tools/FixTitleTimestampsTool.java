package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.TitleTimestampService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Correct a single title folder's creation + modification timestamps by setting them
 * to the earliest known timestamp among the folder's children. See
 * {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.5.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class FixTitleTimestampsTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final TitleTimestampService service;

    public FixTitleTimestampsTool(SessionContext session, Jdbi jdbi, TitleTimestampService service) {
        this.session = session;
        this.jdbi = jdbi;
        this.service = service;
    }

    @Override public String name()        { return "fix_title_timestamps"; }
    @Override public String description() {
        return "Set a title folder's creation + lastWrite time to the earliest creation-time among "
             + "its children (cover + videos). Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("dryRun",    "boolean", "If true (default), return the plan without touching the folder.", true)
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
            TitleTimestampService.Result r = service.apply(fs, folder, dryRun);
            return r;
        } catch (IOException e) {
            throw new IllegalArgumentException("Timestamp fix failed: " + e.getMessage(), e);
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
