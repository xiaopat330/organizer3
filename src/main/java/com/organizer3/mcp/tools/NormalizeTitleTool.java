package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.titlefolder.TitleFolderService;
import com.organizer3.titlefolder.TitleFolderService.MovePair;
import com.organizer3.titlefolder.TitleFolderService.NormalizationOutcome;
import com.organizer3.titlefolder.TitleFolderService.NormalizationPlan;
import com.organizer3.titlefolder.TitleFolderService.NormalizationPlanEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 of the organize pipeline: normalize filenames inside a title's folder.
 * See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.1.
 *
 * <p>Thin adapter over {@link TitleFolderService#planNormalization} (dryRun) and
 * {@link TitleFolderService#executeNormalization} (live). Output is mapped to the
 * legacy {@link Result} shape so existing MCP clients remain stable.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class NormalizeTitleTool implements Tool {

    private final SessionContext session;
    private final TitleFolderService folderService;

    public NormalizeTitleTool(SessionContext session, TitleFolderService folderService) {
        this.session       = session;
        this.folderService = folderService;
    }

    @Override public String name()        { return "normalize_title"; }
    @Override public String description() {
        return "Rename a title's covers + videos to canonical {CODE}.{ext} and move videos into "
             + "the appropriate subfolder (4K / h265 / video). Conflict entries are flagged when "
             + "two files map to the same canonical name. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string",  "Product code (e.g. 'MIDE-123'). Case-insensitive lookup; rename uses the canonical casing you pass.")
                .prop("dryRun",    "boolean", "If true (default), return the plan without renaming.", true)
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

        Path folder = folderService.findTitleFolder(titleCode, volumeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No title '" + titleCode + "' on mounted volume '" + volumeId + "'"));

        VolumeFileSystem fs = conn.fileSystem();
        try {
            NormalizationPlan plan = folderService.planNormalization(fs, titleCode, folder, null);

            if (dryRun) {
                return toResult(true, folder.toString(), plan, null);
            }

            List<MovePair> moves = plan.entries().stream()
                    .filter(e -> !e.alreadyCanonical() && !e.conflict() && e.to() != null)
                    .map(e -> new MovePair(e.from(), e.to()))
                    .toList();

            NormalizationOutcome outcome = folderService.executeNormalization(fs, folder, moves);
            return toResult(false, folder.toString(), plan, outcome);
        } catch (IOException e) {
            throw new IllegalArgumentException("normalize_title failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps service output to the stable {@link Result} shape expected by MCP clients.
     *
     * <p>Plan entries that are already canonical become {@link Skip} rows (reason:
     * "already canonical"). Conflict entries become {@link Skip} rows (reason: "naming
     * conflict — provide explicit name override"). Non-canonical, non-conflict entries
     * become {@link Action} rows in {@code planned}; after execution they appear in
     * {@code applied}.
     */
    private static Result toResult(boolean dryRun, String titleFolder,
                                   NormalizationPlan plan, NormalizationOutcome outcome) {
        List<Action> planned = new ArrayList<>();
        List<Skip>   skipped = new ArrayList<>();

        for (NormalizationPlanEntry e : plan.entries()) {
            if (e.alreadyCanonical()) {
                skipped.add(new Skip(e.kind(), e.from(), "already canonical"));
            } else if (e.conflict()) {
                skipped.add(new Skip(e.kind(), e.from(), "naming conflict — provide explicit name override"));
            } else {
                planned.add(new Action(e.kind() + "-rename", e.from(), e.to()));
            }
        }

        if (dryRun) {
            return new Result(true, titleFolder, planned, List.of(), List.of(), skipped);
        }

        // Map applied moves back to Action records.
        List<Action> applied = outcome.moved().stream()
                .map(desc -> {
                    String[] parts = desc.split(" → ", 2);
                    return parts.length == 2
                            ? new Action("rename", parts[0], parts[1])
                            : new Action("rename", desc, "");
                })
                .toList();

        return new Result(false, titleFolder, planned, applied, List.of(), skipped);
    }

    // ── Result records (preserved for MCP-client stability) ───────────────────

    public record Action(String op, String from, String to) {}

    public record Skip(String kind, String filename, String reason) {}

    public record Result(
            boolean dryRun,
            String titleFolder,
            List<Action> planned,
            List<Action> applied,
            List<Action> failed,
            List<Skip> skipped
    ) {}
}
