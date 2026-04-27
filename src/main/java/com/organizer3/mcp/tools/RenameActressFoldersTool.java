package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.command.ActressMergeService;
import com.organizer3.command.ActressMergeService.RenamePlan;
import com.organizer3.command.ActressMergeService.RenameResult;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

import java.io.IOException;
import java.util.List;

/**
 * Renames every misnamed title folder for one actress on the currently mounted volume.
 * Companion to {@code merge_actresses} — call this after a DB-side merge to clean up the
 * filesystem. See {@code spec/PROPOSAL_ACTRESS_NAME_FIX.md}.
 *
 * <p>Locations on volumes other than the mounted one are returned as {@code skipped}.
 * Folders whose names don't start with any known alias appear as {@code unresolvable} —
 * they need manual inspection.
 *
 * <p>Gated on {@code mcp.allowFileOps}. Default {@code dryRun: true}.
 */
public class RenameActressFoldersTool implements Tool {

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final ActressMergeService mergeService;

    public RenameActressFoldersTool(SessionContext session, ActressRepository actressRepo,
                                    ActressMergeService mergeService) {
        this.session = session;
        this.actressRepo = actressRepo;
        this.mergeService = mergeService;
    }

    @Override public String name()        { return "rename_actress_folders"; }
    @Override public String description() {
        return "Rename every misnamed title folder for one actress on the currently mounted volume "
             + "to use her canonical name. Locations on other volumes are returned as 'skipped' so "
             + "you can mount and re-run. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id to fix. Either this or 'name' is required.")
                .prop("name",       "string",  "Canonical name or alias to resolve. Either this or 'actress_id' is required.")
                .prop("fromName",   "string",  "Optional extra source name to match against folder names. Use when the misspelled name can't be registered as an alias (e.g. it's another actress's canonical name).")
                .prop("dryRun",     "boolean", "If true (default), return the plan without renaming.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long idArg       = Schemas.optLong(args, "actress_id", -1);
        String nameArg   = Schemas.optString(args, "name", null);
        String fromName  = Schemas.optString(args, "fromName", null);
        boolean dryRun   = Schemas.optBoolean(args, "dryRun", true);

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

        RenamePlan plan = mergeService.planRenamesFor(actress, fromName);
        try {
            RenameResult result = mergeService.renameOnly(plan, mountedVolumeId, fs, dryRun);
            return toResponse(actress, mountedVolumeId, dryRun, result);
        } catch (IOException e) {
            throw new IllegalArgumentException("rename_actress_folders failed: " + e.getMessage(), e);
        }
    }

    private static Result toResponse(Actress actress, String mountedVolumeId, boolean dryRun,
                                     RenameResult result) {
        List<RenamedRow> renamed = result.renamedPaths().stream()
                .map(p -> new RenamedRow(p.toString())).toList();
        List<SkippedRow> skipped = result.skipped().stream()
                .map(r -> new SkippedRow(r.volumeId(), r.currentPath().toString(), r.newPath().toString()))
                .toList();
        List<UnresolvedRow> unresolvable = result.unresolved().stream()
                .map(u -> new UnresolvedRow(u.volumeId(), u.currentPath().toString()))
                .toList();

        return new Result(
                actress.getId(),
                actress.getCanonicalName(),
                dryRun,
                mountedVolumeId,
                renamed.size(), skipped.size(), unresolvable.size(),
                renamed, skipped, unresolvable);
    }

    public record RenamedRow(String newPath) {}
    public record SkippedRow(String volumeId, String currentPath, String newPath) {}
    public record UnresolvedRow(String volumeId, String currentPath) {}
    public record Result(
            long actressId,
            String canonicalName,
            boolean dryRun,
            String mountedVolumeId,
            int renamedCount,
            int skippedCount,
            int unresolvableCount,
            List<RenamedRow> renamed,
            List<SkippedRow> skipped,
            List<UnresolvedRow> unresolvable
    ) {}
}
