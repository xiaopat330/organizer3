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
import com.organizer3.trash.Trash;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Trash duplicate cover images from a title folder, keeping one chosen cover in place.
 *
 * <p>Intended to action the output of {@code find_multi_cover_titles}. Given a title code
 * and a filename to keep, this tool trashes all other cover-extension files at the title's
 * base folder on the currently-mounted volume, using the Trash primitive
 * ({@code spec/PROPOSAL_TRASH.md}).
 *
 * <p>Gated on both {@code mcp.allowMutations} and {@code mcp.allowFileOps}. Requires the
 * volume's server to have a {@code trash:} folder configured.
 *
 * <p>Defaults to {@code dryRun: true}.
 */
public class TrashDuplicateCoverTool implements Tool {

    private static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");

    private final SessionContext session;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final Clock clock;

    public TrashDuplicateCoverTool(SessionContext session, Jdbi jdbi, OrganizerConfig config) {
        this(session, jdbi, config, Clock.systemUTC());
    }

    TrashDuplicateCoverTool(SessionContext session, Jdbi jdbi, OrganizerConfig config, Clock clock) {
        this.session = session;
        this.jdbi = jdbi;
        this.config = config;
        this.clock = clock;
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

        Path folder = lookupTitleFolder(volumeId, titleCode);
        VolumeFileSystem fs = conn.fileSystem();
        if (!fs.exists(folder) || !fs.isDirectory(folder)) {
            throw new IllegalArgumentException("Title folder does not exist on volume: " + folder);
        }

        List<String> covers = listCovers(fs, folder);
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

        Plan plan = new Plan(
                volumeId,
                folder.toString(),
                keep,
                toTrash);

        if (!dryRun) {
            Trash trash = new Trash(fs, volumeId, srv.trash(), clock);
            List<String> trashed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (String c : toTrash) {
                Path src = folder.resolve(c);
                try {
                    Trash.Result r = trash.trashItem(src, "Duplicate cover — kept " + keep);
                    trashed.add(r.trashedPath().toString());
                } catch (IOException e) {
                    failed.add(c + " → " + e.getMessage());
                }
            }
            return new Result(false, plan, trashed, failed);
        }
        return new Result(true, plan, List.of(), List.of());
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

    private static List<String> listCovers(VolumeFileSystem fs, Path folder) {
        List<String> out = new ArrayList<>();
        try {
            for (Path child : fs.listDirectory(folder)) {
                if (fs.isDirectory(child)) continue;
                Path name = child.getFileName();
                if (name == null) continue;
                String n = name.toString();
                if (isCover(n)) out.add(n);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list cover candidates under " + folder + ": " + e.getMessage(), e);
        }
        return out;
    }

    static boolean isCover(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return COVER_EXTS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public record Plan(String volumeId, String folder, String keep, List<String> toTrash) {}
    public record Result(boolean dryRun, Plan plan, List<String> trashed, List<String> failed) {}
}
