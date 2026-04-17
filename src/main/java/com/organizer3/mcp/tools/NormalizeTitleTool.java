package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.TitleNormalizerService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Phase 1 of the organize pipeline: normalize filenames inside a title's folder.
 * See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.1.
 *
 * <p>Current scope: rename the sole cover at base and the sole video (wherever
 * located in the title folder) to canonical {@code {CODE}.{ext}}. Multi-file and
 * token strip/replace handling deferred per §6.2.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class NormalizeTitleTool implements Tool {

    private final SessionContext session;
    private final Jdbi jdbi;
    private final TitleNormalizerService service;

    public NormalizeTitleTool(SessionContext session, Jdbi jdbi, TitleNormalizerService service) {
        this.session = session;
        this.jdbi = jdbi;
        this.service = service;
    }

    @Override public String name()        { return "normalize_title"; }
    @Override public String description() {
        return "Rename a title's sole cover + sole video to canonical {CODE}.{ext}. "
             + "Multi-cover / multi-video cases are skipped with a reason. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string",  "Product code (e.g. 'MIDE-123'). Case-insensitive lookup; rename uses the canonical casing you pass.")
                .prop("dryRun",    "boolean", "If true (default), return the plan without renaming.", true)
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
            return service.apply(fs, folder, titleCode, dryRun);
        } catch (IOException e) {
            throw new IllegalArgumentException("normalize_title failed: " + e.getMessage(), e);
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
