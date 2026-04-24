package com.organizer3.web.routes;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.trash.TrashItem;
import com.organizer3.trash.TrashListing;
import com.organizer3.trash.TrashService;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import com.organizer3.utilities.task.trash.TrashRestoreTask;
import com.organizer3.utilities.task.trash.TrashScheduleTask;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP endpoints for the Utilities → Trash screen.
 *
 * <ul>
 *   <li>{@code GET  /api/utilities/trash/volumes}                    — list volumes with trash item counts</li>
 *   <li>{@code GET  /api/utilities/trash/volumes/{id}/items}         — paginated trash listing for a volume</li>
 *   <li>{@code POST /api/utilities/trash/schedule}                   — schedule items for deletion (returns runId)</li>
 *   <li>{@code POST /api/utilities/trash/restore}                    — restore items to original paths (returns runId)</li>
 * </ul>
 */
@Slf4j
public final class TrashRoutes {

    private static final String TRASH_FOLDER = "_trash";
    private static final Path TRASH_ROOT = Path.of("/_trash");
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final TrashService trashService;
    private final SmbConnectionFactory smbConnectionFactory;
    private final TaskRegistry registry;
    private final TaskRunner runner;

    public TrashRoutes(TrashService trashService, SmbConnectionFactory smbConnectionFactory,
                       TaskRegistry registry, TaskRunner runner) {
        this.trashService = trashService;
        this.smbConnectionFactory = smbConnectionFactory;
        this.registry = registry;
        this.runner = runner;
    }

    public void register(Javalin app) {
        app.get("/api/utilities/trash/volumes", this::handleListVolumes);
        app.get("/api/utilities/trash/volumes/{id}/count", this::handleCountItems);
        app.get("/api/utilities/trash/volumes/{id}/items", this::handleListItems);
        app.post("/api/utilities/trash/schedule", this::handleSchedule);
        app.post("/api/utilities/trash/restore", this::handleRestore);
    }

    private void handleListVolumes(io.javalin.http.Context ctx) {
        List<VolumeConfig> volumes = AppConfig.get().volumes().volumes();
        List<Map<String, Object>> result = volumes.stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", v.id());
                    m.put("smbPath", v.smbPath());
                    m.put("group", v.group());
                    // Item counts require a live connection — defer to the items endpoint.
                    // Returning null signals "count unknown" to the UI.
                    m.put("itemCount", (Object) null);
                    return m;
                })
                .toList();
        ctx.json(result);
    }

    private void handleCountItems(io.javalin.http.Context ctx) {
        String volumeId = ctx.pathParam("id");
        try (SmbConnectionFactory.SmbShareHandle handle = smbConnectionFactory.open(volumeId)) {
            TrashListing listing = trashService.list(handle.fileSystem(), volumeId, TRASH_ROOT, 0, 0);
            ctx.json(Map.of("count", listing.totalCount()));
        } catch (IllegalArgumentException e) {
            ctx.status(404);
            ctx.json(Map.of("error", "volume not found: " + volumeId));
        } catch (Exception e) {
            log.warn("Failed to count trash for volume {}", volumeId, e.getMessage());
            ctx.json(Map.of("count", -1));
        }
    }

    private void handleListItems(io.javalin.http.Context ctx) {
        String volumeId = ctx.pathParam("id");
        int page = parseIntParam(ctx.queryParam("page"), 0);
        int pageSize = parseIntParam(ctx.queryParam("pageSize"), DEFAULT_PAGE_SIZE);

        try (SmbConnectionFactory.SmbShareHandle handle = smbConnectionFactory.open(volumeId)) {
            TrashListing listing = trashService.list(handle.fileSystem(), volumeId, TRASH_ROOT, page, pageSize);
            ctx.json(toJson(listing));
        } catch (IllegalArgumentException e) {
            ctx.status(404);
            ctx.json(Map.of("error", "volume not found: " + volumeId));
        } catch (Exception e) {
            log.error("Failed to list trash for volume {}", volumeId, e);
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage()));
        }
    }

    private void handleSchedule(io.javalin.http.Context ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "request body is required"));
            return;
        }

        String volumeId = (String) body.get("volumeId");
        @SuppressWarnings("unchecked")
        List<String> sidecarPaths = (List<String>) body.get("sidecarPaths");
        String scheduledAt = (String) body.get("scheduledAt");

        if (volumeId == null || sidecarPaths == null || sidecarPaths.isEmpty() || scheduledAt == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "volumeId, sidecarPaths, and scheduledAt are required"));
            return;
        }

        // Validate scheduledAt parses as ISO-8601 instant
        try {
            Instant.parse(scheduledAt);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(Map.of("error", "scheduledAt must be an ISO-8601 instant: " + scheduledAt));
            return;
        }

        Map<String, Object> inputs = Map.of(
                "volumeId", volumeId,
                "sidecarPaths", sidecarPaths,
                "scheduledAt", scheduledAt
        );
        startTask(ctx, TrashScheduleTask.ID, inputs);
    }

    private void handleRestore(io.javalin.http.Context ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "request body is required"));
            return;
        }

        String volumeId = (String) body.get("volumeId");
        @SuppressWarnings("unchecked")
        List<String> sidecarPaths = (List<String>) body.get("sidecarPaths");

        if (volumeId == null || sidecarPaths == null || sidecarPaths.isEmpty()) {
            ctx.status(400);
            ctx.json(Map.of("error", "volumeId and sidecarPaths are required"));
            return;
        }

        Map<String, Object> inputs = Map.of(
                "volumeId", volumeId,
                "sidecarPaths", sidecarPaths
        );
        startTask(ctx, TrashRestoreTask.ID, inputs);
    }

    private void startTask(io.javalin.http.Context ctx, String taskId, Map<String, Object> inputs) {
        try {
            TaskRun run = runner.start(taskId, new TaskInputs(inputs));
            ctx.json(Map.of("runId", run.runId(), "taskId", taskId));
        } catch (TaskRunner.TaskInFlightException e) {
            ctx.status(409);
            ctx.json(Map.of(
                    "error", e.getMessage(),
                    "runningTaskId", e.runningTaskId,
                    "runningRunId", e.runningRunId));
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toJson(TrashListing listing) {
        return Map.of(
                "items", listing.items().stream().map(TrashRoutes::itemToJson).toList(),
                "totalCount", listing.totalCount(),
                "page", listing.page(),
                "pageSize", listing.pageSize()
        );
    }

    private static Map<String, Object> itemToJson(TrashItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sidecarPath", item.sidecarPath().toString());
        var sc = item.sidecar();
        m.put("originalPath", sc.originalPath());
        m.put("trashedAt", sc.trashedAt());
        m.put("volumeId", sc.volumeId());
        m.put("reason", sc.reason());
        m.put("scheduledDeletionAt", sc.scheduledDeletionAt());
        m.put("lastDeletionAttempt", sc.lastDeletionAttempt());
        m.put("lastDeletionError", sc.lastDeletionError());
        return m;
    }

    private static int parseIntParam(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
