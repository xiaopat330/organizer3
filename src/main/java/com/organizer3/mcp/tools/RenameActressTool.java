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
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.util.List;

/**
 * Atomically renames an actress's canonical name: adds old canonical name as alias, updates
 * {@code actresses.canonical_name}, and optionally renames on-disk title folders.
 *
 * <p>This preserves enrichment metadata — all title enrichment is keyed to {@code title_id},
 * which is unaffected. The alias addition ensures subsequent syncs can still resolve the
 * old folder names if they haven't been renamed on disk yet.
 *
 * <p>DB changes are committed in a single transaction. Disk renames happen after DB commit.
 * If disk renames fail after DB commit, the DB state is rolled back and {@code partialFailure=true}
 * is reported.
 *
 * <p>Gated on {@code mcp.allowMutations}. Disk rename requires an active volume connection;
 * pass {@code renameDisk:false} to skip it.
 */
@Slf4j
public class RenameActressTool implements Tool {

    private final Jdbi jdbi;
    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final ActressMergeService mergeService;

    public RenameActressTool(Jdbi jdbi, SessionContext session,
                             ActressRepository actressRepo, ActressMergeService mergeService) {
        this.jdbi = jdbi;
        this.session = session;
        this.actressRepo = actressRepo;
        this.mergeService = mergeService;
    }

    @Override public String name()        { return "rename_actress"; }
    @Override public String description() {
        return "Atomically rename an actress: adds old canonical name as alias, updates canonical_name "
             + "in DB, optionally renames on-disk title folders. Preserves all enrichment. "
             + "Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id",       "integer", "Actress id to rename. Either this or 'name' is required.")
                .prop("name",             "string",  "Canonical name or alias to resolve. Either this or 'actress_id' is required.")
                .prop("new_canonical_name","string",  "New canonical name to assign.")
                .prop("dry_run",          "boolean", "If true (default), return the plan without committing.", true)
                .prop("rename_disk",      "boolean", "If true (default), rename on-disk title folders after DB commit. Requires a mounted volume.", true)
                .require("new_canonical_name")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long idArg         = Schemas.optLong(args, "actress_id", -1);
        String nameArg     = Schemas.optString(args, "name", null);
        String newName     = Schemas.requireString(args, "new_canonical_name").strip();
        boolean dryRun     = Schemas.optBoolean(args, "dry_run", true);
        boolean renameDisk = Schemas.optBoolean(args, "rename_disk", true);

        if (idArg < 0 && (nameArg == null || nameArg.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'actress_id' or 'name'");
        }
        if (newName.isBlank()) {
            throw new IllegalArgumentException("new_canonical_name must not be blank");
        }

        Actress actress = (idArg >= 0
                ? actressRepo.findById(idArg)
                : actressRepo.resolveByName(nameArg))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actress found for " + (idArg >= 0 ? "id=" + idArg : "name=" + nameArg)));

        String oldName = actress.getCanonicalName();
        long actressId = actress.getId();

        if (oldName.equalsIgnoreCase(newName)) {
            throw new IllegalArgumentException("new_canonical_name is the same as the current canonical name (case-insensitive)");
        }

        // Ensure new name is not already taken by another actress
        actressRepo.findByCanonicalName(newName).ifPresent(existing -> {
            if (existing.getId() != actressId) {
                throw new IllegalArgumentException(
                        "canonical_name '" + newName + "' is already used by actress id=" + existing.getId());
            }
        });
        // Also check if the new name is an alias of any other actress
        actressRepo.resolveByName(newName).ifPresent(existing -> {
            if (existing.getId() != actressId) {
                throw new IllegalArgumentException(
                        "'" + newName + "' is already registered as an alias of actress id=" + existing.getId()
                        + " ('" + existing.getCanonicalName() + "')");
            }
        });

        // Build disk rename plan BEFORE DB commit so we know what needs renaming
        // planRenamesFor uses current alias list — old name not yet aliased, so pass it as fromName
        RenamePlan plan = mergeService.planRenamesFor(actress, null);

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        log.info("RenameActress: actress={} '{}' → '{}' dryRun={} renameDisk={}",
                actressId, oldName, newName, dryRun, renameDisk);

        if (dryRun) {
            // Compute what disk renames would happen without touching anything
            List<DiskRenameRow> diskPlan = plan.renames().stream()
                    .map(r -> new DiskRenameRow(r.volumeId(), r.currentPath().toString(),
                            deriveNewPath(r.currentPath().toString(), oldName, newName)))
                    .toList();
            List<UnresolvedRow> unresolvable = plan.unresolved().stream()
                    .map(u -> new UnresolvedRow(u.volumeId(), u.currentPath().toString()))
                    .toList();
            return new Result(actressId, oldName, newName, true, false,
                    mountedVolumeId, 0, 0, 0, diskPlan, unresolvable, null);
        }

        // ── DB: add alias + update canonical_name ─────────────────────────────
        jdbi.useTransaction(h -> {
            actressRepo.addAlias(actressId, oldName, h);
            actressRepo.updateCanonicalName(actressId, newName, h);
            log.info("RenameActress: DB commit — alias '{}' added, canonical_name updated to '{}'",
                    oldName, newName);
        });

        // Reload actress with new canonical_name and alias list
        Actress renamedActress = actressRepo.findById(actressId).orElseThrow(
                () -> new IllegalStateException("Actress id=" + actressId + " disappeared after rename"));

        if (!renameDisk) {
            return new Result(actressId, oldName, newName, false, false,
                    mountedVolumeId, 0, 0, 0, List.of(), List.of(), null);
        }

        // ── Disk renames ──────────────────────────────────────────────────────
        // Re-plan with updated DB state (new canonical_name, old name now an alias)
        RenamePlan updatedPlan = mergeService.planRenamesFor(renamedActress, null);
        try {
            RenameResult result = mergeService.renameOnly(updatedPlan, mountedVolumeId, fs, false);
            List<DiskRenameRow> renamed = updatedPlan.renames().stream()
                    .filter(r -> result.renamedPaths().contains(r.newPath()))
                    .map(r -> new DiskRenameRow(r.volumeId(), r.currentPath().toString(), r.newPath().toString()))
                    .toList();
            // Any planned rename not in renamedPaths ended up in skipped (other volume)
            List<SkippedRow> skipped = result.skipped().stream()
                    .map(s -> new SkippedRow(s.volumeId(), s.currentPath().toString(), s.newPath().toString()))
                    .toList();
            List<UnresolvedRow> unresolvable = result.unresolved().stream()
                    .map(u -> new UnresolvedRow(u.volumeId(), u.currentPath().toString()))
                    .toList();
            log.info("RenameActress: disk renames — {} renamed, {} skipped, {} unresolvable",
                    result.renamedPaths().size(), skipped.size(), unresolvable.size());
            return new Result(actressId, oldName, newName, false, false,
                    mountedVolumeId, result.renamedPaths().size(), skipped.size(), unresolvable.size(),
                    renamed, unresolvable, null);
        } catch (IOException e) {
            // Disk failed — roll back DB changes
            log.error("RenameActress: disk rename failed after DB commit — attempting DB rollback", e);
            try {
                jdbi.useTransaction(h -> {
                    actressRepo.updateCanonicalName(actressId, oldName, h);
                    h.createUpdate("DELETE FROM actress_aliases WHERE actress_id = :id AND LOWER(alias_name) = LOWER(:name)")
                            .bind("id", actressId)
                            .bind("name", oldName)
                            .execute();
                });
                log.info("RenameActress: DB rollback succeeded");
            } catch (Exception rollbackEx) {
                log.error("RenameActress: DB rollback FAILED — actress id={} name is now '{}' in DB but disk rename failed. Manual intervention required.",
                        actressId, newName, rollbackEx);
                return new Result(actressId, oldName, newName, false, true,
                        mountedVolumeId, 0, 0, 0, List.of(), List.of(),
                        "DB rollback failed after disk error: " + rollbackEx.getMessage() + "; disk error: " + e.getMessage());
            }
            throw new IllegalStateException("Disk rename failed (DB rolled back): " + e.getMessage(), e);
        }
    }

    private static String deriveNewPath(String currentPath, String oldName, String newName) {
        // Best-effort preview: replace first occurrence of old name in the last path segment
        int lastSlash = currentPath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? currentPath.substring(0, lastSlash + 1) : "";
        String folder = lastSlash >= 0 ? currentPath.substring(lastSlash + 1) : currentPath;
        String newFolder = folder.replace(oldName, newName);
        return dir + newFolder;
    }

    public record DiskRenameRow(String volumeId, String oldPath, String newPath) {}
    public record SkippedRow(String volumeId, String currentPath, String newPath) {}
    public record UnresolvedRow(String volumeId, String currentPath) {}
    public record Result(
            long actressId,
            String oldCanonicalName,
            String newCanonicalName,
            boolean dryRun,
            boolean partialFailure,
            String mountedVolumeId,
            int renamedCount,
            int skippedCount,
            int unresolvableCount,
            List<DiskRenameRow> renamed,
            List<UnresolvedRow> unresolvable,
            String errorMessage
    ) {}
}
