package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.trash.TrashItem;
import com.organizer3.trash.TrashListing;
import com.organizer3.trash.TrashService;

import java.nio.file.Path;
import java.util.List;

/**
 * List trashed items on a volume. Requires an active SMB connection to the volume.
 * Items include the original path, trash reason, scheduled-deletion date, and restore path.
 */
public class ListTrashItemsTool implements Tool {

    private static final Path TRASH_ROOT    = Path.of("/_trash");
    private static final int  DEFAULT_PAGE  = 0;
    private static final int  DEFAULT_LIMIT = 50;
    private static final int  MAX_LIMIT     = 500;

    private final TrashService trashService;
    private final SmbConnectionFactory smbFactory;

    public ListTrashItemsTool(TrashService trashService, SmbConnectionFactory smbFactory) {
        this.trashService = trashService;
        this.smbFactory   = smbFactory;
    }

    @Override public String name()        { return "list_trash_items"; }
    @Override public String description() {
        return "List trashed items on a volume (requires SMB connectivity). "
             + "Returns sidecar metadata: original path, reason, trash date, scheduled deletion, "
             + "and restore status.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Volume id to inspect.")
                .prop("page",     "integer", "0-based page index. Default 0.", DEFAULT_PAGE)
                .prop("pageSize", "integer", "Items per page. Default 50, max 500.", DEFAULT_LIMIT)
                .require("volumeId")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.requireString(args, "volumeId").trim();
        int page        = Math.max(0, Schemas.optInt(args, "page", DEFAULT_PAGE));
        int pageSize    = Math.max(1, Math.min(Schemas.optInt(args, "pageSize", DEFAULT_LIMIT), MAX_LIMIT));

        try {
            return smbFactory.withRetry(volumeId, handle -> {
                TrashListing listing = trashService.list(handle.fileSystem(), volumeId, TRASH_ROOT, page, pageSize);
                List<ItemRow> rows = listing.items().stream().map(ListTrashItemsTool::toRow).toList();
                return new Result(listing.totalCount(), listing.page(), listing.pageSize(), rows);
            });
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Volume not found: " + volumeId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to list trash for volume " + volumeId + ": " + e.getMessage(), e);
        }
    }

    private static ItemRow toRow(TrashItem item) {
        var sc = item.sidecar();
        return new ItemRow(
                item.sidecarPath().toString(),
                sc.originalPath(),
                sc.trashedAt(),
                sc.volumeId(),
                sc.reason(),
                sc.scheduledDeletionAt(),
                sc.lastDeletionAttempt(),
                sc.lastDeletionError()
        );
    }

    public record ItemRow(String sidecarPath, String originalPath, String trashedAt,
                          String volumeId, String reason, String scheduledDeletionAt,
                          String lastDeletionAttempt, String lastDeletionError) {}
    public record Result(int totalCount, int page, int pageSize, List<ItemRow> items) {}
}
