package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Find cover images stored inside a title's video subfolder instead of at the base.
 * By convention covers live at the title's base folder; a cover inside {@code video/},
 * {@code h265/}, {@code 4K/}, etc. is misfiled and should be moved up.
 *
 * <p>Paginates with {@code limit} / {@code offset} the same way {@code find_multi_cover_titles}
 * does — scans up to {@code limit} title folders per call.
 */
public class FindMisfiledCoversTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FindMisfiledCoversTool.class);

    private static final int DEFAULT_LIMIT  = 50;
    private static final int MAX_LIMIT      = 1000;
    private static final int DEFAULT_OFFSET = 0;
    private static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");

    private final SessionContext session;
    private final Jdbi jdbi;

    public FindMisfiledCoversTool(SessionContext session, Jdbi jdbi) {
        this.session = session;
        this.jdbi = jdbi;
    }

    @Override public String name()        { return "find_misfiled_covers"; }
    @Override public String description() {
        return "Scan title folders on the mounted volume and flag those with cover images inside "
             + "a video subfolder (video/, h265/, 4K/, ...) instead of at the base. Paginate with offset.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit",  "integer", "Max title folders to inspect per call. Default 50, max 1000.", DEFAULT_LIMIT)
                .prop("offset", "integer", "Skip this many title locations before scanning. Default 0.", DEFAULT_OFFSET)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit  = Math.max(1, Math.min(Schemas.optInt(args, "limit",  DEFAULT_LIMIT),  MAX_LIMIT));
        int offset = Math.max(0, Schemas.optInt(args, "offset", DEFAULT_OFFSET));

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one with the shell before using this tool.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        List<LocationRef> locations = fetchLocations(volumeId, limit, offset);
        List<Hit> hits = new ArrayList<>();
        int scanned = 0, errors = 0;
        for (LocationRef loc : locations) {
            scanned++;
            try {
                Path folder = Path.of(loc.path);
                if (!fs.exists(folder) || !fs.isDirectory(folder)) continue;
                for (Path child : fs.listDirectory(folder)) {
                    if (!fs.isDirectory(child)) continue;
                    List<String> covers = new ArrayList<>();
                    for (Path inner : fs.listDirectory(child)) {
                        if (fs.isDirectory(inner)) continue;
                        String name = fileName(inner);
                        if (isCover(name)) covers.add(name);
                    }
                    if (!covers.isEmpty()) {
                        hits.add(new Hit(loc.titleCode, loc.path, fileName(child), covers));
                    }
                }
            } catch (Exception e) {
                errors++;
                log.debug("scan error on {}: {}", loc.path, e.getMessage());
            }
        }
        return new Result(volumeId, offset, scanned, hits.size(), errors, hits);
    }

    private List<LocationRef> fetchLocations(String volumeId, int limit, int offset) {
        return jdbi.withHandle(h -> {
            List<LocationRef> out = new ArrayList<>();
            h.createQuery("""
                    SELECT t.code AS code, tl.path AS path
                    FROM title_locations tl
                    JOIN titles t ON t.id = tl.title_id
                    WHERE tl.volume_id = :volumeId
                    ORDER BY tl.id
                    LIMIT :limit OFFSET :offset
                    """)
                    .bind("volumeId", volumeId)
                    .bind("limit", limit)
                    .bind("offset", offset)
                    .map((rs, ctx) -> new LocationRef(rs.getString("code"), rs.getString("path")))
                    .forEach(out::add);
            return out;
        });
    }

    private static boolean isCover(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return COVER_EXTS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    private record LocationRef(String titleCode, String path) {}
    public record Hit(String titleCode, String titlePath, String subfolder, List<String> covers) {}
    public record Result(String volumeId, int offset, int scanned, int hitCount, int errors, List<Hit> hits) {}
}
