package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Move misfiled cover images from inside a title's video subfolder up to the title's base.
 *
 * <p>Actions the output of {@code find_misfiled_covers}. Given a title code, finds covers
 * living in child sub-directories (e.g. {@code video/}, {@code h265/}) of the title's base
 * folder on the currently-mounted volume and moves them up to the base folder.
 *
 * <p>If a file with the same name already exists at the base folder, that one is left in
 * place and the misfiled copy is recorded as a collision — not moved. Use
 * {@code trash_duplicate_cover} afterwards to resolve.
 *
 * <p>Gated on {@code mcp.allowMutations} + {@code mcp.allowFileOps}. Defaults to dryRun.
 */
public class MoveCoverToBaseTool implements Tool {

    private static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");

    private final SessionContext session;
    private final Jdbi jdbi;

    public MoveCoverToBaseTool(SessionContext session, Jdbi jdbi) {
        this.session = session;
        this.jdbi = jdbi;
    }

    @Override public String name()        { return "move_cover_to_base"; }
    @Override public String description() {
        return "Move any covers found inside subfolders of a title's folder up to the base folder. "
             + "Safe against same-name collisions (skipped). Defaults to dryRun:true.";
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
        VolumeFileSystem fs = conn.fileSystem();

        Path base = lookupTitleFolder(volumeId, titleCode);
        if (!fs.exists(base) || !fs.isDirectory(base)) {
            throw new IllegalArgumentException("Title folder does not exist on volume: " + base);
        }

        List<Action> planned = new ArrayList<>();
        List<Action> collisions = new ArrayList<>();
        scan(fs, base, planned, collisions);

        if (planned.isEmpty() && collisions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Title '" + titleCode + "' has no misfiled covers — nothing to move.");
        }

        List<Action> moved = new ArrayList<>();
        List<Action> failed = new ArrayList<>();
        if (!dryRun) {
            for (Action a : planned) {
                try {
                    fs.move(Path.of(a.from()), Path.of(a.to()));
                    moved.add(a);
                } catch (IOException e) {
                    failed.add(new Action(a.from(), a.to(), e.getMessage()));
                }
            }
        }

        return new Result(
                dryRun,
                volumeId,
                base.toString(),
                planned,
                collisions,
                moved,
                failed);
    }

    private void scan(VolumeFileSystem fs, Path base, List<Action> planned, List<Action> collisions) {
        try {
            for (Path child : fs.listDirectory(base)) {
                if (!fs.isDirectory(child)) continue;
                for (Path inner : fs.listDirectory(child)) {
                    if (fs.isDirectory(inner)) continue;
                    Path name = inner.getFileName();
                    if (name == null) continue;
                    String filename = name.toString();
                    if (!isCover(filename)) continue;
                    Path dest = base.resolve(filename);
                    Action a = new Action(inner.toString(), dest.toString(), null);
                    if (fs.exists(dest)) {
                        collisions.add(new Action(inner.toString(), dest.toString(), "destination already exists"));
                    } else {
                        planned.add(a);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to scan under " + base + ": " + e.getMessage(), e);
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

    static boolean isCover(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return COVER_EXTS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public record Action(String from, String to, String note) {}
    public record Result(
            boolean dryRun,
            String volumeId,
            String baseFolder,
            List<Action> planned,
            List<Action> collisions,
            List<Action> moved,
            List<Action> failed
    ) {}
}
