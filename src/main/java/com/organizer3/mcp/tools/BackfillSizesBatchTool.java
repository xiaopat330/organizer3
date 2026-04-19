package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import com.organizer3.shell.SessionContext;

import java.io.IOException;
import java.util.List;

/**
 * Fill in {@code size_bytes} for up to {@code limit} videos on the currently-mounted
 * volume. Cursor-paginated so an agent can resume across calls — pass {@code nextCursor}
 * from the previous response as {@code fromId} on the next call. When {@code scanned}
 * comes back 0, all rows without size on that volume have been attempted.
 *
 * <p>Unlike the duration/codec probe (which streams the file through ffmpeg), size is
 * a one-round-trip filesystem stat, so batches can be large and fast.
 */
public class BackfillSizesBatchTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 2000;

    private final SessionContext session;
    private final VideoRepository videoRepo;

    public BackfillSizesBatchTool(SessionContext session, VideoRepository videoRepo) {
        this.session = session;
        this.videoRepo = videoRepo;
    }

    @Override public String name()        { return "backfill_sizes_batch"; }
    @Override public String description() {
        return "Fill size_bytes for up to 'limit' videos on the mounted volume. "
             + "Cursor-paginated: pass nextCursor back as fromId. Stop when scanned=0. "
             + "Requires an active mount.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("fromId", "integer",
                        "Start after this video id. Use 0 for the first call; echo back 'nextCursor' from the previous response.", 0)
                .prop("limit",  "integer",
                        "Maximum videos per call. Default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ".", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one first (see mount_volume).");
        }
        if (!session.isConnected()) {
            throw new IllegalArgumentException("Active volume connection is not live.");
        }
        VolumeFileSystem fs = session.getActiveConnection().fileSystem();

        long fromId = Math.max(0L, Schemas.optLong(args, "fromId", 0));
        int limit   = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        List<Video> batch = videoRepo.findWithoutSize(volumeId, fromId, limit);
        int ok = 0, failed = 0;
        long cursor = fromId;
        for (Video v : batch) {
            cursor = Math.max(cursor, v.getId());
            try {
                long size = fs.size(v.getPath());
                videoRepo.updateSize(v.getId(), size);
                ok++;
            } catch (IOException e) {
                failed++;
            }
        }
        long remaining = videoRepo.countWithoutSize(volumeId);
        return new Result(volumeId, fromId, cursor, batch.size(), ok, failed, remaining);
    }

    public record Result(String volumeId, long fromId, long nextCursor,
                         int scanned, int ok, int failed, long remainingWithoutSize) {}
}
