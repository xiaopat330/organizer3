package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.command.ActressMergeService;
import com.organizer3.command.ActressMergeService.AttentionExitPlan;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Moves the actress-level folder from {@code /attention/<canonicalName>/} back to
 * {@code /stars/<tier>/<canonicalName>/} on the mounted volume.
 *
 * <p>This is the symmetric inverse of {@link MoveActressFolderToAttentionTool}. Use it after
 * a manual inspection confirms the folder contents are correct and the actress is ready to be
 * filed at her canonical location.
 *
 * <p>The tier is read from the actress record ({@code Actress.Tier}). If the destination
 * already exists, the tool refuses with a collision error. If the source does not exist,
 * the tool refuses with a not-found error.
 *
 * <p>Only acts on the currently mounted volume. Default {@code dryRun: true}.
 */
@Slf4j
public class MoveActressFolderTool implements Tool {

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final ActressMergeService mergeService;
    private final CurationLog curationLog;

    public MoveActressFolderTool(SessionContext session, ActressRepository actressRepo,
                                  ActressMergeService mergeService, Clock clock,
                                  CurationLog curationLog) {
        this.session = session;
        this.actressRepo = actressRepo;
        this.mergeService = mergeService;
        this.curationLog = curationLog;
    }

    @Override public String name() { return "move_actress_folder"; }

    @Override public String description() {
        return "Moves the actress-level folder from /attention/<canonicalName>/ back to "
             + "/stars/<tier>/<canonicalName>/ on the mounted volume. Symmetric inverse of "
             + "move_actress_folder_to_attention. Updates all title_locations paths in the DB. "
             + "Refuses if the source does not exist or the destination is already occupied. "
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

        AttentionExitPlan plan = mergeService.planMoveActressFolderFromAttention(actress, mountedVolumeId);

        Map<String, Object> curationInputs = Map.of(
                "actressId", actress.getId(), "canonical", actress.getCanonicalName(), "dryRun", dryRun);

        Path source = plan.source();
        Path destination = plan.destination();

        if (dryRun || fs == null) {
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            emitLog(mountedVolumeId, curationInputs, status, List.of());
            return new Result(actress.getId(), actress.getCanonicalName(), plan.tier(), true,
                    mountedVolumeId, source.toString(), destination.toString(),
                    plan.locations().size(), List.of(), status);
        }

        // Validate source exists
        if (!fs.exists(source) || !fs.isDirectory(source)) {
            String msg = "source does not exist: " + source;
            log.warn("[MoveActressFolderTool] {} — actress={}", msg, actress.getCanonicalName());
            emitLog(mountedVolumeId, curationInputs, "source-not-found", List.of(msg));
            return new Result(actress.getId(), actress.getCanonicalName(), plan.tier(), false,
                    mountedVolumeId, source.toString(), destination.toString(),
                    0, List.of(msg), "source-not-found");
        }

        // Validate destination is clear
        if (fs.exists(destination)) {
            String msg = "destination already exists (collision): " + destination;
            log.warn("[MoveActressFolderTool] {} — actress={}", msg, actress.getCanonicalName());
            emitLog(mountedVolumeId, curationInputs, "collision", List.of(msg));
            return new Result(actress.getId(), actress.getCanonicalName(), plan.tier(), false,
                    mountedVolumeId, source.toString(), destination.toString(),
                    0, List.of(msg), "collision");
        }

        // Execute: atomic move then DB update
        try {
            fs.createDirectories(destination.getParent());
            fs.move(source, destination);
            log.info("[MoveActressFolderTool] moved actress folder — actressId={} canonical=\"{}\" tier={} from={} to={} titleLocations={}",
                    actress.getId(), actress.getCanonicalName(), plan.tier(), source, destination, plan.locations().size());
        } catch (IOException e) {
            String msg = "filesystem move failed: " + e.getMessage();
            log.warn("[MoveActressFolderTool] {} — actress={} from={} to={}",
                    msg, actress.getCanonicalName(), source, destination);
            emitLog(mountedVolumeId, curationInputs, "failed", List.of(msg));
            return new Result(actress.getId(), actress.getCanonicalName(), plan.tier(), false,
                    mountedVolumeId, source.toString(), destination.toString(),
                    0, List.of(msg), "failed");
        }

        mergeService.applyMoveActressFolderFromAttention(plan);

        emitLog(mountedVolumeId, curationInputs, "moved", List.of());
        return new Result(actress.getId(), actress.getCanonicalName(), plan.tier(), false,
                mountedVolumeId, source.toString(), destination.toString(),
                plan.locations().size(), List.of(), "moved");
    }

    private void emitLog(String volumeId, Map<String, Object> inputs, String status, List<String> errors) {
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", "mcp-" + Thread.currentThread().getName(),
                inputs, null, null,
                Map.of(),
                status, List.copyOf(errors));
        curationLog.append(volumeId != null ? volumeId : "unknown", rec);
    }

    public record Result(
            long actressId,
            String canonicalName,
            String tier,
            boolean dryRun,
            String mountedVolumeId,
            String source,
            String destination,
            int locationsUpdated,
            List<String> errors,
            String status
    ) {}
}
