package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.FolderRegistrar;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers a single manually-placed title folder on the mounted volume into the DB
 * (title_locations + videos) without requiring a full-volume sync.
 *
 * <p>This is a scoped, additive operation — it never calls {@code deleteByVolume} or
 * {@code markStaleByVolume}. See §3a of {@code PROPOSAL_RELOCATION_TOOLING.md}.
 *
 * <p>Default {@code dryRun: true}.
 */
@Slf4j
public class RegisterFolderTool implements Tool {

    private final SessionContext session;
    private final FolderRegistrar registrar;

    public RegisterFolderTool(SessionContext session, FolderRegistrar registrar) {
        this.session   = session;
        this.registrar = registrar;
    }

    @Override public String name() { return "register_folder"; }

    @Override
    public String description() {
        return "Registers one manually-placed title folder on the mounted volume into the DB "
             + "(title_locations + videos) without a full-volume sync. "
             + "Additive only — never touches other locations. "
             + "Refuses if the folder name contains no parseable JAV code. "
             + "Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Must match the currently mounted volume id.")
                .prop("path",     "string",  "Volume-relative path to the title folder (e.g. '/queue/Marin Yakuno (IPZZ-679)').")
                .prop("dryRun",   "boolean", "If true (default), return the plan without writing anything.", true)
                .require("volumeId", "path")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = Schemas.requireString(args, "volumeId").trim();
        String pathArg  = Schemas.requireString(args, "path").trim();
        boolean dryRun  = Schemas.optBoolean(args, "dryRun", true);

        // ── Volume guard ────────────────────────────────────────────────────
        String mountedVolumeId = session.getMountedVolumeId();
        if (mountedVolumeId == null) {
            return error("no volume mounted");
        }
        if (!mountedVolumeId.equals(volumeId)) {
            return error("volumeId '" + volumeId + "' does not match the mounted volume '"
                    + mountedVolumeId + "'");
        }

        VolumeConnection conn = session.getActiveConnection();
        VolumeFileSystem fs   = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;
        if (fs == null) {
            return error("volume '" + volumeId + "' is not connected");
        }

        // ── Resolve path ────────────────────────────────────────────────────
        Path titleFolder = Path.of(pathArg);

        if (!fs.exists(titleFolder)) {
            return error("path '" + pathArg + "' does not exist on volume '" + volumeId + "'");
        }
        if (!fs.isDirectory(titleFolder)) {
            return error("path '" + pathArg + "' is not a directory");
        }

        // ── Derive partition id (mirror MoveTitleFolderTool.derivePartitionId) ─
        Path destParent  = titleFolder.getParent();
        String partitionId = derivePartitionId(destParent != null ? destParent : titleFolder);

        // ── Dry-run plan ─────────────────────────────────────────────────────
        if (dryRun) {
            try {
                FolderRegistrar.RegistrationPlan plan =
                        registrar.plan(titleFolder, volumeId, partitionId, fs);
                return buildPlanResponse(plan, dryRun);
            } catch (IOException e) {
                log.warn("register_folder plan failed volume={} path={}", volumeId, pathArg, e);
                return error("filesystem error during plan: " + e.getMessage());
            }
        }

        // ── Commit ──────────────────────────────────────────────────────────
        try {
            FolderRegistrar.RegistrationResult result =
                    registrar.register(titleFolder, volumeId, partitionId, fs);
            return buildResultResponse(result, dryRun);
        } catch (IOException e) {
            log.warn("register_folder commit failed volume={} path={}", volumeId, pathArg, e);
            return error("filesystem error during registration: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Derives a partition_id from the folder's parent path.
     * Mirrors the logic in {@link MoveTitleFolderTool#derivePartitionId}.
     */
    private String derivePartitionId(Path parent) {
        if (parent.getNameCount() >= 2) {
            String top = parent.getName(0).toString();
            if ("stars".equals(top)) {
                return parent.getName(1).toString(); // tier
            }
        }
        return parent.getName(0).toString();
    }

    private Map<String, Object> buildPlanResponse(FolderRegistrar.RegistrationPlan plan, boolean dryRun) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dryRun", dryRun);
        r.put("committed", false);
        if (plan.refused()) {
            r.put("ok", false);
            r.put("error", plan.refusalReason());
            r.put("code", plan.code());
            return r;
        }
        r.put("ok", true);
        r.put("code", plan.code());
        r.put("isNewTitle", plan.isNewTitle());
        r.put("partitionId", plan.partitionId());
        r.put("path", plan.path());
        r.put("videosToRegister", plan.videosToRegister());
        r.put("videoCount", plan.videosToRegister().size());
        r.put("castInferred", plan.castInferred());
        if (plan.castInferred()) {
            r.put("inferredActressName", plan.inferredActressName());
        }
        return r;
    }

    private Map<String, Object> buildResultResponse(FolderRegistrar.RegistrationResult result, boolean dryRun) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dryRun", dryRun);
        if (result.refused()) {
            r.put("ok", false);
            r.put("committed", false);
            r.put("error", result.refusalReason());
            r.put("code", result.code());
            return r;
        }
        r.put("ok", true);
        r.put("committed", true);
        r.put("titleId", result.titleId());
        r.put("code", result.code());
        r.put("isNewTitle", result.isNewTitle());
        r.put("partitionId", result.partitionId());
        r.put("path", result.path());
        r.put("videosRegistered", result.videosRegistered());
        r.put("videoCount", result.videosRegistered().size());
        r.put("castInferred", result.castInferred());
        return r;
    }

    private Map<String, Object> error(String reason) {
        log.info("register_folder refused: {}", reason);
        return Map.of("ok", false, "error", reason);
    }
}
