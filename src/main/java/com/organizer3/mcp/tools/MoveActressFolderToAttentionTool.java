package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.command.ActressMergeService;
import com.organizer3.command.ActressMergeService.ActressFolderEntry;
import com.organizer3.command.ActressMergeService.ActressFolderPlan;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Moves the actress-level folder (e.g. {@code /stars/minor/Asusa Misaki/}) to
 * {@code /attention/<canonicalName>/} and writes a REASON.txt sidecar explaining why.
 * Also updates all {@code title_locations} paths + {@code partition_id} in the DB.
 *
 * <p>Use this after {@code rename_actress_folders} when the actress-level parent folder
 * still carries the old name and cannot be renamed in-place (e.g. because it is on the
 * wrong volume, or because you need a human to confirm the final rename before a sync).
 *
 * <p>Only acts on the currently mounted volume. Gated on {@code mcp.allowFileOps}.
 * Default {@code dryRun: true}.
 */
@Slf4j
public class MoveActressFolderToAttentionTool implements Tool {

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final ActressMergeService mergeService;
    private final Clock clock;

    public MoveActressFolderToAttentionTool(SessionContext session, ActressRepository actressRepo,
                                             ActressMergeService mergeService, Clock clock) {
        this.session = session;
        this.actressRepo = actressRepo;
        this.mergeService = mergeService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override public String name() { return "move_actress_folder_to_attention"; }

    @Override public String description() {
        return "Moves the actress-level folder (e.g. /stars/minor/OldName/) to "
             + "/attention/<canonicalName>/ on the mounted volume and writes a REASON.txt "
             + "sidecar. Updates all title_locations paths in the DB. Call after "
             + "rename_actress_folders when the parent actress folder still uses the old name. "
             + "Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id. Either this or 'name' is required.")
                .prop("name",       "string",  "Canonical name or alias to resolve. Either this or 'actress_id' is required.")
                .prop("dryRun",     "boolean", "If true (default), return the plan without moving.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long idArg     = Schemas.optLong(args, "actress_id", -1);
        String nameArg = Schemas.optString(args, "name", null);
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        if (idArg < 0 && (nameArg == null || nameArg.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'actress_id' or 'name'");
        }

        Actress actress = (idArg >= 0
                ? actressRepo.findById(idArg)
                : actressRepo.resolveByName(nameArg))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actress found for " + (idArg >= 0 ? "id=" + idArg : "name=" + nameArg)));

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        ActressFolderPlan plan = mergeService.planActressFolderMoveFor(actress, mountedVolumeId);

        if (plan.entries().isEmpty()) {
            return new Result(actress.getId(), actress.getCanonicalName(), dryRun,
                    mountedVolumeId, List.of(), List.of(), "nothing-to-do");
        }

        if (dryRun || fs == null) {
            List<PlannedMove> planned = plan.entries().stream()
                    .map(e -> new PlannedMove(
                            e.actressFolder().toString(),
                            "/attention/" + actress.getCanonicalName(),
                            e.locations().stream().map(l -> l.newPath().toString()).toList()))
                    .toList();
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            return new Result(actress.getId(), actress.getCanonicalName(), true,
                    mountedVolumeId, planned, List.of(), status);
        }

        // Execute: route each actress folder to attention, then update DB
        List<PlannedMove> moved = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();

        String aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName)
                .collect(Collectors.joining(", "));

        for (ActressFolderEntry entry : plan.entries()) {
            try {
                AttentionRouter router = new AttentionRouter(fs, mountedVolumeId, clock);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("canonicalName",      actress.getCanonicalName());
                headers.put("previousFolderName", entry.oldName());
                if (!aliases.isBlank()) headers.put("aliases", aliases);

                String body = buildSidecarBody(actress.getCanonicalName(), entry.oldName(),
                        mountedVolumeId, aliases);

                router.route(entry.actressFolder(), actress.getCanonicalName(),
                        "actress-folder-old-name", headers, body);

                moved.add(new PlannedMove(
                        entry.actressFolder().toString(),
                        "/attention/" + actress.getCanonicalName(),
                        entry.locations().stream().map(l -> l.newPath().toString()).toList()));

            } catch (IOException e) {
                log.warn("Failed to route actress folder {} to attention: {}", entry.actressFolder(), e.getMessage());
                errors.add(entry.actressFolder() + ": " + e.getMessage());
            }
        }

        if (!moved.isEmpty()) {
            mergeService.applyActressFolderMove(plan);
        }

        String status = errors.isEmpty() ? "moved" : (moved.isEmpty() ? "failed" : "partial");
        return new Result(actress.getId(), actress.getCanonicalName(), false,
                mountedVolumeId, moved, errors, status);
    }

    private static String buildSidecarBody(String canonicalName, String oldName,
                                            String volumeId, String aliases) {
        StringBuilder sb = new StringBuilder();
        sb.append("Actress folder was filed under the old name '").append(oldName)
          .append("' on volume '").append(volumeId).append("'. ");
        sb.append("The title folders inside have already been renamed to use the canonical name '")
          .append(canonicalName).append("'. ");
        sb.append("This folder has been moved here so the parent can be inspected and the volume re-synced. ");
        if (!aliases.isBlank()) {
            sb.append("Known aliases: ").append(aliases).append(". ");
        }
        sb.append("After verifying the contents, run 'sync all' on volume '").append(volumeId)
          .append("' to restore correct path records.");
        return sb.toString();
    }

    public record PlannedMove(String actressFolderFrom, String actressFolderTo, List<String> updatedPaths) {}

    public record Result(
            long actressId,
            String canonicalName,
            boolean dryRun,
            String mountedVolumeId,
            List<PlannedMove> moved,
            List<String> errors,
            String status
    ) {}
}
