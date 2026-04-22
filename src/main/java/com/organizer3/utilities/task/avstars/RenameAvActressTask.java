package com.organizer3.utilities.task.avstars;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;

import java.util.List;
import java.util.Optional;

/**
 * Rename an AV actress. Two modes, matching the CLI command:
 * <ul>
 *   <li>If a different row exists whose {@code folder_name} matches the new name
 *       (physical folder on NAS was renamed), migrate curation + IAFD linkage to that row
 *       and update its stage name.</li>
 *   <li>Otherwise, just update the source row's {@code stage_name} (display-only rename).</li>
 * </ul>
 *
 * <p>Inputs: {@code actressId} (Long), {@code newName} (String).
 */
public final class RenameAvActressTask implements Task {

    public static final String ID = "avstars.rename";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Rename AV actress",
            "Rename or migrate an AV actress's curation to a renamed folder.",
            List.of(new TaskSpec.InputSpec("actressId", "AV actress", TaskSpec.InputSpec.InputType.STRING, true),
                    new TaskSpec.InputSpec("newName",   "New name",   TaskSpec.InputSpec.InputType.STRING, true))
    );

    private final AvActressRepository actressRepo;

    public RenameAvActressTask(AvActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        long actressId = Long.parseLong(String.valueOf(inputs.values().get("actressId")));
        String newName = inputs.getString("newName").trim();

        io.phaseStart("rename", "Rename AV actress");
        try {
            Optional<AvActress> sourceOpt = actressRepo.findById(actressId);
            if (sourceOpt.isEmpty()) {
                io.phaseEnd("rename", "failed", "Unknown av actress id: " + actressId);
                return;
            }
            AvActress source = sourceOpt.get();

            Optional<AvActress> target = actressRepo.findAllByVideoCountDesc().stream()
                    .filter(a -> !a.getId().equals(source.getId()))
                    .filter(a -> a.getFolderName().equalsIgnoreCase(newName))
                    .findFirst();

            if (target.isPresent()) {
                AvActress t = target.get();
                actressRepo.migrateCuration(source.getId(), t.getId());
                actressRepo.updateStageName(t.getId(), newName);
                io.phaseEnd("rename", "ok",
                        "Migrated '" + source.getFolderName() + "' → '" + t.getFolderName() + "' as " + newName);
            } else {
                actressRepo.updateStageName(source.getId(), newName);
                io.phaseEnd("rename", "ok",
                        "Renamed: " + source.getStageName() + " → " + newName);
            }
        } catch (RuntimeException e) {
            io.phaseLog("rename", "Rename failed: " + e.getMessage());
            io.phaseEnd("rename", "failed", e.getMessage());
        }
    }
}
