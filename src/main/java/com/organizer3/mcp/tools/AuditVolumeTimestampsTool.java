package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.organize.TitleTimestampService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk every title folder on the mounted volume and correct timestamps where the
 * folder's time differs from the earliest-child-time. Paginated for large volumes.
 *
 * <p>See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.5. Gated on
 * {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class AuditVolumeTimestampsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AuditVolumeTimestampsTool.class);

    private static final int DEFAULT_LIMIT  = 100;
    private static final int MAX_LIMIT      = 1000;
    private static final int DEFAULT_OFFSET = 0;

    private final SessionContext session;
    private final Jdbi jdbi;
    private final TitleTimestampService service;

    public AuditVolumeTimestampsTool(SessionContext session, Jdbi jdbi, TitleTimestampService service) {
        this.session = session;
        this.jdbi = jdbi;
        this.service = service;
    }

    @Override public String name()        { return "audit_volume_timestamps"; }
    @Override public String description() {
        return "Walk title folders on the mounted volume; for each folder where the current timestamps "
             + "differ from the earliest child timestamp, apply the correction (or report on dryRun). "
             + "Paginate with offset.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit",  "integer", "Max title folders to inspect per call. Default 100, max 1000.", DEFAULT_LIMIT)
                .prop("offset", "integer", "Skip this many title locations before scanning. Default 0.", DEFAULT_OFFSET)
                .prop("dryRun", "boolean", "If true (default), report without touching folders.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit   = Math.max(1, Math.min(Schemas.optInt(args, "limit",  DEFAULT_LIMIT),  MAX_LIMIT));
        int offset  = Math.max(0, Schemas.optInt(args, "offset", DEFAULT_OFFSET));
        boolean dry = Schemas.optBoolean(args, "dryRun", true);

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException("No volume is currently mounted. Mount one before calling this tool.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        List<LocationRef> locations = fetchLocations(volumeId, limit, offset);
        int scanned = 0, errors = 0, changed = 0, skipped = 0;
        List<Hit> hits = new ArrayList<>();

        for (LocationRef loc : locations) {
            scanned++;
            Path folder = Path.of(loc.path);
            try {
                if (!fs.exists(folder) || !fs.isDirectory(folder)) {
                    skipped++;
                    continue;
                }
                TitleTimestampService.Result r = service.apply(fs, folder, dry);
                if (r.plan().needsChange()) {
                    if (!dry && r.applied()) changed++;
                    hits.add(new Hit(
                            loc.titleCode,
                            loc.path,
                            r.plan().folderCurrent().modified(),
                            r.plan().earliestChildTime(),
                            r.applied(),
                            r.error()));
                }
            } catch (Exception e) {
                errors++;
                log.debug("audit_volume_timestamps error on {}: {}", loc.path, e.getMessage());
            }
        }

        return new Result(volumeId, offset, scanned, hits.size(), changed, skipped, errors, dry, hits);
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

    private record LocationRef(String titleCode, String path) {}

    public record Hit(
            String titleCode,
            String path,
            Instant folderCurrent,
            Instant earliestChild,
            boolean applied,
            String error
    ) {}

    public record Result(
            String volumeId,
            int offset,
            int scanned,
            int hitCount,
            int changed,
            int skipped,
            int errors,
            boolean dryRun,
            List<Hit> hits
    ) {}
}
