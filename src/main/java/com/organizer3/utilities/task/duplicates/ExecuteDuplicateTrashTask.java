package com.organizer3.utilities.task.duplicates;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.DuplicateDecisionRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.trash.Trash;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Execute pending TRASH decisions from the Duplicate Triage tool.
 *
 * <p>For each pending decision with {@code decision = 'TRASH'}, moves the title's folder on
 * the appropriate volume into that volume's configured trash area using the {@link Trash}
 * primitive, stamps {@code executed_at}, and removes the corresponding {@code title_locations}
 * row. Safety guard: skips any item where the title has only one remaining location (would
 * leave the title orphaned — user must verify first).
 *
 * <p>Optionally scoped to a single actress via {@code actressKey} input:
 * <ul>
 *   <li>{@code id:N} — only titles attributed to actress with DB id N</li>
 *   <li>{@code name:Actress Name} — only titles whose actressKey matches the name string</li>
 * </ul>
 * If {@code actressKey} is omitted, all pending TRASH decisions are executed.
 */
@Slf4j
public final class ExecuteDuplicateTrashTask implements Task {

    public static final String ID = "duplicates.execute_trash";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Execute duplicate trash",
            "Move folders marked TRASH in Duplicate Triage to the volume's trash area and clean up their DB rows.",
            List.of(new TaskSpec.InputSpec(
                    "actressKey", "Actress filter (optional: id:N or name:Actress Name)",
                    TaskSpec.InputSpec.InputType.STRING, false))
    );

    private final DuplicateDecisionRepository decisionRepo;
    private final TitleLocationRepository locationRepo;
    private final OrganizerConfig config;
    private final SmbConnectionFactory smbFactory;
    private final Jdbi jdbi;
    private final Clock clock;

    public ExecuteDuplicateTrashTask(DuplicateDecisionRepository decisionRepo,
                                     TitleLocationRepository locationRepo,
                                     OrganizerConfig config,
                                     SmbConnectionFactory smbFactory,
                                     Jdbi jdbi) {
        this(decisionRepo, locationRepo, config, smbFactory, jdbi, Clock.systemUTC());
    }

    ExecuteDuplicateTrashTask(DuplicateDecisionRepository decisionRepo,
                              TitleLocationRepository locationRepo,
                              OrganizerConfig config,
                              SmbConnectionFactory smbFactory,
                              Jdbi jdbi,
                              Clock clock) {
        this.decisionRepo = decisionRepo;
        this.locationRepo = locationRepo;
        this.config = config;
        this.smbFactory = smbFactory;
        this.jdbi = jdbi;
        this.clock = clock;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) throws Exception {
        String actressKey = inputs.values().containsKey("actressKey")
                ? inputs.getString("actressKey") : null;

        // ── Plan phase ─────────────────────────────────────────────────────────
        io.phaseStart("plan", "Load pending decisions");
        List<DuplicateDecision> pending = decisionRepo.listPending().stream()
                .filter(d -> "TRASH".equals(d.getDecision()))
                .toList();

        if (actressKey != null && !actressKey.isBlank()) {
            pending = filterByActressKey(pending, actressKey, io);
        }

        if (pending.isEmpty()) {
            io.phaseEnd("plan", "ok", "No pending TRASH decisions");
            return;
        }
        io.phaseEnd("plan", "ok", pending.size() + " location(s) to trash");

        // ── Execute phase ──────────────────────────────────────────────────────
        io.phaseStart("execute", "Execute trash moves");

        int trashed = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("execute", "Cancelled — stopped after " + i + " item(s)");
                break;
            }

            DuplicateDecision decision = pending.get(i);
            io.phaseProgress("execute", i, pending.size(), decision.getNasPath());

            String titleCode = decision.getTitleCode();
            String volumeId  = decision.getVolumeId();
            String nasPath   = decision.getNasPath();

            // Safety: count all locations for this title — skip if only one remains
            long titleId = lookupTitleId(titleCode);
            if (titleId < 0) {
                io.phaseLog("execute", "SKIP " + titleCode + " — title not found in DB");
                skipped++;
                continue;
            }
            List<TitleLocation> allLocations = locationRepo.findByTitle(titleId);
            if (allLocations.size() <= 1) {
                io.phaseLog("execute", "SKIP " + titleCode + " on " + volumeId
                        + " — only " + allLocations.size() + " location(s) remain; will not orphan title");
                skipped++;
                continue;
            }

            // Find the specific location row for this (volumeId, nasPath) pair
            Optional<TitleLocation> locOpt = allLocations.stream()
                    .filter(l -> volumeId.equals(l.getVolumeId()) && matchesNasPath(l, volumeId, nasPath))
                    .findFirst();
            if (locOpt.isEmpty()) {
                io.phaseLog("execute", "SKIP " + titleCode + " on " + volumeId
                        + " — no matching location row for nasPath " + nasPath);
                skipped++;
                continue;
            }
            TitleLocation loc = locOpt.get();

            // Resolve volume + server config
            Optional<VolumeConfig> volOpt = config.findById(volumeId);
            if (volOpt.isEmpty()) {
                io.phaseLog("execute", "FAIL " + titleCode + " — unknown volumeId: " + volumeId);
                failed.add(titleCode + " @ " + volumeId + ": unknown volume");
                continue;
            }
            VolumeConfig vol = volOpt.get();
            Optional<ServerConfig> srvOpt = config.findServerById(vol.server());
            if (srvOpt.isEmpty() || srvOpt.get().trash() == null || srvOpt.get().trash().isBlank()) {
                io.phaseLog("execute", "FAIL " + titleCode + " — server has no trash folder configured");
                failed.add(titleCode + " @ " + volumeId + ": no trash folder");
                continue;
            }
            String trashFolder = srvOpt.get().trash();

            // Share-relative path = nasPath with the smbPath prefix stripped
            String smbPath = vol.smbPath();
            if (!nasPath.startsWith(smbPath)) {
                io.phaseLog("execute", "FAIL " + titleCode + " — nasPath does not start with smbPath: " + nasPath);
                failed.add(titleCode + ": nasPath/smbPath mismatch");
                continue;
            }
            String shareRelative = nasPath.substring(smbPath.length());
            Path itemPath = Path.of(shareRelative);

            try {
                VolumeFileSystem fs = smbFactory.open(volumeId).fileSystem();
                Trash trash = new Trash(fs, volumeId, trashFolder, clock);
                String reason = "Duplicate Triage — TRASH decision for " + titleCode;
                Trash.Result result = trash.trashItem(itemPath, reason);

                String executedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
                decisionRepo.markExecuted(titleCode, volumeId, nasPath, executedAt);
                locationRepo.deleteById(loc.getId());

                log.info("ExecuteDuplicateTrashTask: trashed {} on {} → {}", titleCode, volumeId, result.trashedPath());
                io.phaseLog("execute", "OK " + titleCode + " → " + result.trashedPath());
                trashed++;
            } catch (IOException e) {
                log.warn("ExecuteDuplicateTrashTask: failed to trash {} on {}: {}", titleCode, volumeId, e.getMessage());
                io.phaseLog("execute", "FAIL " + titleCode + " — " + e.getMessage());
                failed.add(titleCode + " @ " + volumeId + ": " + e.getMessage());
            }
        }

        String summary = trashed + " trashed"
                + (skipped > 0 ? " · " + skipped + " skipped" : "")
                + (failed.isEmpty() ? "" : " · " + failed.size() + " failed");
        io.phaseEnd("execute", failed.isEmpty() ? "ok" : "failed", summary);
    }

    private List<DuplicateDecision> filterByActressKey(List<DuplicateDecision> all,
                                                        String actressKey, TaskIO io) {
        if (actressKey.startsWith("id:")) {
            String idStr = actressKey.substring(3).trim();
            long actressId;
            try {
                actressId = Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                io.phaseLog("plan", "Invalid actressKey id — not a number: " + idStr + "; using all decisions");
                return all;
            }
            List<String> titleCodes = jdbi.withHandle(h ->
                    h.createQuery("""
                            SELECT DISTINCT t.code FROM titles t
                            JOIN title_actresses ta ON ta.title_id = t.id
                            WHERE ta.actress_id = :actressId
                            """)
                            .bind("actressId", actressId)
                            .mapTo(String.class)
                            .list());
            io.phaseLog("plan", "Filtering by actress id=" + actressId + " — " + titleCodes.size() + " title(s) match");
            return all.stream().filter(d -> titleCodes.contains(d.getTitleCode())).toList();
        } else if (actressKey.startsWith("name:")) {
            String name = actressKey.substring(5).trim();
            log.warn("ExecuteDuplicateTrashTask: actressKey name:{} — batching all titles attributed to '{}'", name, name);
            io.phaseLog("plan", "actressKey name: matching '" + name + "' — filtering by actressKey string");
            // name: match: filter decisions whose nasPath encodes the actress name (best-effort)
            // Since we can't reliably map a name string to title codes without an actress id,
            // we query by actress canonical name.
            List<String> titleCodes = jdbi.withHandle(h ->
                    h.createQuery("""
                            SELECT DISTINCT t.code FROM titles t
                            JOIN title_actresses ta ON ta.title_id = t.id
                            JOIN actresses a ON a.id = ta.actress_id
                            WHERE LOWER(a.canonical_name) = LOWER(:name)
                            """)
                            .bind("name", name)
                            .mapTo(String.class)
                            .list());
            io.phaseLog("plan", "Actress '" + name + "' maps to " + titleCodes.size() + " title(s)");
            return all.stream().filter(d -> titleCodes.contains(d.getTitleCode())).toList();
        } else {
            io.phaseLog("plan", "Unrecognized actressKey format (expected 'id:N' or 'name:X'); using all decisions");
            return all;
        }
    }

    private long lookupTitleId(String titleCode) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM titles WHERE UPPER(code) = UPPER(:code)")
                        .bind("code", titleCode)
                        .mapTo(Long.class)
                        .findFirst()
                        .orElse(-1L));
    }

    private boolean matchesNasPath(TitleLocation loc, String volumeId, String nasPath) {
        Optional<VolumeConfig> volOpt = config.findById(volumeId);
        if (volOpt.isEmpty()) return false;
        String expected = volOpt.get().smbPath() + loc.getPath().toString();
        return nasPath.equals(expected);
    }
}
