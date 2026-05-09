package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.titlefolder.TitleFolderService;
import com.organizer3.trash.Trash;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP adapter that trashes all duplicate covers at a title's base folder
 * except the named keeper. Per-file trash + cover-listing logic lives in
 * {@link TitleFolderService}; this adapter resolves the mounted volume and
 * iterates the to-trash list.
 *
 * <p>Gated on both {@code mcp.allowMutations} and {@code mcp.allowFileOps}.
 * Requires the volume's server to have a {@code trash:} folder configured.
 * Defaults to {@code dryRun: true}.
 */
public class TrashDuplicateCoverTool implements Tool {

    private final SessionContext session;
    private final OrganizerConfig config;
    private final TitleFolderService folderService;
    private final Clock clock;

    public TrashDuplicateCoverTool(SessionContext session, Jdbi jdbi, OrganizerConfig config) {
        this(session, jdbi, config, Clock.systemUTC());
    }

    TrashDuplicateCoverTool(SessionContext session, Jdbi jdbi, OrganizerConfig config, Clock clock) {
        this.session       = session;
        this.config        = config;
        this.folderService = new TitleFolderService(null, null, jdbi);
        this.clock         = clock;
    }

    @Override public String name()        { return "trash_duplicate_cover"; }
    @Override public String description() {
        return "Trash all covers at a title's base folder except the named 'keep' file. "
             + "Uses the per-volume _trash area. Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("keep",      "string",  "Filename of the single cover to retain. Must exist at the base folder.")
                .prop("dryRun",    "boolean", "If true (default), return the plan without moving anything.", true)
                .require("titleCode", "keep")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode = Schemas.requireString(args, "titleCode").trim();
        String keep      = Schemas.requireString(args, "keep").trim();
        boolean dryRun   = Schemas.optBoolean(args, "dryRun", true);

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException("No volume is currently mounted. Mount one before calling this tool.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }

        VolumeConfig vol = config.findById(volumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeId));
        ServerConfig srv = config.findServerById(vol.server()).orElseThrow(
                () -> new IllegalArgumentException("Server not in config: " + vol.server()));
        if (srv.trash() == null || srv.trash().isBlank()) {
            throw new IllegalArgumentException(
                    "Server '" + srv.id() + "' has no 'trash:' folder configured; cannot trash items on volume '" + volumeId + "'.");
        }

        Path folder = folderService.findTitleFolder(titleCode, volumeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No title '" + titleCode + "' on mounted volume '" + volumeId + "'"));

        VolumeFileSystem fs = conn.fileSystem();
        if (!fs.exists(folder) || !fs.isDirectory(folder)) {
            throw new IllegalArgumentException("Title folder does not exist on volume: " + folder);
        }

        List<String> covers = folderService.listCovers(fs, folder);
        if (covers.size() < 2) {
            throw new IllegalArgumentException(
                    "Title '" + titleCode + "' has " + covers.size() + " cover(s) at base — nothing to dedupe.");
        }
        if (!covers.contains(keep)) {
            throw new IllegalArgumentException(
                    "'keep' file '" + keep + "' is not among the covers at '" + folder + "': " + covers);
        }

        List<String> toTrash = new ArrayList<>();
        for (String c : covers) if (!c.equals(keep)) toTrash.add(c);

        Plan plan = new Plan(volumeId, folder.toString(), keep, toTrash);

        if (dryRun) {
            return new Result(true, plan, List.of(), List.of());
        }

        Trash trash = new Trash(fs, volumeId, srv.trash(), clock);
        List<String> trashed = new ArrayList<>();
        List<String> failed  = new ArrayList<>();
        String reason = "Duplicate cover — kept " + keep;
        for (String c : toTrash) {
            TitleFolderService.TrashOutcome outcome = folderService.trashCover(trash, folder, c, reason);
            if (outcome.success()) {
                trashed.add(outcome.trashedTo().toString());
            } else {
                failed.add(c + " → " + outcome.error());
            }
        }
        return new Result(false, plan, trashed, failed);
    }

    public record Plan(String volumeId, String folder, String keep, List<String> toTrash) {}
    public record Result(boolean dryRun, Plan plan, List<String> trashed, List<String> failed) {}
}
