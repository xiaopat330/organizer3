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
 * Walks title folders on the currently-mounted volume and flags those with more than
 * one cover image directly at the base folder. The layout convention is one cover per
 * title, so ≥2 covers strongly suggests a duplication event (manual copy, merged
 * folder, reprint handling, etc.).
 *
 * <p>Cover images are identified by extension (jpg/jpeg/png/webp). Only files at the
 * base folder are counted; subfolder contents are ignored here (see
 * {@code find_misfiled_covers} for that signal).
 */
public class FindMultiCoverTitlesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FindMultiCoverTitlesTool.class);

    private static final int DEFAULT_LIMIT  = 50;
    private static final int MAX_LIMIT      = 1000;
    private static final int DEFAULT_OFFSET = 0;
    private static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");

    private final SessionContext session;
    private final Jdbi jdbi;

    public FindMultiCoverTitlesTool(SessionContext session, Jdbi jdbi) {
        this.session = session;
        this.jdbi = jdbi;
    }

    @Override public String name()        { return "find_multi_cover_titles"; }
    @Override public String description() {
        return "Scan title folders on the mounted volume and flag ones with >1 cover image at the base. "
             + "Scans up to 'limit' title locations starting at 'offset'; paginate with offset to cover "
             + "a whole volume across multiple calls.";
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
                List<String> covers = new ArrayList<>();
                for (Path child : fs.listDirectory(folder)) {
                    if (fs.isDirectory(child)) continue;
                    String name = fileName(child);
                    if (isCover(name)) covers.add(name);
                }
                if (covers.size() >= 2) {
                    hits.add(new Hit(loc.titleCode, loc.path, covers));
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

    static boolean isCover(String filename) {
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
    public record Hit(String titleCode, String path, List<String> covers) {}
    public record Result(String volumeId, int offset, int scanned, int hitCount, int errors, List<Hit> hits) {}
}
